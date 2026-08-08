package za.co.fnb.dcre.cde;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "dcre.cde.processing-lead-days=2"})
@org.springframework.context.annotation.Import(CdeJobTest.FixedClock.class)
class CdeJobTest {

    /**
     * A-77 (SCRUM-107): these are DATE-DRIVEN tests with back-dated fixtures. Scheduling is now
     * clock-aware, so the calendar must be steered rather than inherited from the wall clock,
     * or every assertion here silently re-dates itself as the fixtures age. Fixed BEFORE every fixture date, so
     * collectionDate >= today throughout and the pre-A-77 behaviour is asserted exactly.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClock {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    java.time.ZoneOffset.UTC);
        }
    }


    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        HolidayCalendarFixture.create(CRDB);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("dcre.cde.holidays-db-url", () -> HolidayCalendarFixture.url(CRDB));
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    Job cdeJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schedulesPassRowsIdempotently() throws Exception {
        UUID arrival = UUID.randomUUID();
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_header (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID UNIQUE, business_date VARCHAR(8))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS validation_log (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, outcome VARCHAR(32), UNIQUE (arrival_id, sequence))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_entry (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, e2e VARCHAR(35), UNIQUE (arrival_id, sequence))");
        // Fail-closed guard (R-38): the collection year must have at least one synced ZA holiday.
        HolidayCalendarFixture.jdbc(CRDB).update("INSERT INTO public_holiday (country, holiday_date) VALUES (?,?)"
                + " ON CONFLICT (country, holiday_date) DO NOTHING", "ZA", "2026-12-25");
        jdbc.update("UPSERT INTO tx_header (arrival_id, business_date) VALUES (?,?)", arrival, "20260711");
        for (int i = 1; i <= 30; i++) {
            jdbc.update("UPSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                    arrival, i, i <= 15 ? "PASS" : "FAIL_ACCOUNT_NOT_FOUND");
        }

        JobExecution run = jobOperator.start(cdeJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        // SCRUM-58: without JOB_NAME env the seam name is the self-describing local fallback.
        assertTrue(Files.exists(Path.of("build/test-exchange/outcomes/local-cde-" + run.getId())),
                "outcome seam file local-cde-<executionId> (SCRUM-58 self-describing local seam name)");
        assertEquals(15, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=?", Integer.class, arrival));
        assertEquals("2026-07-13", jdbc.queryForObject(
                "SELECT process_date FROM cde_schedule WHERE arrival_id=? AND sequence=1", String.class, arrival));

        JobExecution rerun = jobOperator.start(cdeJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("attempt", "2", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertEquals(15, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=?", Integer.class, arrival),
                "reschedule is idempotent (R-05)");
    }
}
