package za.co.fnb.dcre.cde.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * READ-ONLY reader over {@code hol_cde_view}, the contract {@code hcs} publishes in
 * {@code dcre_hcs}.
 *
 * <p>Replaces the Spring Data JDBC repository this used to be. That repository resolved against
 * CDE's PRIMARY datasource, which is exactly the coupling being removed: the calendar lived in
 * {@code dcre_col} and every collections service could read, and create, another context's table.
 * A {@code Repository} interface cannot be pointed at a second datasource, so the read moves to a
 * plain DAO built on the dedicated connection (the same shape {@code ctv} already uses for its
 * cross-context mandates read).
 *
 * <p>CDE reads the VIEW and never the table, so {@code hcs} keeps the freedom to reshape
 * {@code public_holiday} without a coordinated release here.
 *
 * <p><b>Fail-closed is a property of this class, not just of its caller (R-38).</b> Both methods
 * let a {@code DataAccessException} propagate rather than catching it and returning an empty
 * result. That distinction is the whole guarantee: an unreachable {@code dcre_hcs} that degraded
 * to "no holidays found" would be indistinguishable from a genuinely holiday-free window, and CDE
 * would roll process dates onto public holidays while every log line looked healthy. An empty
 * result and an unavailable source must never be the same value.
 */
public class HolidayCalendarDao {

    private final JdbcTemplate jdbc;

    public HolidayCalendarDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fail-closed guard input: synced holiday count for the collection year. */
    public long countForYear(final String country, final LocalDate yearStart,
                             final LocalDate nextYearStart) {
        final Long count = jdbc.queryForObject("""
                SELECT count(*) FROM hol_cde_view
                WHERE country = ? AND holiday_date >= ? AND holiday_date < ?""",
                Long.class, country, yearStart, nextYearStart);
        return count == null ? 0L : count;
    }

    /** The holiday dates in the roll horizon, for the R-38 forward roll. */
    public List<LocalDate> holidaysBetween(final String country, final LocalDate from,
                                           final LocalDate to) {
        return jdbc.query("""
                SELECT holiday_date FROM hol_cde_view
                WHERE country = ? AND holiday_date BETWEEN ? AND ?""",
                (rs, rowNum) -> rs.getObject("holiday_date", LocalDate.class), country, from, to);
    }
}
