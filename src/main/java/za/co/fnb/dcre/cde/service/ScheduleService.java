package za.co.fnb.dcre.cde.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.cde.data.model.ExcludedVerdict;
import za.co.fnb.dcre.cde.data.model.TxHeaderView;
import za.co.fnb.dcre.cde.data.repo.CdeScheduleRepo;
import za.co.fnb.dcre.cde.data.repo.PassVerdictViewRepo;
import za.co.fnb.dcre.cde.data.repo.PublicHolidayViewRepo;
import za.co.fnb.dcre.cde.data.repo.TxHeaderViewRepo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * Business tier: estimates and persists process_date per PASS transaction
 * (R-37: CDE schedules, never emits). The header carries the client-supplied
 * collection date (R-38 2nd amendment); Process_Date = candidate rolled per
 * R-38 where candidate = collection date + processing-lead-days, the
 * SYNTHETIC-CONTRACT placeholder (R-35) for the unrecovered collection-cycle
 * rule (A-3). The roll passes Sundays and ZA public holidays only, Saturdays
 * valid, and the result must land strictly after the collection date
 * (fail-closed). Fails closed when the holiday calendar has no rows for the
 * collection year. Writes are set-based: one INSERT..SELECT covers all PASS
 * rows (R-41), upsert-keyed (arrival_id, sequence) so rescheduling is
 * idempotent (R-05). Zero PASS rows = valid no-op run (A-7). Every non-PASS
 * verdict is WARN-logged for exclusion visibility (R-38).
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final int HOLIDAY_HORIZON_DAYS = 60;

    private final TxHeaderViewRepo headers;
    private final PassVerdictViewRepo verdicts;
    private final CdeScheduleRepo schedules;
    private final PublicHolidayViewRepo holidays;
    private final int processingLeadDays;
    private final String country;

    public ScheduleService(TxHeaderViewRepo headers, PassVerdictViewRepo verdicts,
                           CdeScheduleRepo schedules, PublicHolidayViewRepo holidays,
                           @Value("${dcre.cde.processing-lead-days:2}") int processingLeadDays,
                           @Value("${dcre.cde.country:ZA}") String country) {
        this.headers = headers;
        this.verdicts = verdicts;
        this.schedules = schedules;
        this.holidays = holidays;
        this.processingLeadDays = processingLeadDays;
        this.country = country;
    }

    /** @return number of transactions scheduled. */
    public int schedule(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        LocalDate collection = LocalDate.parse(header.getCollectionDate().strip(),
                DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate processDate = ProcessDateCalculator.calculate(collection, processingLeadDays,
                syncedHolidays(collection));
        int scheduled = schedules.upsertAllPassRows(arrivalId, processDate);
        warnExcluded(arrivalId);
        return scheduled;
    }

    /** Fail-closed (R-38): an unsynced calendar year must fail the job, never misdate. */
    private Set<LocalDate> syncedHolidays(LocalDate collection) {
        LocalDate yearStart = LocalDate.of(collection.getYear(), 1, 1);
        if (holidays.countForYear(country, yearStart, yearStart.plusYears(1)) == 0) {
            throw new IllegalStateException("holiday calendar not synced for " + collection.getYear());
        }
        return Set.copyOf(holidays.holidaysBetween(country, collection,
                collection.plusDays(HOLIDAY_HORIZON_DAYS)));
    }

    private void warnExcluded(UUID arrivalId) {
        for (ExcludedVerdict excluded : verdicts.findExcluded(arrivalId)) {
            log.warn("excluded stage=CDE arrival={} seq={} e2e={} reason=CTV_{}",
                    arrivalId, excluded.sequence(), excluded.e2e(), excluded.outcome());
        }
    }
}
