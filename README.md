# SRE Agent Demo (Spring Boot 4.1.2 + Spring AI 2.0.2 + Anthropic Claude + PGVector RAG)

> [!IMPORTANT]
> **Disclaimer — Demonstration & Personal Exploration Only**
> This repository is a **demonstration project** intended strictly for personal learning and exploration of agentic AI concepts (structured extraction, Playbook RAG, tool calling, and alert flood deduplication). It uses simulated observability tools and simplified in-memory/local patterns and is **not** a production-ready codebase.

A multi-module Maven project implementing an autonomous Site Reliability Engineering (SRE) Agent powered by **Spring Boot 4.1.2**, **Spring AI 2.0.2**, **Java 25**, **Anthropic Claude** (`ANTHROPIC_API_KEY`), and **Local ONNX Transformers Embeddings** (`all-MiniLM-L6-v2`).

The agent receives alert emails via webhook, suppresses/correlates alert floods using a **Two-Stage Deduplication & Single-Flight Coalescing Pipeline**, extracts structured service and symptom metadata using Claude, queries service runbooks/playbooks via **RAG** (PostgreSQL + `pgvector` or local in-memory vector store with in-process ONNX embeddings), executes playbook-prescribed diagnostic steps using Spring AI `@Tool` functions, and publishes both **Markdown (`.md`)** and **JSON (`.json`)** incident reports.

---

## Solution Architecture & Workflow

### 1. Component Architecture
```mermaid
flowchart TD
    subgraph Inbound["1. Alert Ingestion & Playbook Management"]
        Email["Alert Email Payload"] -->|"POST /api/v1/webhooks/email"| WebhookCtrl["EmailWebhookController (sre-agent-app)"]
        PlaybookAPI["Playbook Markdown"] -->|"POST /api/v1/playbooks/ingest"| PlaybookCtrl["PlaybookController (playbook-rag)"]
        SeedFiles["Built-in Runbooks (.md)"] -->|"Startup Seeding"| PlaybookSeeder["PlaybookSeeder (playbook-rag)"]
    end

    subgraph FloodControl["2. Two-Stage Alert Flood Control & Single-Flight Correlation"]
        WebhookCtrl --> Orchestrator["SreInvestigationAgentService"]
        Orchestrator --> FloodSvc["AlertFloodControlService (In-Memory TTL Window + Single-Flight)"]
        FloodSvc -->|"Stage 1: Strip pod/UUID/metric noise"| Fingerprinter["AlertFingerprinter"]
        FloodSvc -->|"Stage 1 MISS: Extract Metadata"| Extractor["EmailAlertExtractorService (Claude Structured Output)"]
        Extractor -->|"Stage 2: Service + Symptom Similarity"| FloodSvc
    end

    subgraph AgentCore["3. Primary Investigation (Only on Non-Duplicate Incidents)"]
        FloodSvc -->|"Primary Alert: Query Service Playbook"| PlaybookSvc["PlaybookRagService (playbook-rag)"]
        FloodSvc -->|"Primary Alert: Run Guided Investigation"| ClaudeAgent["Anthropic Claude ChatClient (claude-sonnet-5-5)"]
        FloodSvc -->|"Publish / Update Correlated Report"| Publisher["LocalFileReportPublisher"]
    end

    subgraph RAG["4. Playbook RAG Store"]
        PlaybookCtrl --> PlaybookSvc
        PlaybookSeeder --> PlaybookSvc
        PlaybookSvc <-->|"Local ONNX Embeddings (all-MiniLM-L6-v2, 384d)"| PGVector[("PostgreSQL 16 + PGVector (or Local SimpleVectorStore)")]
    end

    subgraph Tools["5. Observability Tools (observability-tools)"]
        ClaudeAgent <-->|"Spring AI @Tool Calls"| ObsTools["ObservabilityTools"]
        ObsTools --> T1["getServiceHealth()"]
        ObsTools --> T2["getServiceMetrics()"]
        ObsTools --> T3["getDatabaseConnectionPoolStats()"]
        ObsTools --> T4["queryServiceLogs()"]
        ObsTools --> T5["getRecentDeployments()"]
        ObsTools --> T6["getDownstreamDependencyStatus()"]
    end

    subgraph Output["6. Published Reports"]
        Publisher -->|"Writes .md & .json"| LocalFiles["Server Filesystem (./reports/)"]
        LocalFiles <-->|"GET /api/v1/reports"| ReportCtrl["ReportController"]
    end
```

### 2. Two-Stage Alert Flood Control & Single-Flight Pipeline
```mermaid
flowchart TD
    Email["POST /api/v1/webhooks/email<br/>(Incoming Alert Email)"] --> Stage1

    subgraph FloodPipeline["AlertFloodControlService (Sliding TTL Window + Single-Flight)"]
        Stage1["Stage 1: Pre-LLM Fingerprint (AlertFingerprinter)<br/>Normalize subject & strip timestamps, IPs, UUIDs, pod hashes, metrics"]
        Stage1Check{"Active Incident or<br/>In-Flight Future?"}
        Stage1 --> Stage1Check

        Stage1Check -- "HIT (0 Claude Calls)" --> Correlate["Await Single-Flight Future (if in-flight)<br/>Increment occurrenceCount & append flood timeline"]

        Stage1Check -- "MISS" --> Extract["EmailAlertExtractorService (Claude)<br/>Extract serviceName, env, severity, symptoms"]
        Extract --> Stage2["Stage 2: Post-Extraction Correlation<br/>Match (serviceName + env) & symptom Jaccard similarity"]
        Stage2Check{"Active Service Incident<br/>within TTL Window?"}
        Stage2 --> Stage2Check

        Stage2Check -- "HIT (Skips RAG & Tools)" --> BindStage1["Alias Stage 1 Fingerprint -> Active Incident<br/>(Future repeats caught at Stage 1 with 0 LLM calls)"]
        BindStage1 --> Correlate

        Stage2Check -- "MISS (Primary Alert)" --> Investigate["PlaybookRagService + SreInvestigationAgentService<br/>(RAG Search + Claude @Tool Investigation)"]
        Investigate --> Register["Register Active Incident in TTL Window<br/>(Indexed by Stage 1 & Stage 2 keys)"]
    end

    Correlate --> UpdateReport["LocalFileReportPublisher<br/>Update Primary Report (.md / .json) with Flood Counter & Timeline"]
    Register --> PublishNew["LocalFileReportPublisher<br/>Write Primary Report (.md / .json)"]

    UpdateReport --> ResponseDedup["Return IncidentReport<br/>(deduplicated=true, occurrenceCount=N)"]
    PublishNew --> ResponsePrimary["Return IncidentReport<br/>(deduplicated=false, occurrenceCount=1)"]
```

### 3. End-to-End Investigation & Flood Suppression Sequence
```mermaid
sequenceDiagram
    autonumber
    actor AlertSource as Alerting System (Webhook)
    participant Webhook as EmailWebhookController
    participant Flood as AlertFloodControlService
    participant Extractor as EmailAlertExtractorService (Claude)
    participant RAG as PlaybookRagService (PGVector + ONNX)
    participant Claude as Claude Investigation ChatClient
    participant Tools as ObservabilityTools (@Tool)
    participant Publisher as LocalFileReportPublisher

    AlertSource->>Webhook: POST /api/v1/webhooks/email (subject, body, headers)
    Webhook->>Flood: processWithFloodControl(request)
    alt Stage 1 HIT (Normalized Pre-LLM Subject Pattern Active or In-Flight)
        Flood->>Publisher: Increment occurrenceCount & append flood timeline
        Publisher-->>Flood: Updated Primary IncidentReport (deduplicated=true)
        Flood-->>Webhook: Correlated IncidentReport (0 Claude calls)
    else Stage 1 MISS
        Flood->>Extractor: extractAlertInfo(email)
        Extractor-->>Flood: ExtractedAlertInfo (serviceName, env, severity, symptoms)
        alt Stage 2 HIT (Active Incident for Same Service + Correlated Symptoms)
            Flood->>Flood: Alias Stage 1 fingerprint -> Active Incident
            Flood->>Publisher: Increment occurrenceCount & append flood timeline
            Publisher-->>Flood: Updated Primary IncidentReport (deduplicated=true)
            Flood-->>Webhook: Correlated IncidentReport (Skips RAG & Tool Calls)
        else Stage 2 MISS (New Primary Incident)
            Flood->>RAG: queryPlaybooksForService(serviceName, symptomQuery)
            RAG-->>Flood: Matched Playbook Chunks & Guidelines
            Flood->>Claude: Prompt (Alert Context + Playbook Guidelines)
            loop Playbook-Guided Diagnostic Steps
                Claude->>Tools: Invoke @Tool (health, metrics, DB pool, logs, deployments, dependencies)
                Tools-->>Claude: Telemetry & Diagnostic Observations
            end
            Claude-->>Flood: AgentInvestigationResult
            Flood->>Publisher: publishReport(draftReport)
            Publisher-->>Flood: Primary IncidentReport (deduplicated=false, occurrenceCount=1)
            Flood-->>Webhook: Primary IncidentReport
        end
    end
    Webhook-->>AlertSource: 200 OK (Headers: X-SRE-Alert-Deduplicated, X-SRE-Primary-Incident-Id, X-SRE-Alert-Occurrences)
```

---

## Alert Flood Control & Deduplication Strategy

During a production incident ("alert storm"), monitoring systems often emit dozens of similar alerts within seconds. `AlertFloodControlService` and `AlertFingerprinter` handle these floods across three layers:

1. **Stage 1: Pre-LLM Fingerprint Normalization (`0` Claude API Calls)**:
   - Strips dynamic telemetry noise from raw email subjects before calling Claude: Kubernetes pod suffixes (`-7d9f8b6c4-x2k9p`), UUIDs, IPv4 addresses, ISO timestamps, build/ticket numbers (`#8841`), and fluctuating metric readings (`34.8%`, `5,020ms`).
   - If the normalized fingerprint matches an active incident within the suppression window (`sre.agent.deduplication.window`, default `15m`), the alert is immediately correlated into the primary `IncidentReport` with **zero LLM calls**.
2. **Stage 2: Post-Extraction Service & Symptom Correlation (Skips RAG & Tool Investigation)**:
   - When a *differently worded* alert email arrives for the same outage (e.g., `payment-service HTTP 5xx spike` followed by `payment-service HikariPool-1 connection timeout`), Stage 1 lets it through to lightweight Claude metadata extraction.
   - Stage 2 matches the extracted `(serviceName, environment)` scope and checks token Jaccard similarity across `alertTitle`, `errorSummary`, `symptoms`, and `affectedEndpointsOrResources` (plus automatic correlation for concurrent `P1`/`CRITICAL` alerts on the same service).
   - On a Stage 2 match, it **skips RAG and multi-tool investigation**, correlates the alert into the active incident report, and **aliases the new Stage 1 fingerprint** to the active incident so subsequent repeats of that second alert template are caught at Stage 1 with `0` LLM calls.
3. **Single-Flight Concurrency Coalescing (`CompletableFuture`)**:
   - If a burst of concurrent webhook requests arrives simultaneously while the primary investigation (~5–10s) is still running, follower requests wait on the leader's in-flight `CompletableFuture` rather than triggering parallel investigations.

---

## Project Structure

- **`sre-common`**: Shared domain records (`EmailWebhookRequest`, `ExtractedAlertInfo`, `PlaybookIngestRequest`, `PlaybookChunkResult`, `AgentInvestigationResult`, `IncidentReport` with `CorrelatedAlert` flood timeline metadata).
- **`playbook-rag`**: Playbook RAG ingestion and semantic retrieval backed by **PostgreSQL + PGVector** (`spring-ai-starter-vector-store-pgvector`) and **Local ONNX Transformers Embeddings** (`spring-ai-starter-model-transformers`, bundled `all-MiniLM-L6-v2` with `384` dimensions), plus a `local` profile fallback (`SimpleVectorStore`) and built-in playbooks (`payment-service`, `order-service`, `auth-service`).
- **`observability-tools`**: Spring AI `@Tool` functions (`ObservabilityTools`) providing simulated observability & infrastructure checks (`getServiceHealth`, `getServiceMetrics`, `getDatabaseConnectionPoolStats`, `queryServiceLogs`, `getRecentDeployments`, `getDownstreamDependencyStatus`).
- **`sre-agent-app`**: Spring Boot application exposing the email webhook, two-stage alert flood control (`AlertFloodControlService`, `AlertFingerprinter`, `AlertDeduplicationProperties`), playbook ingestion/search endpoints, Anthropic Claude investigation agent orchestrator (`spring-ai-starter-model-anthropic`), and local filesystem report publisher (`./reports/`).

---

## REST API & Webhook Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/webhooks/email` | Receive an alert email, run Two-Stage Flood Control, and execute or correlate an SRE investigation. Returns `IncidentReport` and headers `X-SRE-Alert-Deduplicated`, `X-SRE-Primary-Incident-Id`, and `X-SRE-Alert-Occurrences`. |
| `GET` | `/api/v1/webhooks/deduplication/stats` | Inspect active incident suppression windows, `totalAlertsReceived`, `primaryInvestigationsTriggered`, `stage1SuppressedCount`, and `stage2SuppressedCount`. |
| `DELETE` | `/api/v1/webhooks/deduplication/cache` | Clear all active deduplication windows and reset flood counters (useful when re-running demos). |
| `POST` | `/api/v1/playbooks/ingest` | Ingest or update a Markdown service playbook into the Vector Store. |
| `GET` | `/api/v1/playbooks/search` | Query playbook chunks via semantic search (`?serviceName=...&query=...&topK=4`). |
| `GET` | `/api/v1/reports` | List generated `.md` and `.json` incident reports in `./reports/`. |
| `GET` | `/api/v1/reports/{filename}` | View a specific Markdown or JSON incident report. |

---

## Configuration Properties (`application.yml` / Environment Variables)

| Property | Env Variable | Default | Description |
|---|---|---|---|
| `spring.ai.anthropic.api-key` | `ANTHROPIC_API_KEY` | — | Anthropic API key for Claude chat, extraction, and tool investigation |
| `spring.ai.anthropic.chat.model` | `ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Anthropic Claude model for extraction & tool investigation |
| `spring.ai.vectorstore.pgvector.dimensions` | — | `384` | Vector dimension matching the bundled `all-MiniLM-L6-v2` ONNX embedding model |
| `sre.playbook.seed-on-startup` | `SRE_SEED_PLAYBOOKS` | `true` | Seed built-in playbooks (`payment-service`, `order-service`, `auth-service`) on startup |
| `sre.agent.reports-dir` | `SRE_REPORTS_DIR` | `./reports` | Local directory where `.md` and `.json` incident reports are saved |
| `sre.agent.deduplication.enabled` | `SRE_DEDUP_ENABLED` | `true` | Enable/disable two-stage alert flood deduplication |
| `sre.agent.deduplication.window` | `SRE_DEDUP_WINDOW` | `15m` | Active incident suppression TTL window |
| `sre.agent.deduplication.symptom-similarity-threshold` | `SRE_DEDUP_SIMILARITY_THRESHOLD` | `0.20` | Stage 2 Jaccard token similarity threshold across symptoms/resources |
| `sre.agent.deduplication.correlate-same-service-critical` | `SRE_DEDUP_SAME_SERVICE_CRITICAL` | `true` | Automatically correlate concurrent `P1`/`CRITICAL` alerts on the same service & environment |

---

## Prerequisites

- **Java 25** (or open inside the included **Java 25 Dev Container** in `.devcontainer/`)
- **Maven 3.9+**
- **Docker** (for Dev Container and/or PostgreSQL + `pgvector`)
- **Anthropic API Key** (`ANTHROPIC_API_KEY`)

## Running in the Java 25 Dev Container (VS Code / Antigravity IDE)

1. Open the command palette (`Ctrl+Shift+P`) and run **`Dev Containers: Reopen in Container`**.
   - This builds [.devcontainer/Dockerfile](file:///workspaces/sre-agent-demo/.devcontainer/Dockerfile) (`mcr.microsoft.com/devcontainers/java:25` with Maven) and starts `postgres-pgvector` automatically via [.devcontainer/docker-compose.yml](file:///workspaces/sre-agent-demo/.devcontainer/docker-compose.yml).
2. Add your `ANTHROPIC_API_KEY` to [.env](file:///workspaces/sre-agent-demo/.env) (see [.env.example](file:///workspaces/sre-agent-demo/.env.example)).
3. Launch any configuration from the **Run and Debug** panel (`Ctrl+Shift+D`), configured in [.vscode/launch.json](file:///workspaces/sre-agent-demo/.vscode/launch.json):
   - **`SRE Agent App (PGVector - Port 8080)`**
   - **`SRE Agent App (Local VectorStore / No Docker - Port 8080)`**
   - **`Playbook RAG Service (PGVector - Port 8081)`**
   - **`Playbook RAG Service (Local VectorStore / No Docker - Port 8081)`**

## CLI Quick Start & Testing Alert Flood Suppression

### 1. Start PostgreSQL with PGVector
```bash
docker compose up -d
```

### 2. Set Your Anthropic API Key, Build Modules, and Run the Application
```bash
set -a && source .env && set +a
mvn install -DskipTests
mvn spring-boot:run -pl sre-agent-app
```

### 3. Send Primary Alert Email (Triggers Full RAG + Tool Investigation)
```bash
curl -i -X POST http://localhost:8080/api/v1/webhooks/email \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "alert-20261116-001",
    "from": "pagerduty-alerts@example.com",
    "subject": "[CRITICAL / P1] payment-service HTTP 5xx Error Spike & Checkout Latency on pod payment-service-7d9f8b6c4-x2k9p (34.8%)",
    "body": "Alert triggered for payment-service in prod-us-east-1 on POST /api/v1/payments/charge. HTTP 5xx rate exceeded 34%, p99 latency > 5s, and HikariPool-1 connection timeouts observed."
  }'
```
- Returns `X-SRE-Alert-Deduplicated: false` and `X-SRE-Alert-Occurrences: 1`.

### 4. Simulate a Stage 1 Flood Repeat (Different Pod & Metric Reading -> 0 Claude Calls)
```bash
curl -i -X POST http://localhost:8080/api/v1/webhooks/email \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "alert-20261116-002",
    "from": "pagerduty-alerts@example.com",
    "subject": "[CRITICAL / P1] payment-service HTTP 5xx Error Spike & Checkout Latency on pod payment-service-7d9f8b6c4-z9m4q (41.2%)",
    "body": "Alert triggered for payment-service in prod-us-east-1 on POST /api/v1/payments/charge. HTTP 5xx rate exceeded 41%."
  }'
```
- Returns `X-SRE-Alert-Deduplicated: true` and `X-SRE-Alert-Occurrences: 2` instantaneously without calling Claude.

### 5. Simulate a Stage 2 Related Service Alert (Different Subject Line -> Skips RAG & Tool Calls)
```bash
curl -i -X POST http://localhost:8080/api/v1/webhooks/email \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "alert-20261116-003",
    "from": "datadog-monitors@example.com",
    "subject": "[P1] HikariPool-1 Database Connection Pool Saturated on payment-service",
    "body": "Monitor: HikariPool-1 Active Connections 20/20 in prod-us-east-1. 148 threads awaiting DB connection on payment-service (/api/v1/payments/charge)."
  }'
```
- Returns `X-SRE-Alert-Deduplicated: true` and `X-SRE-Alert-Occurrences: 3`, aliases the new subject into Stage 1, and appends the event to the primary incident report's **Alert Flood & Deduplication Timeline** table.

### 6. Inspect Deduplication Stats & Generated Reports
```bash
curl http://localhost:8080/api/v1/webhooks/deduplication/stats
curl http://localhost:8080/api/v1/reports
```

## Prompt-injection defenses (defence in depth, not a complete control)

Layers: `@SafeText`/`@Size` request validation, `UntrustedContent` fencing of untrusted text, `PromptGuardrailAdvisor`
(scans only the raw email fence), playbook validation at ingest, and server-side tool scoping via `ToolContext`
(tools fail closed and return an error if the requested service differs from the alerted one).
The regex detector is heuristic: it can be evaded by paraphrase, other languages or homoglyphs, and may flag unusual
but benign text. Fencing, role-tag/fence-spoof rules and tool scoping are the most reliable layers.
Blocked requests return `400` (`PROMPT_INJECTION_BLOCKED` or `VALIDATION_FAILED`) without echoing the payload.
