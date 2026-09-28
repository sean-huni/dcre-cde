# dcre-cde

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Collection Day Estimator: mints `cde_schedule` rows (process dates) for CTV-passing transactions of a DC arrival; schedules only, never emits (R-37).

## What it does

| | |
|---|---|
| Stage | `CDE` |
| Family / leg | Collections (DC), REQ |
| Trigger | arrival-launched: AGT launches one Kubernetes Job per arrival when CTV completes |
| Upstream | `CTV` (the DAG fork is `CRR -> CTV -> {CDE, CIR}`) |
| Downstream | none in the DAG (CDE is a terminal of the DC request DAG). `CRW` is clock-launched by AGT, not a DAG successor, and emits what CDE scheduled; AGT keeps the arrival open until a `crw_emission` is visible |
| Diagram sheet | `dcre-collections-req` |

DAG position per AGT `RouteDags.DC` on origin/dev (checked 2026-09-28). CDE exists on the collections family only: the payments request DAG (`PRR -> PTV -> PAI -> {PRW, PIR}`) has no collection-day estimator because payments are processed immediately.

For each PASS verdict of the arrival, Process_Date = roll(client-supplied collection date + processing lead) per R-38 (2nd amendment): the lead is applied first, then the candidate rolls forward one day at a time while it is a Sunday or a ZA public holiday (Saturdays are valid). The calendar is fail-closed: an unreachable calendar database, or zero holiday rows for the collection year, fails the job; CDE never misdates. It is a DB-only stage with no file I/O (R-30) apart from the outcome seam file. Stack: Java 25, Spring Boot 4.1.0 (Spring Batch, Spring Data JDBC), Liquibase, CockroachDB over the PostgreSQL driver.

## Architecture and principles

- **SOLID, 3-tier**: `cdeJob` is a single tasklet step `scheduleStep`. `ScheduleTasklet` is a thin entry adapter (extracts the identifying `arrival.id` job parameter, calls one service method); business logic lives in `ScheduleService`; `ProcessDateCalculator` is a pure function (no I/O); persistence goes only through `data/repo`. Layer-first packages: `config/`, `service/`, `data/model/`, `data/repo/`.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working dev defaults (a clean clone runs with no `.env`), stateless one-shot process, CockroachDB as an attached resource.
- **Idempotent restart semantics**: the schedule write is one set-based `INSERT..SELECT` over all PASS rows (R-41), upserted `ON CONFLICT (arrival_id, sequence) DO UPDATE` (full business identity), so rescheduling and same-identity relaunch never duplicate rows (R-05). Zero PASS rows is a valid no-op run (A-7). Spring Batch metadata lives under the `CDE_BATCH_` prefix; an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CDE_BATCH_", 60)` so a killed pod cannot strand the relaunch (A-39a). The step carries platform-batch's `CrdbRetryExceptionHandler` (CRDB 40001 serialization aborts retry, never skip). Rerun invariants are pinned by `CdeJobTest` and `CdeScheduleRepoIT`.
- **Fail-closed calendar**: `HolidayCalendarDao` reads over a dedicated, non-pooled, read-only connection; an unreachable database raises rather than returning an empty calendar (`HolidayCalendarFailClosedTest`). `HolidaysDatasourceConfig` refuses to start in a pod (`KUBERNETES_SERVICE_HOST` set) while `DCRE_CDE_HOLIDAYS_DB_URL` is still the localhost dev default.
- **Outcome seam**: `afterJob` on COMPLETED writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic). Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s Job condition are the witnesses; AGT treats absence as never-success (R-33).
- **Exclusion visibility (R-38)**: one WARN per non-PASS verdict, shape `excluded stage=CDE arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`.

### Data

| Datasource | Database (dev default) | Env vars | Access |
|---|---|---|---|
| primary | `dcre_col` | `DCRE_DB_URL`, `DCRE_DB_USER`, `DCRE_DB_PASSWORD` | read/write |
| holidays | `dcre_hcs` | `DCRE_CDE_HOLIDAYS_DB_URL`, `DCRE_CDE_HOLIDAYS_DB_USER`, `DCRE_CDE_HOLIDAYS_DB_PASSWORD` | read-only |
| heartbeat (platform-batch) | `agt_ops` | `DCRE_AGTOPS_DB_URL`, `DCRE_AGTOPS_DB_USER`, `DCRE_AGTOPS_DB_PASSWORD` | platform-batch heartbeat writer |

- Reads (`dcre_col`): `tx_header` (CRR-owned; its `business_date` column carries the client-supplied collection date), `validation_log` (CTV verdicts), `tx_entry` (for the `e2e` in exclusion WARNs).
- Reads (`dcre_hcs`): the HCS-published `hol_cde_view` only, horizon 60 days past the collection date. CDE never reads or creates `public_holiday`.
- Writes (`dcre_col`): `cde_schedule` (CDE single writer, `UNIQUE(arrival_id, sequence)`), plus its `CDE_BATCH_*` tables.
- Liquibase history in `cde_databasechangelog` / `cde_databasechangeloglock`. Changesets under `db/changelog/2026/08/`: `001-batch-metadata.xml` (`CDE_BATCH_*`), `002-cde-schedule.xml`.

## Prerequisites

- Java 25 (`.sdkmanrc`: `java=25-tem`; `build.gradle` sets source/target compatibility 25)
- Gradle 9.5.1 via the wrapper
- Docker (Testcontainers CockroachDB and the image build)
- Platform libraries in Maven Local (`repositories { mavenCentral(); mavenLocal() }`):

| Module | Version | Used for |
|---|---|---|
| `za.co.fnb.dcre:platform-persistence` | 0.1.0 | `JdbcConfig`, `BaseEntity` |
| `za.co.fnb.dcre:platform-batch` | 0.1.0 | `ExitCodeMain`, `BatchJdbcConfig`, `HeartbeatDatasourceConfig`, `HeartbeatWriter`, `OutcomeSeamListener`, `StaleExecutionSweeper`, `CrdbRetryExceptionHandler` |

Publish chain: `./gradlew publishToMavenLocal` in `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone.

## Quickstart

Clean clone, no `.env` needed (committed defaults target CockroachDB at `localhost:26257`, e.g. the kind CRDB port-forwarded via dcre-infra `scripts/crdb-forward.sh`):

```bash
./gradlew test                                   # full suite, Docker required
./gradlew bootJar                                # build/libs/cde-2.0.jar
java -jar build/libs/cde-2.0.jar 'arrival.id=<uuid>'
```

`arrival.id` (UUID string) is the identifying job parameter; the count of scheduled transactions lands in the execution context as `scheduled`. The JVM exit code carries the Batch outcome (R-34).

## Configuration

From `src/main/resources/application.yml` (the only profile). Precedence: yml default < environment variable.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | primary collections database |
| `DCRE_DB_USER` | `root` | primary user |
| `DCRE_DB_PASSWORD` | (empty) | primary password |
| `DCRE_CDE_HOLIDAYS_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_hcs?sslmode=disable` | read-only calendar connection; fatal at startup if left at this default inside a pod |
| `DCRE_CDE_HOLIDAYS_DB_USER` | `root` | calendar role; needs only `SELECT` on `hol_cde_view` |
| `DCRE_CDE_HOLIDAYS_DB_PASSWORD` | (empty) | calendar password |
| `DCRE_CDE_PROCESSING_LEAD_DAYS` | `2` (must be >= 1) | lead applied before the R-38 roll (SYNTHETIC-CONTRACT placeholder for the unrecovered collection-cycle rule, A-3) |
| `DCRE_CDE_COUNTRY` | `ZA` | holiday-calendar country |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | outcome seam root (relative dev default; AGT sets `/exchange`) |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | heartbeat user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | heartbeat password |
| `DCRE_AMOUNT_SCALE` | `2` | shared fleet key; not read by CDE code |
| `DCRE_V1_ENABLED` | `false` | shared fleet key; not read by CDE code |
| `JOB_NAME` | `local-cde-<executionId>` | outcome seam file name (set by AGT) |

This is the documented set, not a closed total: Spring relaxed binding lets any Spring or `dcre.*` property be overridden by its derived environment variable name.

## Testing

```bash
./gradlew test                                                                  # Docker required
./gradlew test --tests 'za.co.fnb.dcre.cde.service.ProcessDateCalculatorTest'   # pure unit slice
```

Testcontainers image: `cockroachdb/cockroach:v26.2.3`.

- `ProcessDateCalculatorTest` (unit): lead-then-roll cases, Sunday/holiday candidates roll, plain Saturday stands, fail-closed when the process date would not follow the collection date.
- `HolidayCalendarFailClosedTest` (unit, no container): an unreachable calendar database raises; it never reads as an empty calendar.
- `CdeJobTest` (Testcontainers): 30 verdicts (15 PASS) give exactly 15 schedule rows; a rerun leaves 15 (R-05).
- `CdeProcessDateJobTest` (Testcontainers): rolls past a holiday Saturday, Sunday and holiday Monday; fails closed on an unsynced calendar year; one WARN per excluded verdict; all PASS rows in one statement (R-41).
- `CdeScheduleRepoIT` (Testcontainers): upsert writes only PASS rows, rerun updates without duplicating, zero PASS rows is a no-op.
- `CdeJobConfigRetryTest`: `scheduleStep` retries commit-time CRDB 40001 aborts.
- `CucumberSuiteTest` (`features/cde-process-date.feature`, tag `@cde`): BDD scenarios for unchanged vs rolled dates, fail-closed calendar, idempotent reschedule, no-op runs and exclusion WARNs.

## Local cluster deployment

Cluster and database come from dcre-infra (`scripts/kind-up.sh` creates the kind cluster `dcre-dev`):

```bash
VERSION=<fleet release tag>
./gradlew bootJar
docker build -t dcre-cde:$VERSION .              # eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-cde:$VERSION
```

AGT reads the image from `AGT_CDE_IMAGE` (empty by default, which leaves the stage launch-disabled); dcre-infra `scripts/switch-version.sh <version>` sets `AGT_CDE_IMAGE=dcre-cde:<version>` on the AGT deployment (checked 2026-09-28). Per arrival AGT creates a Job in the collections flow namespace (AGT `AGT_NAMESPACE_COL`, default `dcre-col`) with program arg `arrival.id=<uuid>` and env `JOB_NAME`, `DCRE_DB_URL` (AGT `service-db-url`, `dcre_col`), `DCRE_CDE_HOLIDAYS_DB_URL` (AGT `hcs-service-db-url`, `dcre_hcs`), `DCRE_EXCHANGE_ROOT=/exchange`, `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (AGT `JobLauncher` on origin/dev, checked 2026-09-28). Releases are digits-only 3-component SemVer git tags, uniform across the fleet.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
