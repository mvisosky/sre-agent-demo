package com.example.sre.agent.service;

import com.example.sre.agent.config.AlertDeduplicationProperties;
import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import com.example.sre.common.model.IncidentReport;
import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AlertFloodControlServiceTest {

    @TempDir
    Path tempReportsDir;

    private MutableClock testClock;
    private AlertDeduplicationProperties properties;
    private LocalFileReportPublisher reportPublisher;
    private AlertFloodControlService floodControlService;

    @BeforeEach
    void setUp() {
        testClock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
        properties = new AlertDeduplicationProperties();
        properties.setEnabled(true);
        properties.setWindow(Duration.ofMinutes(15));
        properties.setSymptomSimilarityThreshold(0.20);
        properties.setCorrelateSameServiceCritical(true);

        reportPublisher = new LocalFileReportPublisher(tempReportsDir.toString());
        floodControlService = new AlertFloodControlService(
            properties,
            new AlertFingerprinter(),
            reportPublisher,
            testClock
        );
    }

    @Test
    void shouldSuppressIdenticalAndPodVariantFloodAtStage1WithZeroDuplicateLlmCalls() {
        AtomicInteger extractionCalls = new AtomicInteger(0);
        AtomicInteger investigationCalls = new AtomicInteger(0);

        EmailWebhookRequest firstEmail = new EmailWebhookRequest(
            "msg-flood-1",
            "alerts@datadog.example.com",
            "[P1] payment-service HTTP 5xx Error Spike on pod payment-service-7d9f8b6c4-x2k9p (34.8%)",
            "HikariPool-1 timeout on /api/v1/payments/charge",
            testClock.instant(),
            Map.of()
        );

        EmailWebhookRequest secondEmail = new EmailWebhookRequest(
            "msg-flood-2",
            "alerts@datadog.example.com",
            "[P1] payment-service HTTP 5xx Error Spike on pod payment-service-7d9f8b6c4-z8m1q (39.1%)",
            "HikariPool-1 timeout on /api/v1/payments/charge",
            testClock.instant().plusSeconds(15),
            Map.of()
        );

        EmailWebhookRequest thirdEmail = new EmailWebhookRequest(
            "msg-flood-3",
            "alerts@datadog.example.com",
            "[P1] payment-service HTTP 5xx Error Spike on pod payment-service-7d9f8b6c4-k4p2w (42.0%)",
            "HikariPool-1 timeout on /api/v1/payments/charge",
            testClock.instant().plusSeconds(30),
            Map.of()
        );

        IncidentReport r1 = floodControlService.processWithFloodControl(
            firstEmail,
            req -> {
                extractionCalls.incrementAndGet();
                return samplePaymentAlertInfo();
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-primary1", info));
            }
        );

        testClock.advance(Duration.ofSeconds(15));
        IncidentReport r2 = floodControlService.processWithFloodControl(
            secondEmail,
            req -> {
                extractionCalls.incrementAndGet();
                return samplePaymentAlertInfo();
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-should-not-run", info));
            }
        );

        testClock.advance(Duration.ofSeconds(15));
        IncidentReport r3 = floodControlService.processWithFloodControl(
            thirdEmail,
            req -> {
                extractionCalls.incrementAndGet();
                return samplePaymentAlertInfo();
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-should-not-run-2", info));
            }
        );

        // Only 1 LLM extraction and 1 primary investigation should have occurred across all 3 emails!
        assertThat(extractionCalls.get()).isEqualTo(1);
        assertThat(investigationCalls.get()).isEqualTo(1);

        assertThat(r1.deduplicated()).isFalse();
        assertThat(r1.occurrenceCount()).isEqualTo(1);

        assertThat(r2.deduplicated()).isTrue();
        assertThat(r2.primaryIncidentId()).isEqualTo("inc-primary1");
        assertThat(r2.occurrenceCount()).isEqualTo(2);

        assertThat(r3.deduplicated()).isTrue();
        assertThat(r3.primaryIncidentId()).isEqualTo("inc-primary1");
        assertThat(r3.occurrenceCount()).isEqualTo(3);
        assertThat(r3.correlatedAlerts()).hasSize(2);
        assertThat(r3.correlatedAlerts().get(0).matchedStage())
            .isEqualTo(AlertFloodControlService.STAGE_1_MATCH);

        var stats = floodControlService.getStats();
        assertThat(stats.totalAlertsReceived()).isEqualTo(3);
        assertThat(stats.primaryInvestigationsTriggered()).isEqualTo(1);
        assertThat(stats.stage1SuppressedCount()).isEqualTo(2);
        assertThat(stats.stage2SuppressedCount()).isEqualTo(0);
    }

    @Test
    void shouldCorrelateDifferentlyWordedServiceAlertAtStage2AndAliasForStage1() {
        AtomicInteger extractionCalls = new AtomicInteger(0);
        AtomicInteger investigationCalls = new AtomicInteger(0);

        // Alert 1: HTTP 5xx alert on payment-service
        EmailWebhookRequest http5xxEmail = new EmailWebhookRequest(
            "msg-s2-1",
            "alerts@datadog.example.com",
            "[P1] High HTTP 5xx Error Rate on payment-service in prod-us-east-1",
            "HTTP 5xx rate at 34.8%",
            testClock.instant(),
            Map.of()
        );

        // Alert 2: Completely different subject line (DB pool alert on payment-service)
        EmailWebhookRequest dbPoolEmail = new EmailWebhookRequest(
            "msg-s2-2",
            "pagerduty@example.com",
            "[CRITICAL] HikariPool-1 Connection Exhaustion & Timeouts on payment-service",
            "Active connections 20/20, 148 threads awaiting connection",
            testClock.instant().plusSeconds(20),
            Map.of()
        );

        // Alert 3: Repeat of Alert 2 (should now be caught at Stage 1 without even calling extraction!)
        EmailWebhookRequest dbPoolEmailRepeat = new EmailWebhookRequest(
            "msg-s2-3",
            "pagerduty@example.com",
            "[CRITICAL] HikariPool-1 Connection Exhaustion & Timeouts on payment-service",
            "Active connections 20/20, 160 threads awaiting connection",
            testClock.instant().plusSeconds(40),
            Map.of()
        );

        IncidentReport r1 = floodControlService.processWithFloodControl(
            http5xxEmail,
            req -> {
                extractionCalls.incrementAndGet();
                return samplePaymentAlertInfo();
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-pay-01", info));
            }
        );

        testClock.advance(Duration.ofSeconds(20));
        IncidentReport r2 = floodControlService.processWithFloodControl(
            dbPoolEmail,
            req -> {
                extractionCalls.incrementAndGet();
                return new ExtractedAlertInfo(
                    "payment-service",
                    "prod-us-east-1",
                    "CRITICAL",
                    "HikariPool-1 Connection Exhaustion",
                    "Database connection pool saturated with 148 waiting threads",
                    List.of("HikariPool-1 connection timeout", "20/20 active connections"),
                    List.of("HikariPool-1", "postgres-payments-db")
                );
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-should-not-run", info));
            }
        );

        testClock.advance(Duration.ofSeconds(20));
        IncidentReport r3 = floodControlService.processWithFloodControl(
            dbPoolEmailRepeat,
            req -> {
                extractionCalls.incrementAndGet();
                return samplePaymentAlertInfo();
            },
            (req, info) -> {
                investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-should-not-run", info));
            }
        );

        // Alert 1 ran extraction + investigation (1, 1)
        // Alert 2 ran extraction, matched Stage 2, skipped investigation, and aliased Stage 1 (2, 1)
        // Alert 3 matched the aliased Stage 1 key directly! (2, 1)
        assertThat(extractionCalls.get()).isEqualTo(2);
        assertThat(investigationCalls.get()).isEqualTo(1);

        assertThat(r1.deduplicated()).isFalse();
        assertThat(r2.deduplicated()).isTrue();
        assertThat(r2.occurrenceCount()).isEqualTo(2);
        assertThat(r2.correlatedAlerts().get(0).matchedStage())
            .isEqualTo(AlertFloodControlService.STAGE_2_MATCH);

        assertThat(r3.deduplicated()).isTrue();
        assertThat(r3.occurrenceCount()).isEqualTo(3);
        assertThat(r3.correlatedAlerts().get(1).matchedStage())
            .isEqualTo(AlertFloodControlService.STAGE_1_MATCH);
    }

    @Test
    void shouldCoalesceConcurrentInFlightAlertBurstIntoSingleInvestigation() throws Exception {
        int burstSize = 8;
        AtomicInteger extractionCalls = new AtomicInteger(0);
        AtomicInteger investigationCalls = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(burstSize);

        try {
            List<Callable<IncidentReport>> tasks = new ArrayList<>();
            for (int i = 0; i < burstSize; i++) {
                final int idx = i;
                tasks.add(() -> {
                    startGate.await();
                    EmailWebhookRequest req = new EmailWebhookRequest(
                        "msg-burst-" + idx,
                        "alerts@datadog.example.com",
                        "[P1] order-service OOMKilled & Kafka Consumer Lag on pod order-service-6b8d9f-p9x" + idx,
                        "Consumer lag 48,500",
                        testClock.instant(),
                        Map.of()
                    );
                    return floodControlService.processWithFloodControl(
                        req,
                        r -> {
                            extractionCalls.incrementAndGet();
                            return new ExtractedAlertInfo(
                                "order-service",
                                "prod-us-east-1",
                                "P1",
                                "Order Service OOMKilled",
                                "Pods crashing with Exit Code 137",
                                List.of("OOMKilled", "Kafka lag"),
                                List.of("orders.created")
                            );
                        },
                        (r, info) -> {
                            try {
                                Thread.sleep(80);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            investigationCalls.incrementAndGet();
                            return reportPublisher.publishReport(sampleDraftReport("inc-burst-01", info));
                        }
                    );
                });
            }

            List<Future<IncidentReport>> futures = new ArrayList<>();
            for (Callable<IncidentReport> task : tasks) {
                futures.add(pool.submit(task));
            }
            startGate.countDown();

            List<IncidentReport> results = new ArrayList<>();
            for (Future<IncidentReport> future : futures) {
                results.add(future.get());
            }

            assertThat(extractionCalls.get()).isEqualTo(1);
            assertThat(investigationCalls.get()).isEqualTo(1);
            assertThat(results).allMatch(r -> "inc-burst-01".equals(r.primaryIncidentId()));
            assertThat(results.stream().filter(r -> !r.deduplicated()).count()).isEqualTo(1);
            assertThat(results.stream().filter(IncidentReport::deduplicated).count()).isEqualTo(burstSize - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldStartNewInvestigationAfterSuppressionWindowExpires() {
        AtomicInteger investigationCalls = new AtomicInteger(0);

        EmailWebhookRequest email = new EmailWebhookRequest(
            "msg-window-1",
            "alerts@datadog.example.com",
            "[P1] payment-service HTTP 5xx Error Spike",
            "Body",
            testClock.instant(),
            Map.of()
        );

        IncidentReport first = floodControlService.processWithFloodControl(
            email,
            req -> samplePaymentAlertInfo(),
            (req, info) -> {
                int count = investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-run-" + count, info));
            }
        );

        // Advance clock past the 15-minute window
        testClock.advance(Duration.ofMinutes(16));

        IncidentReport second = floodControlService.processWithFloodControl(
            email,
            req -> samplePaymentAlertInfo(),
            (req, info) -> {
                int count = investigationCalls.incrementAndGet();
                return reportPublisher.publishReport(sampleDraftReport("inc-run-" + count, info));
            }
        );

        assertThat(investigationCalls.get()).isEqualTo(2);
        assertThat(first.incidentId()).isEqualTo("inc-run-1");
        assertThat(first.deduplicated()).isFalse();
        assertThat(second.incidentId()).isEqualTo("inc-run-2");
        assertThat(second.deduplicated()).isFalse();
    }

    private ExtractedAlertInfo samplePaymentAlertInfo() {
        return new ExtractedAlertInfo(
            "payment-service",
            "prod-us-east-1",
            "P1",
            "Payment Service High HTTP 5xx Rate & DB Timeouts",
            "HikariPool-1 connection timeout after 5000ms",
            List.of("HTTP 5xx error rate 34.8%", "HikariPool-1 connection timeout"),
            List.of("/api/v1/payments/charge", "HikariPool-1")
        );
    }

    private IncidentReport sampleDraftReport(String incidentId, ExtractedAlertInfo info) {
        return new IncidentReport(
            incidentId,
            testClock.instant(),
            info,
            List.of("Payment Service SRE Playbook"),
            List.of(new InvestigationStep(1, "Checked health", "getServiceHealth(payment-service)", "Status: DEGRADED")),
            "HikariPool-1 exhausted due to slow synchronous fraud call",
            "Synchronous fraud verification inside @Transactional",
            List.of("Roll back payment-service to v2.14.1"),
            null,
            null
        );
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
