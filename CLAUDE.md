# CLAUDE.md

Demo (not production) autonomous SRE agent: receives alert emails via webhook, dedups alert floods, extracts structured metadata with Claude, retrieves runbooks via RAG, runs playbook-guided `@Tool` diagnostics, and writes `.md`/`.json` incident reports. See [README.md](README.md) for architecture diagrams and the full API/config reference.

## Stack
- Java 25, Maven multi-module, Spring Boot 4.1.2, Spring AI 2.0.2
- Anthropic Claude (model `claude-sonnet-5-5`, key via `ANTHROPIC_API_KEY`)
- Embeddings: local ONNX `all-MiniLM-L6-v2` (384 dims). Vector store: PostgreSQL 16 + pgvector, or `SimpleVectorStore` under the `local` profile

## Modules (build order)
- `sre-common` — shared records (`EmailWebhookRequest`, `ExtractedAlertInfo`, `IncidentReport`, `AgentInvestigationResult`, `PlaybookChunkResult`, `PlaybookIngestRequest`)
- `playbook-rag` — playbook ingestion/search (`PlaybookRagService`, `PlaybookSeeder`, `PlaybookController`); built-in runbooks in `src/main/resources/playbooks/*.md`; also runnable standalone on port 8081
- `observability-tools` — simulated `@Tool` methods in `ObservabilityTools`
- `sre-agent-app` — port 8080 app: `EmailWebhookController`, `ReportController`, `AlertFloodControlService`, `AlertFingerprinter`, `EmailAlertExtractorService`, `SreInvestigationAgentService`, `LocalFileReportPublisher`, `AiConfig`

## Core logic to understand before editing
`AlertFloodControlService` is a two-stage dedup with single-flight coalescing:
1. Stage 1: `AlertFingerprinter` normalizes the subject (strips pod hashes, UUIDs, IPs, timestamps, ticket numbers, metric values); a match against an active incident means zero LLM calls.
2. Stage 2: after Claude extraction, match on (serviceName, environment) plus token-Jaccard symptom similarity (and same-service P1/CRITICAL correlation); a match skips RAG and tools, and aliases the Stage 1 fingerprint to the incident.
3. Concurrent requests for an in-flight primary investigation wait on its `CompletableFuture`.
Incidents live in an in-memory TTL window (`sre.agent.deduplication.*`), so state is lost on restart.

## Commands
```bash
docker compose up -d                          # pgvector Postgres (skip with --spring.profiles.active=local)
mvn install -DskipTests                       # build all modules (needed before running sre-agent-app)
mvn test                                      # all tests
mvn test -pl sre-agent-app -Dtest=AlertFingerprinterTest   # single test class
set -a && source .env && set +a               # load env (bash); on Windows use .env via VS Code envFile
mvn spring-boot:run -pl sre-agent-app                              # PGVector mode
mvn spring-boot:run -pl sre-agent-app -Dspring-boot.run.profiles=local   # no Docker
```
`sample-requests.http` has ready-made requests. Dedup endpoints: `GET /api/v1/webhooks/deduplication/stats`, `DELETE /api/v1/webhooks/deduplication/cache` (reset between demo runs).

## Config
- `.env` (gitignored; template in `.env.example`) holds `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, Postgres vars. Never commit or print it.
- Main config: `sre-agent-app/src/main/resources/application.yml`. `playbook-rag/.../application-local.yml` excludes datasource and PgVector autoconfig for the `local` profile.
- Reports are written to `./reports/` (gitignored); `sample-reports/` holds committed examples.

## Testing notes
- JUnit + Mockito; tests mock the Spring AI chat client and need the inline mock maker in `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` (present in `sre-agent-app` and `playbook-rag`).
- Surefire runs with `forkCount=0` (set in root `pom.xml`).
- Tools in `observability-tools` return simulated data; no real infra is queried.
