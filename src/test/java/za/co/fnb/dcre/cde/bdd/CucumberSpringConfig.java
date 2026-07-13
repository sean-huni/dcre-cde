package za.co.fnb.dcre.cde.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Spring context for all @cde scenarios, bootstrapped exactly like the
 * existing JUnit tests (CdeJobTest): real CockroachDB via Testcontainers,
 * Liquibase-managed schema, scheduler disabled, processing lead 2 so the BDD
 * scenarios exercise the R-38 2nd-amendment lead-then-roll placeholder.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "dcre.cde.processing-lead-days=2"})
public class CucumberSpringConfig {

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
}
