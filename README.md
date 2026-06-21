# bpmnflow-process-runtime-oracle

> A Spring Boot runtime that turns `.bpmn` diagrams into persisted, versioned, REST-driven workflow instances — with Oracle-specific extensions including JSON Relational Duality Views, ETag OCC, and an embedded MCP agent runtime powered by Oracle Autonomous AI Database 26ai.

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Oracle](https://img.shields.io/badge/Oracle-23ai%20%7C%2026ai-red)](https://www.oracle.com/database/)
[![Liquibase](https://img.shields.io/badge/Liquibase-schema%20migrations-orange)](https://www.liquibase.org/)
[![H2](https://img.shields.io/badge/H2-tests%20%26%20local-blue)](https://h2database.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Table of Contents

- [What this project adds](#what-this-project-adds)
- [Ecosystem](#ecosystem)
- [How it works](#how-it-works)
- [API Handler Providers](#api-handler-providers)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Optimistic Concurrency Control](#optimistic-concurrency-control)
- [Oracle JSON Relational Duality Views](#oracle-json-relational-duality-views)
- [Concurrent Variable Upsert](#concurrent-variable-upsert)
- [Database Schema](#database-schema)
- [Variable Types](#variable-types)
- [Profiles](#profiles)
- [Configuration Reference](#configuration-reference)
- [Running Tests](#running-tests)
- [Project Structure](#project-structure)
- [FAQ](#faq)

---

## What this project adds

This repository extends [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) with Oracle-specific features that do not belong in the base runtime:

- **JSON Relational Duality Views** — a parallel read/write path where Oracle maintains the full instance state as a single JSON document, in sync with the relational tables
- **ETag-based OCC (Duality View path)** — every `GET` and `POST /start` response includes an `ETag`; pass `If-Match` on writes to detect concurrent modifications (HTTP 412 on conflict)
- **`@Version` OCC (JPA path)** — Hibernate manages an `occ_version` column; concurrent writes produce HTTP 409
- **Oracle UCP connection pool** — replaces HikariCP for the Oracle profile; warms up the SODA context at startup via `initial-pool-size`
- **Concurrent variable upsert** — `VariableUpsertHelper` runs UPDATE → INSERT → UPDATE on the caller's JDBC connection, avoiding `REQUIRES_NEW` and connection pool contention
- **Paginated listing** — `GET /workflow` accepts `page` / `size`; responses include `X-Page`, `X-Page-Size`, and `X-Result-Count`
- **API Handler Providers** — pluggable transport layer for BPMN API activities: Spring (`RestTemplate`), PL/SQL (`DBMS_CLOUD.SEND_REQUEST`), and MCP (Select AI Agent via `DBMS_CLOUD_AI_AGENT.RUN_TEAM` on ADB 26ai)

The Oracle-only migrations are scoped to the `oracle` Liquibase context and never run on H2, so local development and tests need no Oracle instance.

---

## Ecosystem

| Repository | Role |
|---|---|
| [bpmnflow-core](https://github.com/jefersonferr/bpmnflow-core) | BPMN parser — reads `.bpmn` + YAML config, returns a `Workflow` object. No state, no database, no Spring. |
| [bpmnflow-spring-boot-starter](https://github.com/jefersonferr/bpmnflow-spring-boot-starter) | Spring Boot auto-configuration — `WorkflowEngine` bean, `/process/**` endpoints, hot-swap support. |
| [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) | Base runtime — persistence, versioned deploy, instance lifecycle, typed variables, activity history, API handler execution. Any relational database. |
| **bpmnflow-process-runtime-oracle** | **This project** — base runtime plus Oracle Duality Views, ETag OCC, UCP pool, Wallet support, PL/SQL and MCP API handler providers. |
| [bpmnflow-spring-boot-demo](https://github.com/jefersonferr/bpmnflow-spring-boot-demo) | In-memory demo using the starter — no database required. |

---

## How it works

### Deploy pipeline

1. `bpmnflow-core` parses the model and extracts stages, activities, conclusions, rules, and inconsistencies.
2. A DOM parser reads the raw XML and persists participants, lanes, elements, sequence flows, and extension properties — including `<bpmnflow:apiHandler>` blocks stored under the `connector.*` namespace in `bpmn_extension_property`.
3. The YAML config is SHA-256 hashed and stored, so config changes do not force a model redeploy.
4. A new process version is created; existing instances keep running on their original version.

### Two runtime paths

`ProcessController` dispatches every operation based on `bpmnflow.duality.enabled`:

```
bpmnflow.duality.enabled=false  (default)
  → ProcessInstanceService          → JPA + @Version → HTTP 409 on conflict

bpmnflow.duality.enabled=true
  → ProcessInstanceDualityService   → Duality View + ETag → HTTP 412 on conflict
```

Both paths use the same REST endpoints and the same relational tables. The only visible difference is the OCC mechanism and the presence of `ETag` / `If-Match` headers.

---

## API Handler Providers

BPMN API activities declare their calls inside `<bpmnflow:apiHandler>` extension properties in the `.bpmn` file. The `ApiHandlerProvider` interface decouples the process model from the transport mechanism — the same `.bpmn` file runs on any provider without modification.

```xml
<bpmnflow:apiHandler>
  <bpmnflow:property name="connector.input.url"    value="https://api.example.com/v1/authorize"/>
  <bpmnflow:property name="connector.input.method" value="POST"/>
  <bpmnflow:property name="connector.input.payload"
    value="${payloadBuilder.build(cliente_id, valor_total)}"/>
  <bpmnflow:property name="connector.output.pagamento_status" value="status"/>
  <bpmnflow:property name="connector.output.pagamento_txn_id" value="txn_id"/>
</bpmnflow:apiHandler>
```

Activate a provider via `application.yaml`:

```yaml
bpmnflow:
  api-handler:
    provider: spring   # spring | plsql | mcp
```

### Provider comparison

| | Spring | PL/SQL | MCP |
|---|---|---|---|
| Minimum Oracle version | Any | Oracle 19c | ADB 26ai |
| Transport | `RestTemplate` | `DBMS_CLOUD.SEND_REQUEST` | `DBMS_CLOUD_AI_AGENT.RUN_TEAM` |
| Parameter selection | Fixed placeholder | Fixed placeholder | Contextual reasoning (ReAct) |
| Network ACL required | No | Yes (`UTL_HTTP`) | No (Instance Principal) |
| Latency | < 1s | < 1s | 5–15s |
| Additional cost | Zero | Zero | OCI GenAI tokens |
| Best for | Universal baseline | Oracle legacy systems | Intelligent processes |

### SpringApiHandlerProvider

Default provider. Resolves `${variable}` placeholders from process instance variables and dispatches the call via `RestTemplate`. No Oracle dependency.

### PlSqlApiHandlerProvider

Delegates to the stored procedure `BPMNFLOW_API_HANDLER.EXECUTE_API` via `JdbcTemplate`. The HTTP call is executed by `DBMS_CLOUD.SEND_REQUEST` inside Oracle, using Instance Principal — no ACL required. Output is returned as CLOB and mapped to process variables by JSONPath.

Requires migration `V009__plsql_api_handler.yaml` (context `oracle`).

### McpApiHandlerProvider

Invokes a [Select AI Agent](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-ai-agent-package.html) team via JDBC on Oracle Autonomous AI Database 26ai. The agent uses the ReAct (Reasoning and Acting) pattern to reason about the process context, construct the API call, invoke `BPMNFLOW_MCP_CALL_API` (a PL/SQL tool backed by `DBMS_CLOUD.SEND_REQUEST`), and return a structured JSON response.

The pipeline:

```
McpApiHandlerProvider (Spring Boot / JDBC)
  └─ DBMS_CLOUD_AI.CREATE_CONVERSATION()        → conversation_id
  └─ DBMS_CLOUD_AI_AGENT.RUN_TEAM
       └─ BPMNFLOW_AGENT (ReAct, enable_human_tool: False)
            └─ BPMNFLOW_API_TASK (call exactly once)
                 └─ BPMNFLOW_API_CALLER (tool)
                      └─ BPMNFLOW_MCP_CALL_API (PL/SQL)
                           └─ DBMS_CLOUD.SEND_REQUEST → REST API
```

Requires migration `V010__mcp_api_handler.yaml` (context `oracle`) and the Oracle objects described below.

#### Oracle prerequisites for MCP

```sql
-- Run as ADMIN
GRANT EXECUTE ON DBMS_CLOUD          TO BPMNFLOW;
GRANT EXECUTE ON DBMS_CLOUD_AI       TO BPMNFLOW;
GRANT EXECUTE ON DBMS_CLOUD_AI_AGENT TO BPMNFLOW;
```

Five objects must be created in the `BPMNFLOW` schema:

| Object | Type | Role |
|---|---|---|
| `OCI_GENAI_CRED` | Credential | OCI API Key for OCI GenAI (Base64 private key body, no PEM header/footer) |
| `BPMNFLOW_AI_PROFILE` | AI Profile | Connects the agent to OCI GenAI with the compartment OCID |
| `BPMNFLOW_MCP_CALL_API` | PL/SQL function | Executes HTTP via `DBMS_CLOUD.SEND_REQUEST` with Instance Principal |
| `BPMNFLOW_API_CALLER` | Tool | Registers the function as an agent-callable tool |
| `BPMNFLOW_AGENT` + `BPMNFLOW_API_TASK` + `BPMNFLOW_TEAM` | Agent / Task / Team | Select AI Agent pipeline with `enable_human_tool: False` |

Configuration:

```yaml
bpmnflow:
  api-handler:
    provider: mcp
    mcp:
      team-name: BPMNFLOW_TEAM
      auth-url: https://dataaccess.adb.<region>.oraclecloudapps.com/adb/auth/v1/databases/<ocid>/token
      username: ${MCP_DB_USERNAME}
      password: ${MCP_DB_PASSWORD}
      token-ttl-minutes: 55
```

---

## Prerequisites

| | Minimum |
|---|---|
| Java | 21 |
| Maven | 3.8+ |
| Oracle (Duality Views / PL/SQL provider) | 23ai |
| Oracle (MCP provider) | Autonomous AI Database 26ai |
| Oracle (tests) | Not required — H2 covers all tests |

---

## Getting Started

### 1. Clone

```bash
git clone https://github.com/jefersonferr/bpmnflow-process-runtime-oracle.git
cd bpmnflow-process-runtime-oracle
```

### 2. Run locally with H2

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

H2 data persists in `~/bpmnflow-runtime.mv.db`. Console: `http://localhost:8080/h2-console`.
Swagger UI: `http://localhost:8080/swagger-ui.html`

### 3. Connect to Oracle Autonomous Database

```bash
export ORACLE_URL="jdbc:oracle:thin:@mydb_low?TNS_ADMIN=/opt/oracle/wallet"
export DB_USER=bpmnflow
export DB_PASSWORD=secret
export ORACLE_WALLET_LOCATION=/opt/oracle/wallet

mvn spring-boot:run -Dspring-boot.run.profiles=oracle
```

`oracle-spring-boot-starter-wallet` picks up `spring.datasource.wallet.location` and configures mTLS automatically. Liquibase applies all migrations on first startup, including the Oracle-only ones.

### 4. Enable the Duality View path

```yaml
bpmnflow:
  duality:
    enabled: true
```

### 5. Enable the MCP provider (ADB 26ai)

```yaml
bpmnflow:
  api-handler:
    provider: mcp
    mcp:
      team-name: BPMNFLOW_TEAM
      auth-url: https://dataaccess.adb.<region>.oraclecloudapps.com/adb/auth/v1/databases/<ocid>/token
      username: ${MCP_DB_USERNAME}
      password: ${MCP_DB_PASSWORD}
```

---

## API Reference

### Deploy — Model Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/bpmn/deploy` | Upload a `.bpmn` file (multipart). Each call to the same `processKey` creates a new version. |
| `GET` | `/bpmn/processes` | List deployed processes with versions and structural counters. |
| `GET` | `/bpmn/processes/{processKey}` | Get a process with all versions, newest first. |

```bash
curl -X POST http://localhost:8080/bpmn/deploy \
  -F "bpmn=@pizza-delivery.bpmn" \
  -F "processKey=PIZZA_DELIVERY"
```

### Process Catalog — Activity Inspection

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/process/activities?versionId={id}` | All activities for a given version. Service tasks with connector definitions include an `apiHandler` block. |
| `GET` | `/process/api-activities?versionId={id}` | Only activities that carry a connector definition. |

### Workflow — Instance Execution

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/workflow/start?versionId={id}` | Start a process instance. Returns `ETag` when Duality View is enabled. |
| `POST` | `/workflow/{instanceId}/complete` | Complete the current activity and move to the next step. Accepts `If-Match`. |
| `GET` | `/workflow/{instanceId}` | Current activity, available conclusions, activity history, variables. Returns `ETag`. |
| `GET` | `/workflow` | Paginated list; optional `status` and `processKey` filters. |
| `PUT` | `/workflow/{instanceId}/variables` | Set or update typed variables. Accepts `If-Match`. |
| `GET` | `/workflow/{instanceId}/variables` | All variables with raw and converted values. |

#### Pagination

```
GET /workflow?status=ACTIVE&page=0&size=50

Response headers:
  X-Page: 0
  X-Page-Size: 50
  X-Result-Count: 23
```

`page` is 0-based. `size` defaults to 50, capped at 200.

---

## Optimistic Concurrency Control

### JPA path — `@Version` / HTTP 409

Hibernate manages an `occ_version` column on `wf_process_instance`. On every UPDATE, it checks that the stored version matches the one read. If two concurrent transactions both read version `N` and try to save, the second finds 0 rows updated and throws `OptimisticLockException`, which `GlobalExceptionHandler` maps to HTTP 409.

No client-side header is needed. The client must re-fetch and retry.

### Duality View path — ETag / HTTP 412

Oracle maintains an ETag for every Duality View document. The ETag is returned in `GET` and `POST /start` responses.

```bash
# 1. Read current state
GET /workflow/42
# ETag: "abc123"

# 2. Write with OCC check
POST /workflow/42/complete
If-Match: "abc123"
Content-Type: application/json
{"conclusionCode": "ORDER_CONFIRMED"}

# If another session modified the instance in between:
# HTTP 412 Precondition Failed
```

---

## Oracle JSON Relational Duality Views

### `wf_process_instance_dv` — read/write

Exposes `wf_process_instance`, `wf_instance_activity`, and `wf_instance_variable` as a single JSON document. A single `INSERT` or `UPDATE` on this view is decomposed by Oracle into DML across all three tables within the same transaction.

### `wf_instance_listing_dv` — read-only

Joins `wf_process_instance` with `bpmn_process_version` and `bpmn_process` to include `processKey`, `processName`, and `versionNumber` in the document, avoiding joins at the service layer for the list endpoint.

Activities are not embedded because Oracle does not permit an explicit `JOIN` inside a Duality View subcollection (ORA-40935). The active activity is fetched separately via a single batch query after the listing documents are read.

---

## Concurrent Variable Upsert

The standard JPA pattern (`findById` + `save`) has a race window: two threads can both see that a variable does not exist and both attempt to insert it, hitting the unique constraint. `VariableUpsertHelper` avoids this with an UPDATE-first approach:

```
1. UPDATE → 1 row: done
2. INSERT → success: done
           → ORA-00001 (race): proceed to step 3
3. UPDATE → done
```

The `ORA-00001` is caught inside `JdbcTemplate.execute(ConnectionCallback)`, before Spring's exception translators can flag the transaction for rollback.

---

## Database Schema

| File | Context | What it creates |
|------|---------|-----------------| 
| `V001__metamodel.yaml` | all | `bpmn_config`, `bpmn_config_property` |
| `V002__process_versioning.yaml` | all | `bpmn_process`, `bpmn_process_version` |
| `V003__structural_layer.yaml` | all | `bpmn_participant`, `bpmn_lane`, `bpmn_element`, `bpmn_sequence_flow`, `bpmn_extension_property` |
| `V004__derived_data.yaml` | all | `process_stage`, `process_activity`, `process_conclusion`, `process_rule`, `process_inconsistency` |
| `V005__runtime.yaml` | all | `wf_process_instance`, `wf_instance_activity`, `wf_instance_variable` |
| `V006__duality_views.yaml` | oracle | `wf_process_instance_dv` |
| `V007__listing_duality_view.yaml` | oracle | `wf_instance_listing_dv` |
| `V008__occ_version.yaml` | all | `occ_version BIGINT` on `wf_process_instance` |
| `V009__plsql_api_handler.yaml` | oracle | `BPMNFLOW_API_HANDLER` package + ACL |
| `V010__mcp_api_handler.yaml` | oracle | `BPMNFLOW_MCP_CALL_API` function + Select AI Agent objects |

---

## Variable Types

Variables are stored as text. Type is enforced on write and applied on read.

| Type | Converts to | Accepted values |
|------|-------------|-----------------|
| `STRING` | `String` | anything |
| `INTEGER` | `Long` | integer string |
| `FLOAT` | `Double` | decimal string |
| `BOOLEAN` | `Boolean` | `true`/`false`, `1`/`0`, `yes`/`no` |
| `DATE` | `LocalDate` | `yyyy-MM-dd` |
| `JSON` | `JsonNode` | valid JSON |

---

## Profiles

| Profile | Database | Notes |
|---------|----------|-------|
| _(default)_ | configurable | Set `spring.datasource` manually |
| `h2` | H2 file | Persists in `~/bpmnflow-runtime.mv.db` |
| `oracle` | Oracle ADB | UCP + mTLS Wallet; `oracle` Liquibase context applied |
| `test` | H2 in-memory | Clean per test class; used by `mvn test` |

---

## Configuration Reference

```yaml
spring:
  datasource:
    url: ${ORACLE_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    type: oracle.ucp.jdbc.PoolDataSource
    oracleucp:
      initial-pool-size: ${ORACLE_POOL_INITIAL:5}
      min-pool-size:     ${ORACLE_POOL_MIN:2}
      max-pool-size:     ${ORACLE_POOL_MAX:10}
      connection-wait-duration-in-millis: ${ORACLE_POOL_WAIT_MS:30000}
    wallet:
      location: ${ORACLE_WALLET_LOCATION:/opt/oracle/wallet}

bpmnflow:
  duality:
    enabled: ${BPMNFLOW_DUALITY_ENABLED:false}
  api-handler:
    provider: ${BPMNFLOW_API_HANDLER_PROVIDER:spring}   # spring | plsql | mcp
    mcp:
      team-name:         ${MCP_TEAM_NAME:BPMNFLOW_TEAM}
      auth-url:          ${MCP_AUTH_URL}
      username:          ${MCP_DB_USERNAME}
      password:          ${MCP_DB_PASSWORD}
      token-ttl-minutes: ${MCP_TOKEN_TTL_MINUTES:55}
```

| Variable | Required | Default | Description |
|---|---|---|---|
| `ORACLE_URL` | oracle | — | JDBC URL with TNS alias |
| `DB_USER` | oracle | — | Database user |
| `DB_PASSWORD` | oracle | — | Database password |
| `ORACLE_WALLET_LOCATION` | oracle | `/opt/oracle/wallet` | Path to extracted wallet directory |
| `ORACLE_POOL_INITIAL` | no | `5` | UCP initial connections |
| `ORACLE_POOL_MIN` | no | `2` | UCP minimum idle connections |
| `ORACLE_POOL_MAX` | no | `10` | UCP maximum pool size |
| `ORACLE_POOL_WAIT_MS` | no | `30000` | Connection wait timeout in ms |
| `BPMNFLOW_DUALITY_ENABLED` | no | `false` | Switches to the Duality View path |
| `BPMNFLOW_API_HANDLER_PROVIDER` | no | `spring` | Active API handler provider |
| `MCP_TEAM_NAME` | mcp | `BPMNFLOW_TEAM` | Select AI Agent team name |
| `MCP_AUTH_URL` | mcp | — | ADB OAuth 2.1 token endpoint |
| `MCP_DB_USERNAME` | mcp | — | Database username for token auth |
| `MCP_DB_PASSWORD` | mcp | — | Database password for token auth |
| `MCP_TOKEN_TTL_MINUTES` | no | `55` | Token refresh interval (Oracle issues 60min tokens) |
| `SERVER_PORT` | no | `8080` | HTTP port |

---

## Running Tests

```bash
mvn test
```

No Oracle instance needed — all tests run on H2 in-memory.

| Suite | Type | Covers |
|-------|------|--------|
| `BpmnDeployServiceTest` | Integration | Full deploy pipeline, version increments, structural persistence |
| `PizzaDeliveryIntegrationTest` | Integration | 4 end-to-end flow scenarios on the real pizza-delivery model |
| `WorkflowFlowTest` | Unit | Step advancement, rule resolution, loop and split gateway |
| `StartProcessTest` | Unit | Instance creation, first activity resolution, null-activity guard |
| `VariableTest` | Unit | Variable persistence via `VariableUpsertHelper` |
| `VariableUpsertHelperTest` | Unit | UPDATE hit, INSERT success, INSERT collision → fallback UPDATE |
| `InstanceQueryTest` | Unit | Paginated list, status and processKey filters, get by ID |
| `ConclusionValidationTest` | Unit | Missing code, invalid code, already-completed guard |
| `VariableTypeTest` | Unit | All 6 types: valid and invalid values, conversion |
| `McpAgentResponseTest` | Unit | Envelope unwrap, brace-balance extraction, Oracle literal newline parsing |
| `McpPromptBuilderTest` | Unit | Prompt structure, imperative form, edge cases |
| `McpApiHandlerProviderTest` | Unit | JDBC two-step sequence, output mapping, failure cases |
| `McpTokenManagerTest` | Unit | Token cache, TTL, invalidation, auth failure |

JaCoCo enforces **75% branch coverage** — the build fails below that threshold.

```bash
# Coverage report at target/site/jacoco/index.html
mvn test site -DgenerateReports=false
```

---

## Project Structure

```
bpmnflow-process-runtime-oracle/
├── src/
│   ├── main/
│   │   ├── java/org/bpmnflow/runtime/
│   │   │   ├── ProcessRuntimeApplication.java
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   ├── api/
│   │   │   │   ├── ApiHandlerContext.java
│   │   │   │   ├── ApiHandlerException.java
│   │   │   │   ├── ApiHandlerExecutor.java
│   │   │   │   ├── ApiHandlerProvider.java
│   │   │   │   ├── SpringApiHandlerProvider.java
│   │   │   │   ├── PlSqlApiHandlerProvider.java
│   │   │   │   └── mcp/
│   │   │   │       ├── McpApiHandlerProvider.java
│   │   │   │       ├── McpAgentResponse.java
│   │   │   │       ├── McpPromptBuilder.java
│   │   │   │       ├── McpProperties.java
│   │   │   │       └── McpTokenManager.java
│   │   │   ├── controller/
│   │   │   ├── duality/
│   │   │   └── service/
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── pizza-delivery.bpmn
│   │       └── db/changelog/
│   │           ├── V001__metamodel.yaml
│   │           ├── V002__process_versioning.yaml
│   │           ├── V003__structural_layer.yaml
│   │           ├── V004__derived_data.yaml
│   │           ├── V005__runtime.yaml
│   │           ├── V006__duality_views.yaml
│   │           ├── V007__listing_duality_view.yaml
│   │           ├── V008__occ_version.yaml
│   │           ├── V009__plsql_api_handler.yaml
│   │           └── V010__mcp_api_handler.yaml
│   └── test/
│       └── java/org/bpmnflow/runtime/
│           └── api/mcp/
│               ├── McpAgentResponseTest.java
│               ├── McpApiHandlerProviderTest.java
│               ├── McpPromptBuilderTest.java
│               └── McpTokenManagerTest.java
└── pom.xml
```

---

## FAQ

**Do I need Oracle to use this project?**

No. H2 covers all tests and local development. Oracle-specific migrations are tagged `dbms: oracle` and Liquibase skips them on H2. The Spring provider and JPA OCC path run on any Hibernate-supported database.

**What Oracle version do I need for each provider?**

Spring provider: any database. PL/SQL provider: Oracle 19c+. MCP provider: Oracle Autonomous AI Database 26ai (Select AI Agent framework).

**What is the difference between HTTP 409 and HTTP 412?**

Both mean a concurrent modification was detected. 409 comes from the JPA path (`occ_version` mismatch at commit time). 412 comes from the Duality View path (ETag mismatch at the UPDATE statement). In both cases the client should re-fetch with `GET` and retry.

**Why does `McpApiHandlerProvider` call `CREATE_CONVERSATION` before `RUN_TEAM`?**

The internal table `DBMS_CLOUD_AI_CONVERSATION_PROMPT$.CONVERSATION_ID#` has a `NOT NULL` constraint. Passing `params => NULL` causes `ORA-01400`. UUIDs generated in Java cause `ORA-20050` (conversation does not exist). The conversation ID must come from Oracle via `DBMS_CLOUD_AI.CREATE_CONVERSATION()`.

**Why does the MCP provider use `enable_human_tool: False`?**

Without it, the agent can enter `WAITING_FOR_HUMAN` state, which blocks indefinitely in a headless runtime. The task instruction also enforces a single tool call to prevent the ReAct loop from iterating beyond the first invocation.

**Can I switch providers at runtime?**

No. `bpmnflow.api-handler.provider` is read at startup. Changing it requires a restart.

**Why UCP instead of HikariCP on Oracle?**

UCP initialises the SODA context when connections are created, so the first request to any SODA-backed endpoint does not pay that cost. HikariCP has no equivalent hook.

**Why not just use `findById` + `save` for variables?**

Two threads can both find that a variable does not exist and both try to insert it, causing a unique constraint violation. The UPDATE-first strategy in `VariableUpsertHelper` turns the constraint into a retry instead of an error, within the caller's transaction and without opening a second connection.

---

## License

MIT — see [LICENSE](LICENSE).

## Author

[Jeferson Ferreira](https://github.com/jefersonferr) · [Oracle ACE Apprentice](https://ace.oracle.com)

---

*Part of the BPMNFlow ecosystem:*
*[bpmnflow-core](https://github.com/jefersonferr/bpmnflow-core) · [bpmnflow-spring-boot-starter](https://github.com/jefersonferr/bpmnflow-spring-boot-starter) · [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) · [bpmnflow-spring-boot-demo](https://github.com/jefersonferr/bpmnflow-spring-boot-demo)*