package za.co.fnb.dcre.cde;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import za.co.fnb.dcre.cde.data.repo.HolidayCalendarDao;
import za.co.fnb.dcre.cde.service.ProcessDateCalculator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-38 must survive the move to another context's database.
 *
 * <p>The risk the move introduces is specific and quiet: the calendar is now behind a network hop
 * that can fail, and the cheapest wrong implementation catches that failure and returns an empty
 * calendar. CDE would then roll nothing, schedule collections onto public holidays, and report
 * success. "No holidays found" and "could not ask" must never be the same value, so the unreachable
 * case is asserted here rather than assumed from the absence of a catch block.
 *
 * <p>Each test asserts exactly one control. A single test covering "unreachable or unsynced" would
 * keep passing if either arm were lost.
 */
class HolidayCalendarFailClosedTest {

    private static final LocalDate YEAR_START = LocalDate.of(2026, 1, 1);

    /**
     * The datasource resolves and the driver loads, but nothing is listening. This is the shape of
     * a real outage: dcre_hcs down, wrong host, network partition, revoked grant.
     */
    private static HolidayCalendarDao unreachable() {
        // Driver loaded BY NAME: the PostgreSQL driver is a runtimeOnly dependency, so naming
        // the class in source would not compile.
        final var ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        // Port 1 is reserved and never listening, so this fails to CONNECT rather than to
        // authenticate, which is the case a silent empty result would hide.
        ds.setUrl("jdbc:postgresql://localhost:1/dcre_hcs?sslmode=disable&connectTimeout=2");
        ds.setUsername("root");
        return new HolidayCalendarDao(new JdbcTemplate(ds));
    }

    @Test
    void unreachableCalendarThrowsRatherThanReportingZeroHolidaysForTheYear() {
        final var thrown = assertThrows(DataAccessException.class,
                () -> unreachable().countForYear("ZA", YEAR_START, YEAR_START.plusYears(1)));
        // Assert the SPECIFIC failure. A bare "it threw" would also pass on a missing driver
        // class, which is a build problem rather than the fail-closed property under test.
        assertTrue(thrown.getMessage().toLowerCase().contains("connect"), thrown.getMessage());
    }

    @Test
    void unreachableCalendarThrowsRatherThanReturningAnEmptyHolidaySet() {
        assertThrows(DataAccessException.class,
                () -> unreachable().holidaysBetween("ZA", YEAR_START, YEAR_START.plusDays(60)));
    }

    /**
     * The roll semantics the move must not disturb, pinned alongside the fail-closed arms because
     * they are the reason the calendar is consulted at all.
     */
    @Test
    void saturdaysStayValidWhileSundaysAndHolidaysRollForward() {
        final LocalDate saturday = LocalDate.of(2026, 8, 8);
        assertEquals(DayOfWeek.SATURDAY, saturday.getDayOfWeek());
        assertEquals(saturday, ProcessDateCalculator.roll(saturday, Set.of()),
                "Saturdays are valid process dates (Sean-ruled 2026-07-12)");

        final LocalDate sunday = LocalDate.of(2026, 8, 9);
        assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 8, 10), ProcessDateCalculator.roll(sunday, Set.of()));

        final LocalDate holiday = LocalDate.of(2026, 8, 10);
        assertEquals(LocalDate.of(2026, 8, 11), ProcessDateCalculator.roll(holiday, Set.of(holiday)));
    }
}
