# dcre-cde

Collection Day Estimator (R-37: schedules only, never emits). PASS verdicts + `tx_header.business_date` + offset-days -> `cde_schedule` upsert keyed (arrival_id, sequence). 3-tier: ScheduleTasklet -> ScheduleService -> data/repo. SYNTHETIC-CONTRACT date rule (A-3 residual). Since SCRUM-37 the schedule applies the R-38 process-date roll against a fail-closed holiday calendar and WARN-logs every excluded verdict.

## Pipeline position

Per-file DAG stage in the Collections DAG (SPEC-DAG-PIPELINE): downstream of CTV in the fork `CTV -> { CDE || CIR }` (via AIS on ENDO); CDE's own downstream is CRW, the Process-Date Executor that emits what CDE scheduled. Launched by AGT as an ephemeral Kubernetes Job per arrival; DB-only stage, no file I/O (R-30).

## Job structure and key rules

`cdeJob` = single tasklet step `scheduleStep`: `ScheduleTasklet` (thin entry adapter) -> `ScheduleService` (business tier) -> `data/repo`. Identifying JobParameter: `arrival.id` (UUID string); the count of scheduled transactions lands in the execution context as `scheduled`.

- Collection_Date = `tx_header.business_date` + `dcre.cde.offset-days` (offset semantics remain the A-3 residual, [SYNTHETIC-CONTRACT]).
- Process_Date = Collection_Date rolled per R-38 (`ProcessDateCalculator`, pure, no I/O): roll forward one day at a time while the date is a Sunday or a ZA public holiday. Saturdays are valid process dates (Sean-ruled 2026-07-12): the historical "next available weekday" phrasing is loose wording, not a Mon-Fri constraint.
- Fail-closed calendar (R-38): if `public_holiday` has zero synced rows for the collection year the job throws and FAILS; it never misdates. Holiday lookup horizon: 60 days past the collection date.
- One `cde_schedule` row per PASS verdict, upserted `ON CONFLICT (arrival_id, sequence) DO UPDATE` (rescheduling is idempotent, R-05). Zero PASS rows = valid no-op run (A-7).
- R-38 exclusion visibility: one WARN per non-PASS verdict (joined to `tx_entry` for the e2e), shape `excluded stage=CDE arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`.

## Outcome seam

`afterJob` on COMPLETED writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic); CDE produces no business-partial verdicts of its own. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33).

## Data

Reads (grants-based, R-04/R-06): `tx_header` (CRR), `validation_log` (CTV verdicts), `public_holiday` (HCS-owned, read-only). Writes: `cde_schedule` (CDE single writer, R-04; UNIQUE(arrival_id, sequence)).

Liquibase: per-service history tables `cde_databasechangelog` / `cde_databasechangeloglock` (shared DB). Changesets: 001 `cde_schedule`, 002 CDE_BATCH_ metadata DDL, plus a BOOTSTRAP-ORDER GUARD that creates `public_holiday` IF NOT EXISTS with the owner's exact column set: HCS owns the table (single writer, R-04) but CDE is launched per arrival and may run before HCS's first 6 h clock window on a fresh DB.

## Batch metadata

Spring Batch tables under the `CDE_BATCH_` prefix, `initialize-schema: never` (Liquibase owns the DDL). A-39a self-abandonment: an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CDE_BATCH_", 60)` before the job launches, abandoning STARTED executions older than 60 s so a killed pod cannot strand the relaunch.

## Local module dependencies

| Module | Version | Scope | Used for |
|---|---|---|---|
| `dcre-platform-persistence` | 0.1.0 | `implementation` | `BaseEntity` (version/created_at/updated_at on `CdeScheduleEntity`), `JdbcConfig` (Spring Data JDBC base config, imported by `CdeApplication`) |
| `dcre-platform-batch` | 0.1.0 | `implementation` | `ExitCodeMain` (R-34 exit-code wiring), `OutcomeFileWriter` (outcome seam), `StaleExecutionSweeper` (A-39a self-abandonment) |

Both resolve from Maven Local only (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo first, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch` (batch brings files and model transitively via its `api` chain); `dcre-platform-persistence` is standalone. Details in each module repo's README under "Publishing".

## Configuration

12FactorApp Alignment (https://12factor.net/): committed working dev defaults, env overrides; a clean clone runs with no `.env`.

| Env var | Default | Used for |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../infra/dcre-infra/exchange` | outcome seam directory |
| `DCRE_CDE_OFFSET_DAYS` | `0` (dev: due today; prod overrides) | Collection_Date offset (A-3 residual) |
| `DCRE_CDE_COUNTRY` | `ZA` | holiday-calendar country for the R-38 roll |
| `JOB_NAME` | `local-<executionId>` | outcome seam file name (set by AGT) |

`DCRE_AMOUNT_SCALE`, `DCRE_V1_ENABLED` and `DCRE_FLOW_DC` sit in the shared config block but are not consumed by CDE code.

## Build & test

Spring Boot 4.1.0, Java 25 toolchain; platform libs resolve from mavenLocal (see Local module dependencies). `./gradlew test` (Docker required):

- `CdeJobTest`: Testcontainers CockroachDB v26.2.3; 30 verdicts (15 PASS) -> exactly 15 schedule rows with the offset-and-rolled process date; rerun leaves 15 (idempotent reschedule, R-05).
- `CdeProcessDateJobTest`: rolls past a holiday-Saturday/Sunday/holiday-Monday chain, fails closed on an unsynced calendar year, one WARN per excluded non-PASS verdict.
- `ProcessDateCalculatorTest`: pure roll-rule cases (plain Saturday stands, Sunday rolls to Monday, plain weekday unchanged).

## Run

`./gradlew build && docker build -t dcre-cde:0.1.0 .` (eclipse-temurin:25-jre-alpine). In the cluster AGT launches it as a Job with `JOB_NAME` and the identifying `arrival.id=<uuid>` job parameter; locally: `java -jar build/libs/dcre-cde-0.1.0.jar arrival.id=<uuid>` against the dcre-infra compose stack. The JVM exit code carries the Batch outcome (R-34).

## Observability

No metrics wired yet. Operational signals: structured R-38 exclusion WARNs (`excluded stage=CDE ...`), the fail-closed calendar failure, the outcome seam file, and the R-34 exit code observed by AGT.
