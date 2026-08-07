package za.co.fnb.dcre.cde.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.cde.service.ScheduleTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;

import javax.sql.DataSource;

@Configuration
public class CdeJobConfig {

    /**
     * CRDB 40001 retry for the WRITE step (set-based schedule upsert), the
     * observed RETRY_SERIALIZABLE death under 3-way copybook concurrency
     * (exit 5, TECH_FAILED). Commit-time aborts are covered because the
     * tasklet commit runs inside the step's repeat loop; the re-run redoes
     * the whole tasklet in a fresh transaction (shared platform handler,
     * proven live in CTV). Retry, never skip.
     */
    private final CrdbRetryExceptionHandler crdbRetry = new CrdbRetryExceptionHandler("CDE");

    /** A-77 (SCRUM-107): the system clock as a BEAN, so business logic never calls now()
     *  directly and date-driven tests can steer the calendar with Clock.fixed. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(java.time.Clock.class)
    java.time.Clock systemClock() {
        return java.time.Clock.systemDefaultZone();
    }

    @Bean
    public Step scheduleStep(JobRepository repo, PlatformTransactionManager tx, ScheduleTasklet tasklet) {
        return new StepBuilder("scheduleStep", repo).tasklet(tasklet, tx).exceptionHandler(crdbRetry).build();
    }

    @Bean
    public Job cdeJob(JobRepository repo, Step scheduleStep, HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") String exchangeRoot) {
        return new JobBuilder("cdeJob", repo)
                .listener(new OutcomeSeamListener("cde", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(scheduleStep)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "CDE_BATCH_", 60);
    }
}
