package za.co.fnb.dcre.cde.data.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-41 set-based schedule write: one INSERT..SELECT covers every PASS row of the
 * arrival, keyed (arrival_id, sequence) so a rerun stays idempotent (R-05).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "dcre.cde.processing-lead-days=2"})
class CdeScheduleRepoIT {

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
    CdeScheduleRepo repo;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void createVerdictTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS validation_log (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, outcome VARCHAR(32), UNIQUE (arrival_id, sequence))");
    }

    @Test
    void upsertsOnlyPassRowsAndReturnsCount() {
        UUID arrival = seedVerdicts(3, 1);

        int written = repo.upsertAllPassRows(arrival, LocalDate.of(2026, 7, 15));

        assertEquals(3, written);
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND process_date='2026-07-15'",
                Integer.class, arrival));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND sequence=4",
                Integer.class, arrival), "the FAIL row must not be scheduled");
    }

    @Test
    void rerunUpdatesProcessDateWithoutDuplicatingRows() {
        UUID arrival = seedVerdicts(2, 0);

        assertEquals(2, repo.upsertAllPassRows(arrival, LocalDate.of(2026, 7, 15)));
        assertEquals(2, repo.upsertAllPassRows(arrival, LocalDate.of(2026, 7, 16)));

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=?", Integer.class, arrival),
                "reschedule is idempotent (R-05)");
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND process_date='2026-07-16'",
                Integer.class, arrival));
    }

    private UUID seedVerdicts(int passCount, int failCount) {
        UUID arrival = UUID.randomUUID();
        for (int i = 1; i <= passCount + failCount; i++) {
            jdbc.update("UPSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                    arrival, i, i <= passCount ? "PASS" : "FAIL_ACCOUNT_NOT_FOUND");
        }
        return arrival;
    }
}
