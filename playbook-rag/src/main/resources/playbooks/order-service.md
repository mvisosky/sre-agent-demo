# order-service Incident Investigation Playbook

## Service Overview
- **Service Name**: `order-service`
- **Owner Team**: Commerce Core SRE
- **Criticality**: Tier-1
- **Primary Dependencies**: `inventory-service`, `kafka-orders-cluster`, `postgres-orders-db`

## Common Alert Scenarios & Investigation Guidelines

### Scenario A: Order Processing Delays / Pod OOMKills / Consumer Lag
When an alert indicates order fulfillment backlog, pod restarts (`OOMKilled`), or high memory utilization in `order-service`, execute the following diagnostic guidelines:

1. **Step 1 - Check Service Health & Restart Count**:
   - Call `getServiceHealth("order-service")` to inspect pod status, recent `OOMKilled` events, and replica availability.
2. **Step 2 - Check JVM & Infrastructure Metrics**:
   - Call `getServiceMetrics("order-service", "15m")` to check JVM heap usage, GC pause times, CPU throttling, and Kafka consumer lag.
3. **Step 3 - Query Application Error Logs**:
   - Call `queryServiceLogs("order-service", "ERROR", 20)` to identify `java.lang.OutOfMemoryError: Java heap space`, large batch deserialization warnings, or `inventory-service` retry loops.
4. **Step 4 - Check Downstream Dependencies**:
   - Call `getDownstreamDependencyStatus("order-service")` to inspect `inventory-service` response times and error rates.
5. **Step 5 - Check Recent Deployments**:
   - Call `getRecentDeployments("order-service")` to see if batch size or retry buffering configuration was recently modified.

## Root Cause Correlation & Remediation Guidelines
- **If Pods are OOMKilled AND Logs Show Unbounded Retry Buffering or Large Batch Fetch**:
  - **Root Cause**: Unbounded in-memory buffering of failed `inventory-service` requests or oversized `max.poll.records` in Kafka consumer config causing heap exhaustion.
  - **Suggested Course of Action**:
    1. Reduce `spring.kafka.consumer.max-poll-records` from `5000` back to `250` via config map update.
    2. Roll back recent deployment or cap the in-memory retry queue using a Dead Letter Topic (`orders.dlq`).
    3. Scale `order-service` replicas horizontally to drain the Kafka consumer lag once memory usage stabilizes.
