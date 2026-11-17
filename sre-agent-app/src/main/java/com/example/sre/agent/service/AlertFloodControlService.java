package com.example.sre.agent.service;

import com.example.sre.agent.config.AlertDeduplicationProperties;
import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import com.example.sre.common.model.IncidentReport;
import com.example.sre.common.model.IncidentReport.CorrelatedAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

@Service
public class AlertFloodControlService {

    private static final Logger log = LoggerFactory.getLogger(AlertFloodControlService.class);

    public static final String STAGE_1_MATCH = "STAGE_1_PRE_LLM_FINGERPRINT";
    public static final String STAGE_2_MATCH = "STAGE_2_SERVICE_SYMPTOM_CORRELATION";

    private final AlertDeduplicationProperties properties;
    private final AlertFingerprinter fingerprinter;
    private final LocalFileReportPublisher reportPublisher;
    private final Clock clock;

    private final ConcurrentHashMap<String, Stage1Slot> stage1Registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ActiveIncidentRecord>> serviceScopeRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> serviceScopeLocks = new ConcurrentHashMap<>();

    private final AtomicLong totalAlertsReceived = new AtomicLong(0);
    private final AtomicLong primaryInvestigationsTriggered = new AtomicLong(0);
    private final AtomicLong stage1SuppressedCount = new AtomicLong(0);
    private final AtomicLong stage2SuppressedCount = new AtomicLong(0);

    @Autowired
    public AlertFloodControlService(
            AlertDeduplicationProperties properties,
            AlertFingerprinter fingerprinter,
            LocalFileReportPublisher reportPublisher,
            Clock clock) {
        this.properties = properties;
        this.fingerprinter = fingerprinter;
        this.reportPublisher = reportPublisher;
        this.clock = clock;
    }

    public AlertFloodControlService(
            AlertDeduplicationProperties properties,
            AlertFingerprinter fingerprinter,
            LocalFileReportPublisher reportPublisher) {
        this(properties, fingerprinter, reportPublisher, Clock.systemUTC());
    }

    /**
     * Executes the two-stage alert flood deduplication & single-flight correlation pipeline.
     *
     * @param emailRequest Raw incoming webhook email
     * @param extractor    Stage 1 -> Stage 2 LLM metadata extractor
     * @param investigator Primary RAG + Claude tool-calling investigator
     * @return Primary IncidentReport (deduplicated=false) or correlated IncidentReport (deduplicated=true)
     */
    public IncidentReport processWithFloodControl(
            EmailWebhookRequest emailRequest,
            Function<EmailWebhookRequest, ExtractedAlertInfo> extractor,
            BiFunction<EmailWebhookRequest, ExtractedAlertInfo, IncidentReport> investigator) {

        totalAlertsReceived.incrementAndGet();

        if (!properties.isEnabled()) {
            ExtractedAlertInfo extracted = extractor.apply(emailRequest);
            primaryInvestigationsTriggered.incrementAndGet();
            return investigator.apply(emailRequest, extracted);
        }

        Instant now = clock.instant();
        evictExpired(now);

        String stage1Key = fingerprinter.computeStage1Fingerprint(emailRequest);
        AtomicBoolean isStage1Leader = new AtomicBoolean(false);

        Stage1Slot slot = stage1Registry.compute(stage1Key, (key, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                isStage1Leader.set(false);
                return existing;
            }
            isStage1Leader.set(true);
            return new Stage1Slot(key, now.plus(properties.getWindow()));
        });

        // Stage 1 HIT: Either an active incident already exists for this normalized email pattern,
        // or another concurrent request with the same pattern is currently in-flight.
        if (!isStage1Leader.get()) {
            try {
                ActiveIncidentRecord activeRecord = slot.future().join();
                stage1SuppressedCount.incrementAndGet();
                Instant hitTime = clock.instant();
                slot.refreshExpiration(hitTime.plus(properties.getWindow()));
                log.info("Stage 1 Flood Suppression HIT [primaryIncidentId={}, stage1Key='{}'] - skipping LLM extraction & investigation",
                    activeRecord.primaryIncidentId(), stage1Key);
                return activeRecord.recordCorrelatedHit(emailRequest, STAGE_1_MATCH, hitTime);
            } catch (CompletionException ex) {
                throw unwrapCompletionException(ex);
            }
        }

        // Stage 1 MISS (Leader): Extract structured alert metadata via Claude
        try {
            ExtractedAlertInfo alertInfo = extractor.apply(emailRequest);
            String serviceScopeKey = fingerprinter.computeServiceScopeKey(alertInfo);
            Object serviceLock = serviceScopeLocks.computeIfAbsent(serviceScopeKey, k -> new Object());

            synchronized (serviceLock) {
                Instant afterExtractNow = clock.instant();
                ActiveIncidentRecord matchedStage2 = findCorrelatedActiveIncident(serviceScopeKey, alertInfo, afterExtractNow);

                if (matchedStage2 != null) {
                    // Stage 2 HIT: Correlate with existing active incident for this service & alias Stage 1 key
                    stage2SuppressedCount.incrementAndGet();
                    matchedStage2.addStage1Alias(stage1Key);
                    slot.refreshExpiration(afterExtractNow.plus(properties.getWindow()));
                    slot.future().complete(matchedStage2);

                    log.info("Stage 2 Correlation HIT [primaryIncidentId={}, serviceScope='{}', stage1Key='{}'] - skipping RAG & tool investigation",
                        matchedStage2.primaryIncidentId(), serviceScopeKey, stage1Key);
                    return matchedStage2.recordCorrelatedHit(emailRequest, STAGE_2_MATCH, afterExtractNow);
                }

                // Stage 2 MISS: Execute full primary RAG + Claude tool investigation
                primaryInvestigationsTriggered.incrementAndGet();
                IncidentReport primaryReport = investigator.apply(emailRequest, alertInfo);
                Instant completedNow = clock.instant();
                Instant expiresAt = completedNow.plus(properties.getWindow());

                ActiveIncidentRecord newRecord = new ActiveIncidentRecord(
                    primaryReport.incidentId(),
                    alertInfo,
                    serviceScopeKey,
                    stage1Key,
                    primaryReport,
                    completedNow,
                    expiresAt
                );

                serviceScopeRegistry
                    .computeIfAbsent(serviceScopeKey, k -> new CopyOnWriteArrayList<>())
                    .add(newRecord);

                slot.refreshExpiration(expiresAt);
                slot.future().complete(newRecord);
                return primaryReport;
            }
        } catch (RuntimeException | Error ex) {
            stage1Registry.remove(stage1Key, slot);
            slot.future().completeExceptionally(ex);
            throw ex;
        }
    }

    public DeduplicationStats getStats() {
        Instant now = clock.instant();
        evictExpired(now);

        List<ActiveIncidentSummary> activeSummaries = serviceScopeRegistry.values().stream()
            .flatMap(List::stream)
            .filter(r -> !r.isExpired(now))
            .sorted(Comparator.comparing(ActiveIncidentRecord::lastSeenAt).reversed())
            .map(r -> new ActiveIncidentSummary(
                r.primaryIncidentId(),
                r.primaryAlertInfo().serviceName(),
                r.primaryAlertInfo().environment(),
                r.primaryAlertInfo().severity(),
                r.primaryAlertInfo().alertTitle(),
                r.occurrenceCount(),
                r.createdAt(),
                r.lastSeenAt(),
                r.expiresAt(),
                List.copyOf(r.stage1Aliases())
            ))
            .toList();

        return new DeduplicationStats(
            properties.isEnabled(),
            properties.getWindow().toString(),
            totalAlertsReceived.get(),
            primaryInvestigationsTriggered.get(),
            stage1SuppressedCount.get(),
            stage2SuppressedCount.get(),
            activeSummaries.size(),
            activeSummaries
        );
    }

    public void clearAll() {
        stage1Registry.clear();
        serviceScopeRegistry.clear();
        totalAlertsReceived.set(0);
        primaryInvestigationsTriggered.set(0);
        stage1SuppressedCount.set(0);
        stage2SuppressedCount.set(0);
        log.info("Cleared all active alert deduplication state and counters");
    }

    private ActiveIncidentRecord findCorrelatedActiveIncident(
            String serviceScopeKey,
            ExtractedAlertInfo candidateInfo,
            Instant now) {
        List<ActiveIncidentRecord> candidates = serviceScopeRegistry.get(serviceScopeKey);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (ActiveIncidentRecord record : candidates) {
            if (!record.isExpired(now) && fingerprinter.isCorrelatedWithActiveIncident(
                    candidateInfo,
                    record.primaryAlertInfo(),
                    properties.getSymptomSimilarityThreshold(),
                    properties.isCorrelateSameServiceCritical())) {
                return record;
            }
        }
        return null;
    }

    public void evictExpired(Instant now) {
        stage1Registry.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        serviceScopeRegistry.forEach((scopeKey, list) -> {
            list.removeIf(record -> record.isExpired(now));
            if (list.isEmpty()) {
                serviceScopeRegistry.remove(scopeKey, list);
            }
        });
    }

    private RuntimeException unwrapCompletionException(CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new IllegalStateException("Failed while awaiting in-flight primary incident investigation", cause);
    }

    private static final class Stage1Slot {
        private final String stage1Key;
        private final CompletableFuture<ActiveIncidentRecord> future = new CompletableFuture<>();
        private volatile Instant expiresAt;

        private Stage1Slot(String stage1Key, Instant expiresAt) {
            this.stage1Key = stage1Key;
            this.expiresAt = expiresAt;
        }

        private CompletableFuture<ActiveIncidentRecord> future() {
            return future;
        }

        private boolean isExpired(Instant now) {
            return future.isDone() && now.isAfter(expiresAt);
        }

        private void refreshExpiration(Instant newExpiresAt) {
            this.expiresAt = newExpiresAt;
        }
    }

    private final class ActiveIncidentRecord {
        private final String primaryIncidentId;
        private final ExtractedAlertInfo primaryAlertInfo;
        private final String serviceScopeKey;
        private final Set<String> stage1Aliases = ConcurrentHashMap.newKeySet();
        private final Instant createdAt;
        private volatile Instant expiresAt;
        private IncidentReport latestReport;

        private ActiveIncidentRecord(
                String primaryIncidentId,
                ExtractedAlertInfo primaryAlertInfo,
                String serviceScopeKey,
                String initialStage1Key,
                IncidentReport initialReport,
                Instant createdAt,
                Instant expiresAt) {
            this.primaryIncidentId = primaryIncidentId;
            this.primaryAlertInfo = primaryAlertInfo;
            this.serviceScopeKey = serviceScopeKey;
            this.stage1Aliases.add(initialStage1Key);
            this.latestReport = initialReport;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        private synchronized IncidentReport recordCorrelatedHit(
                EmailWebhookRequest request,
                String matchedStage,
                Instant hitTime) {
            Instant alertTimestamp = (request != null && request.receivedAt() != null)
                ? request.receivedAt()
                : hitTime;
            CorrelatedAlert correlatedAlert = new CorrelatedAlert(
                request != null ? request.subject() : "unknown",
                request != null ? request.from() : "unknown",
                alertTimestamp,
                matchedStage
            );

            IncidentReport updated = latestReport.withCorrelatedOccurrence(correlatedAlert, hitTime);
            if (reportPublisher != null) {
                updated = reportPublisher.publishReport(updated);
            }
            this.latestReport = updated;
            this.expiresAt = hitTime.plus(properties.getWindow());
            return updated;
        }

        private void addStage1Alias(String stage1Key) {
            stage1Aliases.add(stage1Key);
        }

        private boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }

        private String primaryIncidentId() {
            return primaryIncidentId;
        }

        private ExtractedAlertInfo primaryAlertInfo() {
            return primaryAlertInfo;
        }

        private synchronized int occurrenceCount() {
            return latestReport.occurrenceCount();
        }

        private Instant createdAt() {
            return createdAt;
        }

        private synchronized Instant lastSeenAt() {
            return latestReport.lastSeenAt();
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private Set<String> stage1Aliases() {
            return stage1Aliases;
        }
    }

    public record DeduplicationStats(
        boolean enabled,
        String windowDuration,
        long totalAlertsReceived,
        long primaryInvestigationsTriggered,
        long stage1SuppressedCount,
        long stage2SuppressedCount,
        int activeIncidentCount,
        List<ActiveIncidentSummary> activeIncidents
    ) {}

    public record ActiveIncidentSummary(
        String primaryIncidentId,
        String serviceName,
        String environment,
        String severity,
        String alertTitle,
        int occurrenceCount,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        List<String> stage1Fingerprints
    ) {}
}
