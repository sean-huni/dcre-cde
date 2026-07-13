package za.co.fnb.dcre.cde.bdd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.cde.service.ScheduleService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for the @cde R-38 scheduling scenarios (seeding mirrors CdeProcessDateJobTest). */
public class CdeScheduleSteps {

    @Autowired
    Job cdeJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${dcre.cde.processing-lead-days}")
    int processingLeadDays;

    private UUID arrival;
    private JobExecution lastExecution;
    private int attempt;
    private ListAppender<ILoggingEvent> warnAppender;
    private Logger serviceLogger;

    @Before
    public void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_header (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID UNIQUE, business_date VARCHAR(8))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS validation_log (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, outcome VARCHAR(32), UNIQUE (arrival_id, sequence))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_entry (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, e2e VARCHAR(35), UNIQUE (arrival_id, sequence))");
        serviceLogger = (Logger) LoggerFactory.getLogger(ScheduleService.class);
        warnAppender = new ListAppender<>();
        warnAppender.start();
        serviceLogger.addAppender(warnAppender);
    }

    @After
    public void tearDown() {
        serviceLogger.detachAppender(warnAppender);
    }

    @Given("the processing lead is {int} days")
    public void theProcessingLeadIs(int days) {
        assertEquals(days, processingLeadDays,
                "scenario lead must match the context's dcre.cde.processing-lead-days");
    }

    @Given("the ZA public holiday calendar for {int} is synced")
    public void holidayCalendarSynced(int year) {
        seedHoliday(year + "-12-25");
    }

    @Given("no ZA public holidays are synced for {int}")
    public void noHolidaysSyncedFor(int year) {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_holiday"
                        + " WHERE country='ZA' AND holiday_date >= ?::date AND holiday_date < ?::date",
                Integer.class, year + "-01-01", (year + 1) + "-01-01"));
    }

    @Given("{} is a ZA public holiday")
    public void isPublicHoliday(String date) {
        seedHoliday(date);
    }

    @Given("^a DC arrival with (\\d+) PASS transactions collected on (?:[A-Za-z]+ )?(\\d{4}-\\d{2}-\\d{2})$")
    public void arrivalWithPassTransactions(int passCount, String collectionDate) {
        seedArrival(collectionDate, passCount, 0);
    }

    @Given("^a DC arrival with (\\d+) PASS transactions and (\\d+) failed transactions collected on"
            + " (?:[A-Za-z]+ )?(\\d{4}-\\d{2}-\\d{2})$")
    public void arrivalWithPassAndFailedTransactions(int passCount, int failCount, String collectionDate) {
        seedArrival(collectionDate, passCount, failCount);
    }

    @When("the CDE job runs for the arrival")
    public void cdeJobRuns() throws Exception {
        startJob();
    }

    @When("the CDE job runs again for the arrival")
    public void cdeJobRunsAgain() throws Exception {
        startJob();
    }

    @Then("the CDE job completes")
    public void jobCompletes() {
        assertEquals(BatchStatus.COMPLETED, lastExecution.getStatus());
    }

    @Then("the CDE job fails")
    public void jobFails() {
        assertEquals(BatchStatus.FAILED, lastExecution.getStatus());
    }

    @Then("every scheduled transaction has process date {}")
    public void everyScheduledTransactionHasProcessDate(String processDate) {
        int total = scheduledCount();
        assertTrue(total > 0, "expected at least one scheduled transaction");
        assertEquals(total, jdbc.queryForObject(
                "SELECT count(*) FROM cde_schedule WHERE arrival_id=? AND process_date=?::date",
                Integer.class, arrival, processDate));
    }

    @Then("no transactions are scheduled for the arrival")
    public void noTransactionsScheduled() {
        assertEquals(0, scheduledCount());
    }

    @Then("exactly {int} transactions are scheduled for the arrival")
    public void exactlyTransactionsScheduled(int expected) {
        assertEquals(expected, scheduledCount());
    }

    @Then("{int} exclusion warnings are logged for stage CDE with reason {word}")
    public void exclusionWarningsLogged(int expected, String reason) {
        List<String> warns = warnAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("excluded stage=CDE") && m.contains("arrival=" + arrival))
                .toList();
        assertEquals(expected, warns.size(), "one WARN per excluded non-PASS verdict");
        for (String warn : warns) {
            assertTrue(warn.contains("reason=" + reason), warn);
            assertTrue(warn.contains("e2e=E2E-"), warn);
        }
    }

    private void startJob() throws Exception {
        attempt++;
        lastExecution = jobOperator.start(cdeJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("attempt", String.valueOf(attempt), true)
                .toJobParameters());
    }

    private int scheduledCount() {
        return jdbc.queryForObject("SELECT count(*) FROM cde_schedule WHERE arrival_id=?",
                Integer.class, arrival);
    }

    /** The header date IS the client-supplied collection date (R-38 2nd amendment). */
    private void seedArrival(String collectionDate, int passCount, int failCount) {
        arrival = UUID.randomUUID();
        String headerDate = LocalDate.parse(collectionDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        jdbc.update("UPSERT INTO tx_header (arrival_id, business_date) VALUES (?,?)", arrival, headerDate);
        for (int i = 1; i <= passCount + failCount; i++) {
            jdbc.update("UPSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                    arrival, i, i <= passCount ? "PASS" : "FAIL_ACCOUNT_NOT_FOUND");
            jdbc.update("UPSERT INTO tx_entry (arrival_id, sequence, e2e) VALUES (?,?,?)",
                    arrival, i, "E2E-" + i);
        }
    }

    private void seedHoliday(String date) {
        jdbc.update("INSERT INTO public_holiday (country, holiday_date) VALUES (?,?)"
                + " ON CONFLICT (country, holiday_date) DO NOTHING", "ZA", date);
    }
}
