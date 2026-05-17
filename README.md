# bpmnflow-process-runtime-oracle

A Spring Boot runtime that turns `.bpmn` diagrams into persisted, versioned, REST-driven workflow instances — with Oracle 23ai-specific extensions.

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Oracle](https://img.shields.io/badge/Oracle-23ai-red)](https://www.oracle.com/database/23ai/)
[![Liquibase](https://img.shields.io/badge/Liquibase-schema%20migrations-orange)](https://www.liquibase.org/)
[![H2](https://img.shields.io/badge/H2-tests%20%26%20local-blue)](https://h2database.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## What this project adds

This repository extends [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) with Oracle 23ai features that do not belong in the base runtime:

- **JSON Relational Duality Views** — a parallel read/write path where Oracle maintains the full instance state as a single JSON document, in sync with the relational tables.
- **ETag-based OCC (Duality View path)** — every `GET` and `POST /start` response includes an `ETag`. Pass `If-Match` on writes to detect concurrent modifications (HTTP 412 on conflict).
- **`@Version` OCC (JPA path)** — Hibernate manages an `occ_version` column; concurrent writes produce HTTP 409.
- **Oracle UCP connection pool** — replaces HikariCP for the Oracle profile; warms up the SODA context at startup via `initial-pool-size`.
- **Concurrent variable upsert** — `VariableUpsertHelper` runs UPDATE → INSERT → UPDATE on the caller's JDBC connection, avoiding `REQUIRES_NEW` and connection pool contention.
- **Paginated listing** — `GET /workflow` accepts `page` / `size`; responses include `X-Page`, `X-Page-Size`, and `X-Result-Count`.

The Oracle-only migrations (`V006`, `V007`) are scoped to the `oracle` Liquibase context and never run on H2, so local development and tests need no Oracle instance.

---

## Ecosystem

| Repository | Role |
|---|---|
| [bpmnflow-core](https://github.com/jefersonferr/bpmnflow-core) | BPMN parser — reads `.bpmn` + YAML config, returns a `Workflow` object. No state, no database, no Spring. |
| [bpmnflow-spring-boot-starter](https://github.com/jefersonferr/bpmnflow-spring-boot-starter) | Spring Boot auto-configuration — `WorkflowEngine` bean, `/process/**` endpoints, hot-swap support. |
| [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) | Base runtime — persistence, versioned deploy, instance lifecycle, typed variables, activity history. Any relational database. |
| **bpmnflow-process-runtime-oracle** | **This project** — base runtime plus Oracle 23ai Duality Views, ETag OCC, UCP pool, and Wallet support. |
| [bpmnflow-spring-boot-demo](https://github.com/jefersonferr/bpmnflow-spring-boot-demo) | In-memory demo using the starter — no database required. |

---

## How it works

### Deploy pipeline

1. `bpmnflow-core` parses the model and extracts stages, activities, conclusions, rules, and inconsistencies.
2. A DOM parser reads the raw XML and persists participants, lanes, elements, sequence flows, and extension properties.
3. The YAML config is SHA-256 hashed and stored, so config changes do not force a model redeploy.
4. A new process version is created; existing instances keep running on their original version.

### Two runtime paths

`ProcessController` dispatches every operation based on `bpmnflow.duality.enabled`:

```
bpmnflow.duality.enabled=false  (default)
  └─ ProcessInstanceService        → JPA + @Version → HTTP 409 on conflict

bpmnflow.duality.enabled=true
  └─ ProcessInstanceDualityService → Duality View + ETag → HTTP 412 on conflict
```

Both paths use the same REST endpoints and the same relational tables. The only visible difference is the OCC mechanism and the presence of `ETag` / `If-Match` headers.

---

## Prerequisites

| | Minimum |
|---|---|
| Java | 21 |
| Maven | 3.8+ |
| Oracle (production) | 23ai — Duality Views require 23ai |
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

```bash
export BPMNFLOW_DUALITY_ENABLED=true
mvn spring-boot:run -Dspring-boot.run.profiles=oracle
```

Or in `application.yaml`:

```yaml
bpmnflow:
  duality:
    enabled: true
```

---

## API Reference

### Deploy

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

### Workflow

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

Hibernate manages an `occ_version` column on `wf_process_instance`. On every UPDATE, it checks that the stored version matches the one read. If two concurrent transactions both read version `N` and try to save, the second finds 0 rows updated and throws `OptimisticLockException`, which the `GlobalExceptionHandler` maps to HTTP 409.

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

Omitting `If-Match` does not disable OCC: Oracle still enforces it internally via the `_metadata` round-trip. The difference is that `If-Match` makes the intent explicit and gives the client a clearer contract.

After a successful write, the response includes the new ETag, so the next write can proceed without an extra `GET`.

| | Without `If-Match` | With `If-Match` |
|---|---|---|
| No conflict | HTTP 200, new ETag | HTTP 200, new ETag |
| Concurrent write | HTTP 412 | HTTP 412 |

---

## Oracle JSON Relational Duality Views

### `wf_process_instance_dv` — read/write

Exposes `wf_process_instance`, `wf_instance_activity`, and `wf_instance_variable` as a single JSON document. A single `INSERT` or `UPDATE` on this view is decomposed by Oracle into DML across all three tables within the same transaction.

```json
{
  "_id": 42,
  "externalId": "ORDER-001",
  "status": "ACTIVE",
  "processStatus": "IN_PREPARATION",
  "versionId": 1,
  "activities": [
    {"stepNumber": 1, "status": "COMPLETED", "conclusionCode": "ORDER_CONFIRMED"},
    {"stepNumber": 2, "status": "ACTIVE"}
  ],
  "variables": [
    {"variableKey": "customer", "variableType": "STRING", "variableValue": "John"}
  ],
  "_metadata": {"etag": "abc123"}
}
```

### `wf_instance_listing_dv` — read-only

Joins `wf_process_instance` with `bpmn_process_version` and `bpmn_process` to include `processKey`, `processName`, and `versionNumber` in the document, avoiding joins at the service layer for the list endpoint.

Activities are not embedded because Oracle does not permit an explicit `JOIN` inside a Duality View subcollection (ORA-40935). The active activity is fetched separately via a single batch query after the listing documents are read.

---

## Concurrent Variable Upsert

The standard JPA pattern (`findById` + `save`) has a race window: two threads can both see that a variable does not exist and both attempt to insert it, hitting the unique constraint. `VariableUpsertHelper` avoids this with an UPDATE-first approach:

```
1. UPDATE ... WHERE instance_id=? AND variable_key=?
   → 1 row affected: done
   → 0 rows affected: row does not exist yet, proceed to INSERT

2. INSERT ...
   → success: done
   → ORA-00001 (another session inserted first): catch and proceed to step 3

3. UPDATE ... (row now exists)
   → done
```

The `ORA-00001` is caught inside `JdbcTemplate.execute(ConnectionCallback)`, before Spring's `PersistenceExceptionTranslationInterceptor` can wrap it and flag the transaction for rollback. The caller's transaction remains valid.

`REQUIRES_NEW` is not used because it would need a second connection from the pool for every variable write, which causes starvation under concurrent load.

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
| `V007_listing_duality_view.yaml` | oracle | `wf_instance_listing_dv` |
| `V008__occ_version.yaml` | all | `occ_version BIGINT` on `wf_process_instance` |

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

Type mismatches return HTTP 400 before any write reaches the database.

---

## Profiles

| Profile | Database | Notes |
|---------|----------|-------|
| _(default)_ | configurable | Set `spring.datasource` manually |
| `h2` | H2 file | Persists in `~/bpmnflow-runtime.mv.db` |
| `oracle` | Oracle ADB | UCP + mTLS Wallet; oracle Liquibase context applied |
| `test` | H2 in-memory | Clean per test class; used by `mvn test` |

```bash
# H2
mvn spring-boot:run -Dspring-boot.run.profiles=h2

# Oracle, JPA path
ORACLE_URL=... DB_USER=... DB_PASSWORD=... \
  mvn spring-boot:run -Dspring-boot.run.profiles=oracle

# Oracle, Duality View path
ORACLE_URL=... DB_USER=... DB_PASSWORD=... BPMNFLOW_DUALITY_ENABLED=true \
  mvn spring-boot:run -Dspring-boot.run.profiles=oracle
```

---

## Configuration Reference

```yaml
# Oracle profile
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
```

| Variable | Required | Default | Description |
|---|---|---|---|
| `ORACLE_URL` | oracle | — | JDBC URL with TNS alias |
| `DB_USER` | oracle | — | Database user |
| `DB_PASSWORD` | oracle | — | Database password |
| `ORACLE_WALLET_LOCATION` | oracle | `/opt/oracle/wallet` | Path to extracted wallet directory |
| `ORACLE_POOL_INITIAL` | no | `5` | UCP initial connections (warms up SODA) |
| `ORACLE_POOL_MIN` | no | `2` | UCP minimum idle connections |
| `ORACLE_POOL_MAX` | no | `10` | UCP maximum pool size |
| `ORACLE_POOL_WAIT_MS` | no | `30000` | Connection wait timeout in ms |
| `BPMNFLOW_DUALITY_ENABLED` | no | `false` | Switches to the Duality View path |
| `SERVER_PORT` | no | `8080` | HTTP port |
| `H2_URL` | no | `jdbc:h2:file:~/bpmnflow-runtime;MODE=Oracle;AUTO_SERVER=TRUE` | H2 datasource URL |

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

JaCoCo enforces 75% branch coverage; the build fails below that threshold.

```bash
# Coverage report at target/site/jacoco/index.html
mvn test
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
│   │   │   ├── ETagConflictException.java
│   │   │   ├── SwaggerConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── DeployController.java
│   │   │   │   └── ProcessController.java
│   │   │   ├── dto/
│   │   │   │   ├── WorkflowSummaryProjection.java
│   │   │   │   └── WorkflowSummaryResponse.java
│   │   │   ├── duality/
│   │   │   │   ├── doc/
│   │   │   │   │   ├── WfProcessInstanceDoc.java
│   │   │   │   │   ├── WfActivityDoc.java
│   │   │   │   │   ├── WfVariableDoc.java
│   │   │   │   │   └── WfInstanceListingDoc.java
│   │   │   │   ├── repository/
│   │   │   │   │   ├── WfProcessInstanceDualityRepository.java
│   │   │   │   │   └── WfInstanceListingRepository.java
│   │   │   │   └── service/
│   │   │   │       └── ProcessInstanceDualityService.java
│   │   │   ├── model/entity/
│   │   │   ├── repository/
│   │   │   └── service/
│   │   │       ├── BpmnDeployService.java
│   │   │       ├── BpmnCatalogService.java
│   │   │       ├── ProcessInstanceService.java
│   │   │       ├── VariableUpsertHelper.java
│   │   │       └── deploy/
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── pizza-delivery.bpmn
│   │       ├── bpmn-config.yaml
│   │       └── db/changelog/
│   │           ├── V001__metamodel.yaml
│   │           ├── V002__process_versioning.yaml
│   │           ├── V003__structural_layer.yaml
│   │           ├── V004__derived_data.yaml
│   │           ├── V005__runtime.yaml
│   │           ├── V006__duality_views.yaml
│   │           ├── V007_listing_duality_view.yaml
│   │           └── V008__occ_version.yaml
│   └── test/
│       ├── java/org/bpmnflow/runtime/service/
│       └── resources/application-test.yaml
└── pom.xml
```

---

## FAQ

**Do I need Oracle 23ai to use this project?**

No. H2 covers all tests and local development. The Oracle-specific migrations are tagged `dbms: oracle` and Liquibase skips them on H2. The JPA path with `@Version` OCC runs on any Hibernate-supported database.

**What is the difference between HTTP 409 and HTTP 412?**

Both mean a concurrent modification was detected. 409 comes from the JPA path — Hibernate found the `occ_version` mismatch at commit time. 412 comes from the Duality View path — Oracle found the ETag mismatch at the `UPDATE` statement. In both cases the client should re-fetch with `GET` and retry.

**Can I switch between paths at runtime?**

No. `bpmnflow.duality.enabled` is read at startup. Changing it requires a restart.

**Why does the listing not include the active activity?**

Oracle does not allow an explicit `JOIN` inside a Duality View subcollection (ORA-40935). The active activity is fetched with `findActiveByInstanceIdIn` after reading the listing documents — one query for all IDs, not one per document.

**Why UCP instead of HikariCP on Oracle?**

UCP initialises the SODA context when connections are created, so the first request to any SODA-backed endpoint does not pay that cost. HikariCP has no equivalent hook.

**Why not just use `findById` + `save` for variables?**

Two threads can both find that a variable does not exist and both try to insert it, causing a unique constraint violation. The UPDATE-first strategy in `VariableUpsertHelper` turns the constraint violation into a retry instead of an error, and keeps everything in the caller's transaction without opening a second connection.

---

## License

MIT — see [LICENSE](LICENSE).

## Author

[Jeferson Ferreira](https://github.com/jefersonferr)

---

*Part of the BPMNFlow ecosystem:*
*[bpmnflow-core](https://github.com/jefersonferr/bpmnflow-core) · [bpmnflow-spring-boot-starter](https://github.com/jefersonferr/bpmnflow-spring-boot-starter) · [bpmnflow-process-runtime](https://github.com/jefersonferr/bpmnflow-process-runtime) · [bpmnflow-spring-boot-demo](https://github.com/jefersonferr/bpmnflow-spring-boot-demo)*