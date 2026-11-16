# SRE Incident Investigation Report: `inc-f7443efc`

- **Timestamp (UTC)**: 2026-11-16T20:38:03.524960308Z
- **Primary Incident ID**: `inc-f7443efc`
- **Alert Occurrences Correlated**: **2**
- **Last Seen (UTC)**: 2026-11-16T20:41:40.801653922Z
- **Service**: `payment-service`
- **Environment**: `prod-us-east-1`
- **Severity**: **P1**
- **Alert Title**: [CRITICAL / P1] payment-service HTTP 5xx Error Spike & Checkout Latency on pod payment-service-7d9f8b6c4-x2k9p (34.8%)
- **Error Summary**: HTTP 5xx error spike (34.8%) and high checkout latency on payment-service, with HikariPool-1 database connection timeouts on the payment charge endpoint.
- **Reported Symptoms**:
  - HTTP 5xx error rate exceeded 34% (34.8%)
  - p99 latency greater than 5 seconds
  - HikariPool-1 connection timeouts observed

## Alert Flood & Deduplication Timeline
| # | Received At (UTC) | Stage | From | Subject |
|---|---|---|---|---|
| 2 | 2026-11-16T20:41:40.800121033Z | `STAGE_1_PRE_LLM_FINGERPRINT` | pagerduty-alerts@example.com | [CRITICAL / P1] payment-service HTTP 5xx Error Spike & Checkout Latency on pod payment-service-7d9f8b6c4-x2k9p (34.8%) |

## Playbooks Consulted (RAG)
- payment-service Incident Investigation Playbook (service: payment-service)

## Investigation Steps Taken
### Step 1: Checked service health and pod status for payment-service
- **Tool Executed**: `getServiceHealth`
- **Observation**:

```text
Status is DEGRADED. All 4/4 pods are ready with 0 restarts in the last 24h. The readiness probe passes, but /actuator/health/db reports slow connection acquisition (4950ms). Uptime is 38 minutes, which matches the v2.14.2 deployment.
```

### Step 2: Retrieved application metrics for the last 15m
- **Tool Executed**: `getServiceMetrics`
- **Observation**:

```text
The HTTP 5xx rate is 34.8% (baseline <0.1%). Latency is p50 840ms, p95 5,020ms and p99 5,150ms (baseline p99 180ms). CPU is 28% and JVM heap is 54%, so neither is a bottleneck. 192 of 200 Tomcat worker threads are busy, blocked waiting on a DB connection. Request rate is 420 req/s.
```

### Step 3: Inspected HikariCP connection pool statistics
- **Tool Executed**: `getDatabaseConnectionPoolStats`
- **Observation**:

```text
HikariPool-1 is fully saturated: 20/20 active, 0 idle, 148 threads awaiting a connection. There were 1,420 connection timeouts in 15m against a 5,000ms threshold. Average connection hold time is 3,850ms (baseline 18ms).
```

### Step 4: Queried the top ERROR log clusters
- **Tool Executed**: `queryServiceLogs`
- **Observation**:

```text
842 occurrences show PaymentProcessor running synchronous fraud verification inside a @Transactional block, taking about 3,920ms. 1,420 occurrences are SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 5001ms. 310 Hikari ProxyLeakTask warnings show connections held during the external fraud-detection-service HTTP call.
```

### Step 5: Checked recent deployments and config changes
- **Tool Executed**: `getRecentDeployments`
- **Observation**:

```text
v2.14.2 was deployed 38 minutes ago by CI/CD pipeline #8841, commit a94f21c: 'Add synchronous deep fraud scoring call inside PaymentProcessor.charge() @Transactional method'. The previous stable version is v2.14.1, which ran for 12 days with 0 incidents.
```

### Step 6: Checked downstream dependency status
- **Tool Executed**: `getDownstreamDependencyStatus`
- **Observation**:

```text
fraud-detection-service is DEGRADED, with p95 latency of 3,900ms against a 120ms baseline, caused by an ML model cold-scoring spike. postgres-payments-db is HEALTHY (CPU 18%, query time <4ms), and the exhaustion is on the client-side pool. stripe-gateway is HEALTHY (p95 210ms, 99.98% success).
```

## Investigation Findings
The incident started right after v2.14.2 was deployed 38 minutes ago. The service is DEGRADED with a 34.8% HTTP 5xx rate on POST /api/v1/payments/charge. p99 latency is about 5.1s against a 180ms baseline. CPU (28%) and heap (54%) are normal, and pods are not restarting. HikariPool-1 is 100% saturated (20/20 active, 0 idle, 148 threads waiting, 1,420 timeouts in 15m). Average connection hold time rose from 18ms to 3,850ms, and 192/200 Tomcat threads are blocked waiting for a connection. Logs show the new synchronous fraud verification runs inside the @Transactional boundary and takes about 3.9s. Connection-leak warnings confirm DB connections are held during the external fraud-detection-service HTTP call. fraud-detection-service is degraded (p95 3,900ms vs 120ms baseline) because of ML cold-scoring. postgres-payments-db (queries <4ms, CPU 18%) and stripe-gateway are healthy, so they are not the cause. This matches the playbook criteria of an exhausted DB pool plus a recent deployment.

## Root Cause Hypothesis
Release v2.14.2 (commit a94f21c) added a synchronous deep fraud scoring call to fraud-detection-service inside the @Transactional PaymentProcessor.charge() method. Each request holds a HikariPool-1 connection for the whole duration of the slow fraud call, which is now about 3.9s because of the ML cold-scoring spike. With a pool of 20 connections at 420 req/s, the pool is exhausted. Threads queue and hit the 5s connection timeout, which surfaces as SQLTransientConnectionException and HTTP 5xx errors. The database itself is healthy. The root cause is a code regression holding DB connections across a slow external call, made worse by fraud-detection-service degradation.

## Suggested Course of Action
1. Immediately roll back payment-service from v2.14.2 to the previous stable v2.14.1 (12 days with no incidents) and watch the 5xx rate, p99 latency and Hikari active/waiting counts recover.
2. If the rollback is delayed, temporarily raise spring.datasource.hikari.maximum-pool-size from 20 to 40. This is only a stopgap. Confirm postgres-payments-db has connection headroom first.
3. Refactor PaymentProcessor.charge() so the fraud-detection-service HTTP call runs outside the @Transactional database block. Open the transaction only for the DB reads and writes before and after the call.
4. Configure a strict 800ms timeout and circuit breaker on calls to fraud-detection-service, with a defined fallback or fail-fast behavior, so a slow dependency cannot exhaust the pool again.
5. Engage the fraud-detection-service owners to investigate the ML model cold-scoring latency spike (p95 3,900ms vs 120ms baseline) and add warm-up or capacity.
6. Add pre-release checks and alerts for Hikari pool saturation, threads awaiting a connection, and connection hold time. Add code review or static checks that flag external calls inside @Transactional methods, and use canary rollout for payment-service releases.
