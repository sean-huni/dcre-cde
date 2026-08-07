package za.co.fnb.dcre.cde;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import za.co.fnb.dcre.cde.service.ScheduleService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-38 (2nd amendment) integration: process_date = (collection date + processing
 * lead) rolled past Sundays + ZA public holidays; fail-closed on an unsynced
 * calendar year; WARN per excluded verdict. The header date IS the client-supplied
 * collection date.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "dcre.cde.processing-lead-days=2"})
@org.springframework.context.annotation.Import(CdeProcessDateJobTest.FixedClock.class)
class CdeProcessDateJobTest {

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
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
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
    void rollsProcessDatePastHolidaySaturdaySundayAndHolidayMonday() throws Exception {
        // Collection Sat 2026-02-14 + lead 2 = Mon 2026-02-16 (holiday) -> Tue 17.
        UUID arrival = seedArrival("20260214", 3, 0);
        seedHoliday("2026-02-14");
        seedHoliday("2026-02-16");

        JobExecution run = jobOperator.start(cdeJob, params(arrival));
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND process_date='2026-02-17'",
                Integer.class, arrival));
    }

    @Test
    void failsClosedWhenCalendarNotSyncedForCollectionYear() throws Exception {
        UUID arrival = seedArrival("20310301", 2, 0);

        JobExecution run = jobOperator.start(cdeJob, params(arrival));
        assertEquals(BatchStatus.FAILED, run.getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=?", Integer.class, arrival));
    }

    @Test
    void warnsOncePerExcludedNonPassVerdict() throws Exception {
        UUID arrival = seedArrival("20260218", 2, 3);
        seedHoliday("2026-02-16");

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(ScheduleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            JobExecution run = jobOperator.start(cdeJob, params(arrival));
            assertEquals(BatchStatus.COMPLETED, run.getStatus());
        } finally {
            serviceLogger.detachAppender(appender);
        }

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("excluded stage=CDE"))
                .toList();
        assertEquals(3, warns.size(), "one WARN per non-PASS verdict");
        for (ILoggingEvent warn : warns) {
            String msg = warn.getFormattedMessage();
            assertTrue(msg.contains("arrival=" + arrival), msg);
            assertTrue(msg.contains("reason=CTV_FAIL_ACCOUNT_NOT_FOUND"), msg);
            assertTrue(msg.contains("e2e=E2E-"), msg);
        }
    }

    @Test
    void schedulesAllPassRowsInOneStatement() throws Exception {
        // R-41 set-based write: Mon 2026-07-13 collection + lead 2 = Wed 2026-07-15.
        UUID arrival = seedArrival("20260713", 500, 3);
        seedHoliday("2026-12-25");

        JobExecution run = jobOperator.start(cdeJob, params(arrival));
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(500, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND process_date='2026-07-15'",
                Integer.class, arrival));
    }

    private UUID seedArrival(String collectionDate, int passCount, int failCount) {
        UUID arrival = UUID.randomUUID();
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_header (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID UNIQUE, business_date VARCHAR(8))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS validation_log (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, outcome VARCHAR(32), UNIQUE (arrival_id, sequence))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_entry (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, e2e VARCHAR(35), UNIQUE (arrival_id, sequence))");
        jdbc.update("UPSERT INTO tx_header (arrival_id, business_date) VALUES (?,?)", arrival, collectionDate);
        for (int i = 1; i <= passCount + failCount; i++) {
            jdbc.update("UPSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                    arrival, i, i <= passCount ? "PASS" : "FAIL_ACCOUNT_NOT_FOUND");
            jdbc.update("UPSERT INTO tx_entry (arrival_id, sequence, e2e) VALUES (?,?,?)",
                    arrival, i, "E2E-" + i);
        }
        return arrival;
    }

    private void seedHoliday(String date) {
        jdbc.update("INSERT INTO public_holiday (country, holiday_date) VALUES (?,?)"
                + " ON CONFLICT (country, holiday_date) DO NOTHING", "ZA", date);
    }

    private org.springframework.batch.core.job.parameters.JobParameters params(UUID arrival) {
        return new JobParametersBuilder().addString("arrival.id", arrival.toString(), true).toJobParameters();
    }
}
