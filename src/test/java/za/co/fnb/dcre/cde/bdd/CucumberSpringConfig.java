package za.co.fnb.dcre.cde.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import za.co.fnb.dcre.cde.HolidayCalendarFixture;
import org.testcontainers.utility.DockerImageName;

/**
 * One Spring context for all @cde scenarios, bootstrapped exactly like the
 * existing JUnit tests (CdeJobTest): real CockroachDB via Testcontainers,
 * Liquibase-managed schema, scheduler disabled, processing lead 2 so the BDD
 * scenarios exercise the R-38 2nd-amendment lead-then-roll placeholder.
 */
@org.springframework.context.annotation.Import(CucumberSpringConfig.FixedClock.class)
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "dcre.cde.processing-lead-days=2"})
public class CucumberSpringConfig {

    /**
     * A-77 (SCRUM-107): scheduling is clock-aware, so these date-driven scenarios must steer the
     * calendar rather than inherit the wall clock. Fixed BEFORE every fixture date in the feature
     * files (earliest 2026-02-20), so collectionDate >= today throughout and the scenarios assert
     * the pure R-38 rule exactly, unchanged by the back-date guard.
     */
    @org.springframework.boot.test.context.TestConfiguration
    public static class FixedClock {
        @org.springframework.context.annotation.Bean
        public java.time.Clock clock() {
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

}
