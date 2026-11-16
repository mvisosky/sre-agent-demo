# auth-service Incident Investigation Playbook

## Service Overview
- **Service Name**: `auth-service`
- **Owner Team**: Identity & Access Management SRE
- **Criticality**: Tier-0 (Platform-wide Authentication Gateway)
- **Primary Dependencies**: `redis-session-cache`, `postgres-users-db`

## Common Alert Scenarios & Investigation Guidelines

### Scenario A: Elevated HTTP 401/503 Errors or Token Validation Latency
When an alert indicates login failures, token verification timeouts, or Redis cache saturation on `auth-service`, follow these investigation steps:

1. **Step 1 - Check Service Health**:
   - Call `getServiceHealth("auth-service")` to check replica readiness.
2. **Step 2 - Check Metrics & Cache Hit Ratio**:
   - Call `getServiceMetrics("auth-service", "15m")` to examine token verification latency, CPU usage, and cache hit ratio.
3. **Step 3 - Check Downstream Dependencies (Redis & User DB)**:
   - Call `getDownstreamDependencyStatus("auth-service")` to check `redis-session-cache` latency, connection errors, or key eviction rates.
4. **Step 4 - Check Database Connection Pool**:
   - Call `getDatabaseConnectionPoolStats("auth-service")` to verify if Redis cache misses are causing fallback storm queries against `postgres-users-db`.
5. **Step 5 - Query Error Logs**:
   - Call `queryServiceLogs("auth-service", "ERROR", 20)` to check for `RedisCommandTimeoutException` or `OOM command not allowed when used memory > 'maxmemory'`.

## Root Cause Correlation & Remediation Guidelines
- **If Redis is Evicting Keys / Timing Out AND User DB Pool is Saturated by Cache-Miss Fallbacks**:
  - **Root Cause**: `redis-session-cache` reached `maxmemory` limit due to missing TTL on audit/session keys, causing cache stampede onto `postgres-users-db`.
  - **Suggested Course of Action**:
    1. Scale up `redis-session-cache` memory tier and set eviction policy to `allkeys-lru`.
    2. Enable local Caffeine short-lived JWT public-key/claims cache (60s TTL) in `auth-service` to shield `postgres-users-db` from cache stampedes.
    3. Patch session writer to enforce a strict TTL on all Redis session entries.
