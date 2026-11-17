package com.example.sre.tools;

import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ObservabilityTools {

    /** ToolContext key holding the single service the current investigation is allowed to inspect. */
    public static final String ALLOWED_SERVICE_KEY = "allowedServiceName";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTools.class);

    private final ThreadLocal<List<InvestigationStep>> recordedSteps =
        ThreadLocal.withInitial(() -> Collections.synchronizedList(new ArrayList<>()));
    private final ThreadLocal<AtomicInteger> stepCounter =
        ThreadLocal.withInitial(() -> new AtomicInteger(0));

    public void resetTrace() {
        recordedSteps.get().clear();
        stepCounter.get().set(0);
    }

    public List<InvestigationStep> getRecordedSteps() {
        return List.copyOf(recordedSteps.get());
    }

    @Tool(description = "Check current health status, pod readiness, restart count, and uptime of a service")
    public String getServiceHealth(
            @ToolParam(description = "Name of the service, e.g. payment-service, order-service, auth-service") String serviceName,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        log.info("[Tool] getServiceHealth(serviceName={})", norm);

        String result = switch (norm) {
            case "payment-service" -> """
                Service: payment-service (env: prod-us-east-1)
                Status: DEGRADED
                Pods Ready: 4/4 (0 restarts in last 24h)
                Readiness Probe: PASSING, but HTTP /actuator/health/db reports slow connection acquisition (4950ms)
                Uptime: 38 minutes (since deployment v2.14.2)
                """;
            case "order-service" -> """
                Service: order-service (env: prod-us-east-1)
                Status: CRITICAL
                Pods Ready: 2/5 (3 pods in CrashLoopBackOff; Last Termination Reason: OOMKilled, Exit Code 137)
                Restart Count (last 30m): 11 restarts across deployment
                Uptime: Unstable (pods restarting every ~6 minutes)
                """;
            case "auth-service" -> """
                Service: auth-service (env: prod-us-east-1)
                Status: DEGRADED
                Pods Ready: 6/6 (0 restarts)
                Readiness Probe: INTERMITTENT TIMEOUT on /actuator/health/redis
                Uptime: 14 days
                """;
            default -> """
                Service: %s (env: prod-us-east-1)
                Status: DEGRADED
                Pods Ready: 2/2 (0 restarts)
                Note: Elevated response latency detected on primary ingress.
                """.formatted(norm);
        };

        recordStep("Checked service health and pod readiness for " + norm,
            "getServiceHealth(" + norm + ")", result.trim());
        return result.trim();
    }

    @Tool(description = "Fetch key infrastructure, JVM, and HTTP metrics (CPU, memory, latency p95/p99, HTTP error rate) for a service")
    public String getServiceMetrics(
            @ToolParam(description = "Name of the service, e.g. payment-service") String serviceName,
            @ToolParam(description = "Time window, e.g. 15m, 1h") String window,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        String win = (window == null || window.isBlank()) ? "15m" : window.trim();
        log.info("[Tool] getServiceMetrics(serviceName={}, window={})", norm, win);

        String result = switch (norm) {
            case "payment-service" -> """
                Metrics for payment-service (window: %s):
                - HTTP Request Rate: 420 req/s
                - HTTP 5xx Error Rate: 34.8%% (baseline: <0.1%%)
                - Latency p50: 840ms | p95: 5,020ms | p99: 5,150ms (baseline p99: 180ms)
                - CPU Utilization: 28%% (normal)
                - JVM Heap Usage: 54%% (1.1 GB / 2.0 GB)
                - Tomcat Worker Threads Busy: 192 / 200 (blocked awaiting DB connection)
                """.formatted(win);
            case "order-service" -> """
                Metrics for order-service (window: %s):
                - Kafka Consumer Lag (topic: orders.created): 48,500 messages (growing +1,200 msg/min)
                - JVM Heap Usage: 98.7%% (1.97 GB / 2.0 GB before OOMKill)
                - GC Pause Time: 4,200ms/min (Full GC thrashing)
                - CPU Utilization: 94%% (GC threads saturated)
                - HTTP 5xx Error Rate: 18.2%%
                """.formatted(win);
            case "auth-service" -> """
                Metrics for auth-service (window: %s):
                - Token Validation Rate: 1,850 req/s
                - HTTP 503 Error Rate: 21.4%%
                - Latency p95: 2,400ms (baseline: 12ms)
                - Redis Cache Hit Ratio: 14.2%% (dropped from 99.1%%)
                - CPU Utilization: 76%%
                """.formatted(win);
            default -> """
                Metrics for %s (window: %s):
                - HTTP 5xx Error Rate: 12.0%%
                - Latency p95: 1,950ms
                - CPU Utilization: 65%%, JVM Heap Usage: 70%%
                """.formatted(norm, win);
        };

        recordStep("Fetched infrastructure, JVM, and HTTP metrics for " + norm + " (" + win + ")",
            "getServiceMetrics(" + norm + ", " + win + ")", result.trim());
        return result.trim();
    }

    @Tool(description = "Check database connection pool statistics (active, idle, waiting threads, connection timeouts) for a service")
    public String getDatabaseConnectionPoolStats(
            @ToolParam(description = "Name of the service, e.g. payment-service") String serviceName,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        log.info("[Tool] getDatabaseConnectionPoolStats(serviceName={})", norm);

        String result = switch (norm) {
            case "payment-service" -> """
                Database Connection Pool (HikariPool-1 -> postgres-payments-db) for payment-service:
                - Maximum Pool Size: 20
                - Active Connections: 20 / 20 (100%% saturated)
                - Idle Connections: 0
                - Threads Awaiting Connection: 148
                - Connection Timeout Threshold: 5,000ms
                - Connection Timeouts (last 15m): 1,420
                - Average Connection Hold Time: 3,850ms (baseline: 18ms)
                """;
            case "order-service" -> """
                Database Connection Pool (HikariPool-1 -> postgres-orders-db) for order-service:
                - Maximum Pool Size: 30
                - Active Connections: 8 / 30
                - Idle Connections: 22
                - Threads Awaiting Connection: 0
                - Connection Timeouts (last 15m): 0
                """;
            case "auth-service" -> """
                Database Connection Pool (HikariPool-1 -> postgres-users-db) for auth-service:
                - Maximum Pool Size: 25
                - Active Connections: 25 / 25 (100%% saturated due to Redis cache miss fallback storm)
                - Idle Connections: 0
                - Threads Awaiting Connection: 89
                - Connection Timeouts (last 15m): 610
                """;
            default -> """
                Database Connection Pool for %s:
                - Maximum Pool Size: 20, Active: 6, Idle: 14, Waiting: 0
                """.formatted(norm);
        };

        recordStep("Inspected database connection pool statistics for " + norm,
            "getDatabaseConnectionPoolStats(" + norm + ")", result.trim());
        return result.trim();
    }

    @Tool(description = "Query recent application logs filtered by severity (ERROR, WARN, INFO) for a service")
    public String queryServiceLogs(
            @ToolParam(description = "Name of the service, e.g. payment-service") String serviceName,
            @ToolParam(description = "Log severity filter, e.g. ERROR or WARN") String severityFilter,
            @ToolParam(description = "Maximum number of log entries to return") Integer limit,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        String sev = (severityFilter == null || severityFilter.isBlank()) ? "ERROR" : severityFilter.toUpperCase();
        int max = (limit == null || limit <= 0) ? 10 : limit;
        log.info("[Tool] queryServiceLogs(serviceName={}, severityFilter={}, limit={})", norm, sev, max);

        String result = switch (norm) {
            case "payment-service" -> """
                Recent %s logs for payment-service (showing top %d clusters):
                1. [842 occurrences] ERROR c.e.p.service.PaymentProcessor : Synchronous fraud verification inside @Transactional block took 3920ms for txId=tx-99481
                2. [1,420 occurrences] ERROR o.s.t.i.TransactionInterceptor : Could not open JPA EntityManager for transaction; nested exception is java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 5001ms.
                3. [310 occurrences] WARN  c.z.h.pool.ProxyLeakTask : Connection leak detection triggered for org.postgresql.jdbc.PgConnection on thread http-nio-8080-exec-42 (held during external fraud-detection-service HTTP call)
                """.formatted(sev, max);
            case "order-service" -> """
                Recent %s logs for order-service (showing top %d clusters):
                1. [42 occurrences] ERROR o.s.k.l.KafkaMessageListenerContainer : Consumer poll batch fetched 5000 records (payload size ~340MB)
                2. [19 occurrences] ERROR c.e.o.consumer.OrderFulfillmentWorker : Downstream inventory-service timeout; buffering 5000 failed order payloads in InMemoryRetryBuffer (current buffer size: 45,000 orders)
                3. [3 occurrences] ERROR org.apache.catalina.core.ContainerBase : Uncaught exception: java.lang.OutOfMemoryError: Java heap space at com.example.order.retry.InMemoryRetryBuffer.enqueue(InMemoryRetryBuffer.java:88)
                """.formatted(sev, max);
            case "auth-service" -> """
                Recent %s logs for auth-service (showing top %d clusters):
                1. [2,150 occurrences] ERROR c.e.a.token.RedisTokenStore : io.lettuce.core.RedisCommandTimeoutException: Command timed out after 500ms on redis-session-cache:6379
                2. [640 occurrences] ERROR c.e.a.token.RedisTokenStore : Redis error: OOM command not allowed when used memory > 'maxmemory' (session-audit-* keys missing TTL)
                3. [610 occurrences] ERROR c.e.a.service.AuthService : Fallback DB lookup failed: HikariPool-1 - Connection is not available, request timed out after 3000ms
                """.formatted(sev, max);
            default -> """
                Recent %s logs for %s:
                1. [25 occurrences] ERROR Upstream request timeout after 3000ms
                """.formatted(sev, norm);
        };

        recordStep("Queried recent " + sev + " application logs for " + norm,
            "queryServiceLogs(" + norm + ", " + sev + ", " + max + ")", result.trim());
        return result.trim();
    }

    @Tool(description = "Check recent deployments, release versions, and configuration changes for a service")
    public String getRecentDeployments(
            @ToolParam(description = "Name of the service, e.g. payment-service") String serviceName,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        log.info("[Tool] getRecentDeployments(serviceName={})", norm);

        String result = switch (norm) {
            case "payment-service" -> """
                Recent deployments for payment-service:
                1. Version v2.14.2 — Deployed 38 minutes ago by CI/CD pipeline #8841
                   - Commit: a94f21c ("Add synchronous deep fraud scoring call inside PaymentProcessor.charge() @Transactional method")
                   - Previous Stable Version: v2.14.1 (ran for 12 days with 0 incidents)
                """;
            case "order-service" -> """
                Recent deployments for order-service:
                1. Version v4.8.0 — Deployed 52 minutes ago by CI/CD pipeline #8839
                   - Commit: 7c31b0e ("Increase spring.kafka.consumer.max-poll-records from 250 to 5000 and enable in-memory retry queue")
                   - Previous Stable Version: v4.7.9
                """;
            case "auth-service" -> """
                Recent deployments for auth-service:
                1. Version v1.9.4 — Deployed 3 days ago
                   - Commit: 2b88d11 ("Store verbose session audit trail in Redis key prefix session-audit-*")
                   - Note: Memory growth on redis-session-cache accumulated over 72 hours due to missing key expiration TTL.
                """;
            default -> """
                Recent deployments for %s:
                - No deployments in the last 24 hours.
                """.formatted(norm);
        };

        recordStep("Checked recent deployments and configuration changes for " + norm,
            "getRecentDeployments(" + norm + ")", result.trim());
        return result.trim();
    }

    @Tool(description = "Check status, error rates, and latency of downstream dependencies and external APIs for a service")
    public String getDownstreamDependencyStatus(
            @ToolParam(description = "Name of the service, e.g. payment-service") String serviceName,
            ToolContext toolContext) {
        String norm = resolveService(serviceName, toolContext);
        log.info("[Tool] getDownstreamDependencyStatus(serviceName={})", norm);

        String result = switch (norm) {
            case "payment-service" -> """
                Downstream Dependencies for payment-service:
                1. fraud-detection-service: DEGRADED (p95 latency: 3,900ms, baseline: 120ms; synchronous ML model cold-scoring spike)
                2. postgres-payments-db: HEALTHY (CPU 18%%, query execution time <4ms; client-side Hikari pool in payment-service is exhausted waiting on open transactions)
                3. stripe-gateway: HEALTHY (p95 latency: 210ms, HTTP 200 rate: 99.98%%)
                """;
            case "order-service" -> """
                Downstream Dependencies for order-service:
                1. inventory-service: DEGRADED (HTTP 429 rate limiting & 2,800ms latency spike)
                2. kafka-orders-cluster: HEALTHY (brokers online, consumer group 'order-service-cg' lagging by 48,500 records)
                3. postgres-orders-db: HEALTHY
                """;
            case "auth-service" -> """
                Downstream Dependencies for auth-service:
                1. redis-session-cache: CRITICAL (Memory usage: 100%% of 16GB maxmemory; eviction policy: noeviction; rejecting writes with OOM and timing out reads)
                2. postgres-users-db: OVERLOADED (receiving 1,600 queries/sec from auth-service cache-miss fallbacks)
                """;
            default -> """
                Downstream Dependencies for %s:
                - All registered downstream dependencies reporting nominal status.
                """.formatted(norm);
        };

        recordStep("Checked downstream dependency health and latency for " + norm,
            "getDownstreamDependencyStatus(" + norm + ")", result.trim());
        return result.trim();
    }

    private void recordStep(String action, String toolUsed, String observation) {
        int stepNum = stepCounter.get().incrementAndGet();
        recordedSteps.get().add(new InvestigationStep(stepNum, action, toolUsed, observation));
    }

    /**
     * Least-privilege scoping: when the server bound an allowed service in the ToolContext, the
     * LLM-supplied serviceName is ignored if it differs (prompt-injected cross-service queries).
     */
    private String resolveService(String requested, ToolContext toolContext) {
        String requestedNorm = normalize(requested);
        if (toolContext != null && toolContext.getContext().get(ALLOWED_SERVICE_KEY) instanceof String allowed
                && !allowed.isBlank()) {
            String allowedNorm = normalize(allowed);
            if (!allowedNorm.equals(requestedNorm)) {
                log.warn("SECURITY: tool call for out-of-scope service rejected; using allowed service='{}'", allowedNorm);
            }
            return allowedNorm;
        }
        return requestedNorm;
    }

    private String normalize(String serviceName) {
        return serviceName == null ? "unknown-service" : serviceName.trim().toLowerCase();
    }
}
