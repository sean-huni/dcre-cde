package za.co.fnb.dcre.cde;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Stands up the HCS-owned side of the boundary on the test container.
 *
 * <p>CDE no longer creates {@code public_holiday}, which is the whole point of SCRUM-107, so its
 * tests can no longer seed the calendar through the primary datasource either. They now build a
 * real {@code dcre_hcs} database carrying the table AND the published {@code hol_cde_view}, and
 * point {@code dcre.cde.holidays-db-url} at it.
 *
 * <p>The view matters here rather than being a formality: seeding a bare table would let CDE pass
 * its suite while reading something HCS does not actually publish, and the first honest signal
 * would be an empty result on the cluster. The DDL below is deliberately the same shape as
 * {@code hcs}'s own v1 changeset.
 */
public final class HolidayCalendarFixture {

    /** The database HCS owns. CDE only ever reads from it. */
    public static final String HCS_DB = "dcre_hcs";

    private HolidayCalendarFixture() {
    }

    /** Rewrites the container JDBC URL to target another database in the same cluster. */
    public static String forDatabase(final CockroachContainer crdb, final String dbName) {
        final String jdbcUrl = crdb.getJdbcUrl();
        final int queryStart = jdbcUrl.indexOf('?');
        final String base = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
        final String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return "%s/%s%s".formatted(base.substring(0, base.lastIndexOf('/')), dbName, query);
    }

    /** The URL CDE's dedicated read-only datasource is pointed at in tests. */
    public static String url(final CockroachContainer crdb) {
        return forDatabase(crdb, HCS_DB);
    }

    /** Creates dcre_hcs with the calendar table and the published view. Call from a static block. */
    public static void create(final CockroachContainer crdb) {
        try (var c = DriverManager.getConnection(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS " + HCS_DB);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create " + HCS_DB, e);
        }
        final JdbcTemplate hcs = jdbc(crdb);
        hcs.execute("""
                CREATE TABLE IF NOT EXISTS public_holiday (
                    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    country VARCHAR(2) NOT NULL,
                    holiday_date DATE NOT NULL,
                    local_name VARCHAR(128),
                    name VARCHAR(128),
                    is_global BOOL,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_public_holiday_country_date UNIQUE (country, holiday_date))""");
        hcs.execute("CREATE VIEW IF NOT EXISTS hol_cde_view AS "
                + "SELECT country, holiday_date FROM public_holiday");
    }

    /** A template on dcre_hcs, for tests that seed or count the calendar. */
    public static JdbcTemplate jdbc(final CockroachContainer crdb) {
        return new JdbcTemplate(new DriverManagerDataSource(
                url(crdb), crdb.getUsername(), crdb.getPassword()));
    }
}
