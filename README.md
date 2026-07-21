# dcre-cde

Collection Day Estimator: mints `cde_schedule` rows (process dates) for CTV-passing transactions of a DC arrival; schedules only, never emits (R-37).

## What it does

Per-file DAG stage downstream of CTV in the fork `CTV -> { CDE || CIR }`; CDE's own downstream is CRW, which emits what CDE scheduled. For each PASS verdict of the arrival, Process_Date = roll(client-supplied Collection_Date + processing lead) per R-38 (2nd amendment): the lead is applied first, then the candidate rolls forward one day at a time while it is a Sunday or a ZA public holiday (Saturdays are valid). The calendar is fail-closed: zero synced `public_holiday` rows for the collection year fails the job; CDE never misdates. AGT launches it as a short-lived Kubernetes Job per arrival; it is a DB-only stage with no file I/O (R-30) apart from the outcome seam file. Stack: Java 25, Spring Boot 4.1.0 (Spring Batch, Spring Data JDBC), Liquibase, CockroachDB.

## Architecture and principles

- **SOLID, 3-tier**: `cdeJob` is a single tasklet step `scheduleStep`. `ScheduleTasklet` is a thin entry adapter (extracts the identifying `arrival.id` job parameter, calls one service method); business logic lives in `ScheduleService`; `ProcessDateCalculator` is a pure function (no I/O); persistence goes only through `data/repo` interfaces. Layer-first packages: `config/`, `service/`, `data/model/`, `data/repo/`.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working dev defaults (a clean clone runs with no `.env`), stateless one-shot process, the shared CockroachDB as an attached resource, dev/prod parity via the same image locally and in kind.
- **Idempotent restart semantics**: the schedule write is one set-based `INSERT..SELECT` over all PASS rows (R-41), upserted `ON CONFLICT (arrival_id, sequence) DO UPDATE` (full business identity), so rescheduling and same-identity relaunch are no-ops that never duplicate rows (R-05). Zero PASS rows is a valid no-op run (A-7). Spring Batch metadata lives under the `CDE_BATCH_` prefix; an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "CDE_BATCH_", 60)` so a killed pod cannot strand the relaunch (A-39a). The write step carries the shared `CrdbRetryExceptionHandler` (CRDB 40001 serialization aborts retry, never skip). Kill-resume is chaos-validated fleet-wide (SIGKILL at every stage, same-identity relaunch, zero duplicates); in this repo the rerun invariants are pinned by `CdeJobTest` and `CdeScheduleRepoIT`.
- **Outcome seam**: `afterJob` on COMPLETED writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic). Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s Job condition are the witnesses; AGT treats absence as never-success (R-33).
- **Exclusion visibility (R-38)**: one WARN per non-PASS verdict, shape `excluded stage=CDE arrival=<id> seq=<n> e2e=<e2e> reason=CTV_<OUTCOME>`.

### Data

Reads (grants-based, R-04/R-06): `tx_header` (CRR-owned; the header's `business_date` column carries the client-supplied collection date), `validation_log` (CTV verdicts), `public_holiday` (HCS-owned, read-only; lookup horizon 60 days past the collection date). Writes: `cde_schedule` (CDE single writer, UNIQUE(arrival_id, sequence)).

Liquibase (per-service history tables `cde_databasechangelog` / `cde_databasechangeloglock` on the shared DB): 001 `cde_schedule`, 002 `CDE_BATCH_` metadata DDL, plus a BOOTSTRAP-ORDER GUARD that creates `public_holiday` IF NOT EXISTS with the owner's exact column set: HCS owns the table, but CDE runs per arrival and may start before HCS's first clock window on a fresh DB.

## Prerequisites

- JDK 25 (Gradle toolchain; wrapper is Gradle 9.5.1)
- Docker (Testcontainers CockroachDB and the image build)
- Platform libs in Maven Local (no remote repository):

| Module | Version | Used for |
|---|---|---|
| `za.co.fnb.dcre:platform-persistence` | 0.1.0 | `BaseEntity`, `JdbcConfig` (imported by `CdeApplication`) |
| `za.co.fnb.dcre:platform-batch` | 0.1.0 | `ExitCodeMain`, `OutcomeFileWriter`, `StaleExecutionSweeper`, `CrdbRetryExceptionHandler` |

Publish chain: `./gradlew publishToMavenLocal` in `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch` (batch brings files and model transitively); `dcre-platform-persistence` is standalone.

## Quickstart

Clean clone, no `.env` needed (committed defaults target the local CockroachDB at `localhost:26257`, e.g. the kind CRDB port-forwarded via dcre-infra `scripts/crdb-forward.sh`):

```bash
./gradlew test                                   # full suite, Docker required
./gradlew bootJar                                # build/libs/cde-2.0.jar
java -jar build/libs/cde-2.0.jar 'arrival.id=<uuid>'
```

`arrival.id` (UUID string) is the identifying job parameter; the count of scheduled transactions lands in the execution context as `scheduled`. The JVM exit code carries the Batch outcome (R-34).

## Configuration

Precedence: yml default < environment variable.

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | shared CockroachDB |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | outcome seam directory (resolves to dcre-infra's `exchange/` in the canonical fleet checkout; deployed contexts set an absolute path) |
| `DCRE_CDE_PROCESSING_LEAD_DAYS` | `2` (must be >= 1) | processing lead applied before the R-38 roll (SYNTHETIC-CONTRACT placeholder for the unrecovered collection-cycle rule, A-3) |
| `DCRE_CDE_COUNTRY` | `ZA` | holiday-calendar country for the R-38 roll |
| `JOB_NAME` | `local-<executionId>` | outcome seam file name (set by AGT) |

`DCRE_AMOUNT_SCALE` (`2`), `DCRE_V1_ENABLED` (`false`) and `DCRE_FLOW_DC` (`true`) sit in the shared fleet config block but are not read by CDE code.

## Testing

```bash
./gradlew test                                                              # Docker required
./gradlew test --tests 'za.co.fnb.dcre.cde.service.ProcessDateCalculatorTest'   # pure unit slice
```

- `ProcessDateCalculatorTest`: lead-then-roll cases (ratified 2026-07-13 -> 2026-07-15 example, Sunday/holiday candidates roll, plain Saturday stands, fail-closed when the process date would not follow the collection date) plus the pure roll-only cases.
- `CdeJobTest` (Testcontainers `cockroachdb/cockroach:v26.2.3`): 30 verdicts (15 PASS) -> exactly 15 schedule rows with the lead-and-rolled process date; rerun leaves 15 (idempotent reschedule, R-05).
- `CdeProcessDateJobTest`: rolls past a holiday Saturday + Sunday + holiday Monday, fails closed on an unsynced calendar year, one WARN per excluded non-PASS verdict, all PASS rows written in one statement (R-41).
- `CdeScheduleRepoIT`: upsert writes only PASS rows and returns the count, rerun updates the process date without duplicating rows, zero PASS rows is a valid no-op.
- `CdeJobConfigRetryTest`: `scheduleStep` retries commit-time CRDB 40001 serialization aborts.
- `CucumberSuiteTest` (`features/cde-process-date.feature`, tag `@cde`): BDD scenarios for unchanged vs rolled process dates, fail-closed calendar, idempotent reschedule, no-op runs and exclusion WARNs.

## Local cluster deployment

Cluster and DB come from dcre-infra (`scripts/kind-up.sh` creates the kind cluster `dcre-dev`):

```bash
VERSION=2.1.0   # any fleet release tag accepted by dcre-infra scripts/switch-version.sh
./gradlew bootJar
docker build -t dcre-cde:$VERSION .              # eclipse-temurin:25-jre-alpine
kind load docker-image --name dcre-dev dcre-cde:$VERSION
```

Then from dcre-infra, `scripts/switch-version.sh $VERSION` points the fleet at the tag (it sets `AGT_CDE_IMAGE=dcre-cde:$VERSION` on the AGT deployment). AGT mints one Kubernetes Job per arrival from that image, passing `JOB_NAME` and the identifying `arrival.id=<uuid>` job parameter. Releases are digits-only 3-component SemVer git tags, uniform across the fleet (this repo: `1.0.0` through `2.1.1`).

## Related repositories

- Orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt)
- Upstream stage: [dcre-ctv](https://github.com/sean-huni/dcre-ctv) (verdicts CDE consumes); [dcre-crr](https://github.com/sean-huni/dcre-crr) (headers)
- Downstream stage: [dcre-crw](https://github.com/sean-huni/dcre-crw) (emits what CDE scheduled)
- Calendar owner: [dcre-hcs](https://github.com/sean-huni/dcre-hcs) (`public_holiday` sync)
- Other stages: [dcre-cir](https://github.com/sean-huni/dcre-cir), [dcre-ixr](https://github.com/sean-huni/dcre-ixr), [dcre-sxr](https://github.com/sean-huni/dcre-sxr), [dcre-pxr](https://github.com/sean-huni/dcre-pxr), [dcre-prg](https://github.com/sean-huni/dcre-prg), [dcre-ais](https://github.com/sean-huni/dcre-ais)
- Platform libs: [dcre-platform-model](https://github.com/sean-huni/dcre-platform-model), [dcre-platform-files](https://github.com/sean-huni/dcre-platform-files), [dcre-platform-batch](https://github.com/sean-huni/dcre-platform-batch), [dcre-platform-persistence](https://github.com/sean-huni/dcre-platform-persistence)
- Environment and tooling: [dcre-infra](https://github.com/sean-huni/dcre-infra), [dcre-fixture-toolkit](https://github.com/sean-huni/dcre-fixture-toolkit), [dcre-design-register](https://github.com/sean-huni/dcre-design-register), [dcre-rpt](https://github.com/sean-huni/dcre-rpt)
