# payment-service Incident Investigation Playbook

## Service Overview
- **Service Name**: `payment-service`
- **Owner Team**: Payments Platform SRE
- **Criticality**: Tier-1 (Revenue Critical)
- **Primary Dependencies**: `postgres-payments-db` (HikariCP pool), `fraud-detection-service`, `stripe-gateway`

## Common Alert Scenarios & Investigation Guidelines

### Scenario A: HTTP 5xx Error Spike / High Checkout Latency (`POST /api/v1/payments/charge`)
When an alert indicates elevated HTTP 5xx errors, checkout timeouts, or high latency on `payment-service`, follow these mandatory investigation steps in order:

1. **Step 1 - Check Service Health & Pod Status**:
   - Call `getServiceHealth("payment-service")` to verify whether pods are healthy, degraded, or restarting.
2. **Step 2 - Check Application Metrics**:
   - Call `getServiceMetrics("payment-service", "15m")` to inspect p95/p99 latency, HTTP 5xx rate, CPU, and JVM heap usage.
3. **Step 3 - Inspect Database Connection Pool Statistics**:
   - Call `getDatabaseConnectionPoolStats("payment-service")` to check HikariCP active connections, idle connections, threads awaiting connection, and connection timeout count.
4. **Step 4 - Query Recent Application Logs**:
   - Call `queryServiceLogs("payment-service", "ERROR", 20)` to look for `SQLTransientConnectionException` (`HikariPool-1 - Connection is not available`), unclosed transaction leaks, or downstream gateway timeouts.
5. **Step 5 - Check Recent Deployments & Config Changes**:
   - Call `getRecentDeployments("payment-service")` to see if a recent release introduced a regression (for example, a long-running synchronous fraud check inside a `@Transactional` boundary holding DB connections).
6. **Step 6 - Check Downstream Dependencies**:
   - Call `getDownstreamDependencyStatus("payment-service")` to verify if `fraud-detection-service` or `stripe-gateway` is experiencing elevated latency.

## Root Cause Correlation & Remediation Guidelines
- **If DB Connection Pool is Exhausted AND a Recent Deployment Occurred**:
  - **Root Cause**: A recent release likely holds database connections open while waiting on slow external calls (`fraud-detection-service`), starving `HikariPool-1`.
  - **Suggested Course of Action**:
    1. Immediately roll back `payment-service` to the previous stable version (e.g., `v2.14.1`).
    2. Temporarily increase `spring.datasource.hikari.maximum-pool-size` from `20` to `40` if rollback is delayed.
    3. Refactor the payment processor so external `fraud-detection-service` HTTP calls execute outside the `@Transactional` database block.
    4. Configure a strict 800ms circuit-breaker timeout on calls to `fraud-detection-service`.
