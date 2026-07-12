package za.co.fnb.dcre.cde.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.cde.data.model.CdeScheduleEntity;
import za.co.fnb.dcre.cde.data.model.ExcludedVerdict;
import za.co.fnb.dcre.cde.data.model.PassVerdictView;
import za.co.fnb.dcre.cde.data.model.TxHeaderView;
import za.co.fnb.dcre.cde.data.repo.CdeScheduleRepo;
import za.co.fnb.dcre.cde.data.repo.PassVerdictViewRepo;
import za.co.fnb.dcre.cde.data.repo.PublicHolidayViewRepo;
import za.co.fnb.dcre.cde.data.repo.TxHeaderViewRepo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Business tier: estimates and persists process_date per PASS transaction
 * (R-37: CDE schedules, never emits). Collection_Date = business_date +
 * offset-days (offset semantics remain the A-3 residual); Process_Date =
 * Collection_Date rolled per R-38 (roll rule resolved: past Sundays and ZA
 * public holidays, Saturdays valid). Fails closed when the holiday calendar
 * has no rows for the collection year. Upserts keyed (arrival_id, sequence):
 * rescheduling is idempotent (R-05). Zero PASS rows = valid no-op run (A-7).
 * Every non-PASS verdict is WARN-logged for exclusion visibility (R-38).
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final int HOLIDAY_HORIZON_DAYS = 60;

    private final TxHeaderViewRepo headers;
    private final PassVerdictViewRepo verdicts;
    private final CdeScheduleRepo schedules;
    private final PublicHolidayViewRepo holidays;
    private final int offsetDays;
    private final String country;

    public ScheduleService(TxHeaderViewRepo headers, PassVerdictViewRepo verdicts,
                           CdeScheduleRepo schedules, PublicHolidayViewRepo holidays,
                           @Value("${dcre.cde.offset-days:2}") int offsetDays,
                           @Value("${dcre.cde.country:ZA}") String country) {
        this.headers = headers;
        this.verdicts = verdicts;
        this.schedules = schedules;
        this.holidays = holidays;
        this.offsetDays = offsetDays;
        this.country = country;
    }

    /** @return number of transactions scheduled. */
    public int schedule(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        LocalDate collection = LocalDate.parse(header.getBusinessDate().strip(),
                DateTimeFormatter.BASIC_ISO_DATE).plusDays(offsetDays);
        LocalDate processDate = ProcessDateCalculator.roll(collection, syncedHolidays(collection));
        List<PassVerdictView> passes = verdicts.findByArrivalIdAndOutcomeOrderBySequence(arrivalId, "PASS");
        for (PassVerdictView pass : passes) {
            schedules.upsert(CdeScheduleEntity.of(arrivalId, pass.getSequence(), processDate));
        }
        warnExcluded(arrivalId);
        return passes.size();
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
