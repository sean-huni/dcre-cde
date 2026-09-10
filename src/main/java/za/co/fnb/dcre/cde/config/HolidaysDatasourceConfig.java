package za.co.fnb.dcre.cde.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import za.co.fnb.dcre.cde.data.repo.HolidayCalendarDao;

import javax.sql.DataSource;

/**
 * SCRUM-107: the {@code @Import}-able unit giving CDE a READ-ONLY connection to the HCS-owned
 * {@code dcre_hcs} holiday calendar, for the R-38 process-date roll.
 *
 * <p>Deliberately a copy of {@code ctv}'s {@code MandatesDatasourceConfig}, down to the caveats,
 * because that is the sibling already solving this exact problem (cross-context read over a
 * dedicated connection). Every deviation from it would be drift.
 *
 * <p><b>Usage: {@code @Import} only, never component-scanned.</b> The {@code dcre_hcs}
 * {@link DataSource} is built INLINE and is NOT exposed as a bean: a standalone {@code DataSource}
 * bean would trip Boot's {@code DataSourceAutoConfiguration}
 * ({@code @ConditionalOnMissingBean(DataSource.class)}) and REPLACE the primary {@code dcre_col}
 * datasource. It is a deliberately non-pooling {@link SimpleDriverDataSource}: the calendar is
 * read twice per job, so a pool would idle unclosed for the life of the context.
 *
 * <p><b>Why this exists at all.</b> {@code public_holiday} used to sit in {@code dcre_col}, and
 * CDE carried its own changeset creating it there to win a bootstrap race with HCS. The owner
 * ruled that out on 2026-08-08: holiday data in the collections database is a 12FactorApp/SOLID
 * violation (https://12factor.net/). CDE now reads the published {@code hol_cde_view} in another
 * context's database and creates nothing.
 *
 * <p><b>Dedicated read-only credentials.</b> The connection uses
 * {@code dcre.cde.holidays-db-user} / {@code dcre.cde.holidays-db-password} (default {@code root}
 * / blank for the dev clean-clone), NOT the primary {@code spring.datasource.*} creds, so the
 * standing-cluster cde role can be granted only {@code SELECT} on {@code hol_cde_view}. The
 * resolved URL and user are logged once at startup so an operator can spot a wired-but-wrong
 * target.
 *
 * <p><b>The dev default is guarded, not dropped.</b> The localhost default is what lets a clean
 * clone boot with no {@code .env}, so it survives for local dev and becomes FATAL in a pod: if
 * {@code KUBERNETES_SERVICE_HOST} is set (the kubelet sets it in every container) and the url is
 * still the committed dev default, the context fails at start naming the variable. This is the
 * lesson from the CTV projection gate, which shipped with no {@code DCRE_CTV_MANDATES_DB_URL} and
 * quietly aimed at localhost in-cluster for weeks.
 */
@Configuration(proxyBeanMethods = false)
public class HolidaysDatasourceConfig {

    /** The committed local-dev target; kept verbatim as the placeholder default so a clean clone
     *  boots with no {@code .env}. Pinned to the yml by a parity test. */
    static final String LOCAL_DEV_URL = "jdbc:postgresql://localhost:26257/dcre_hcs?sslmode=disable";

    private static final Logger log = LoggerFactory.getLogger(HolidaysDatasourceConfig.class);

    @Bean
    HolidayCalendarDao holidayCalendarDao(
            @Value("${dcre.cde.holidays-db-url:" + LOCAL_DEV_URL + "}") final String url,
            @Value("${dcre.cde.holidays-db-user:root}") final String user,
            @Value("${dcre.cde.holidays-db-password:}") final String password,
            @Value("${KUBERNETES_SERVICE_HOST:}") final String clusterApiHost) {
        requireWiredTargetInCluster(url, clusterApiHost);
        log.info("cde holiday calendar: dcre_hcs read-only view url={} user={}", url, user);
        final DataSource dcreHcs = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new HolidayCalendarDao(new JdbcTemplate(dcreHcs));
    }

    /** Fail fast in a pod still on the local-dev default: CDE would otherwise start, log the wrong
     *  target, and fail the R-38 roll at query time, one arrival at a time. */
    private static void requireWiredTargetInCluster(final String url, final String clusterApiHost) {
        if (clusterApiHost == null || clusterApiHost.isBlank() || !LOCAL_DEV_URL.equals(url)) {
            return;
        }
        throw new IllegalStateException(
                "set DCRE_CDE_HOLIDAYS_DB_URL: running in-cluster (KUBERNETES_SERVICE_HOST="
                        + clusterApiHost + ") on the local-dev dcre_hcs default " + LOCAL_DEV_URL
                        + "; the R-38 process-date roll reads hol_cde_view and localhost is not it");
    }
}
