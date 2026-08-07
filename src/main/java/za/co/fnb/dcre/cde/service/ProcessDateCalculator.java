package za.co.fnb.dcre.cde.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Pure R-38 (2nd amendment) process-date rule (no I/O): the processing lead is
 * applied first (candidate = collection date + lead days), then the final
 * adjustment rolls the candidate forward one day at a time while it is a Sunday
 * or a ZA public holiday. Saturdays are valid process dates (Sean-ruled
 * 2026-07-12). The lead-then-roll shape is the SYNTHETIC-CONTRACT placeholder
 * (R-35) for the unrecovered collection-cycle rule (A-3). The computed process
 * date must land strictly after the collection date; a lead that violates that
 * invariant fails closed.
 */
public final class ProcessDateCalculator {

    private ProcessDateCalculator() {
    }

    public static LocalDate calculate(LocalDate collectionDate, int processingLeadDays,
                                      Set<LocalDate> holidays) {
        return calculate(collectionDate, processingLeadDays, holidays, null);
    }

    /**
     * A-77 (SCRUM-107): clock-aware. R-37 has CRW emit only where
     * {@code process_date = today}, so a process date in the PAST is not "late", it is
     * UNREACHABLE: the row is warehoused for a day that has already gone and is never emitted.
     * Observed on the cluster, 8 rows at 2026-07-29 against a run date of 2026-08-07, and since
     * R-37 was amended to gate DC completion on an emission, the arrival then never completed.
     *
     * <p>The lead is therefore applied from whichever of the collection date and {@code today}
     * is LATER, so a back-dated instruction is processed at the earliest date it actually can
     * be. Futured instructions are untouched, because for them the collection date is already
     * the later of the two: warehousing is preserved exactly.
     *
     * @param today the run date, or null to keep the pure, clock-free behaviour
     */
    public static LocalDate calculate(LocalDate collectionDate, int processingLeadDays,
                                      Set<LocalDate> holidays, LocalDate today) {
        LocalDate base = today == null || !today.isAfter(collectionDate) ? collectionDate : today;
        LocalDate processDate = roll(base.plusDays(processingLeadDays), holidays);
        if (!processDate.isAfter(collectionDate)) {
            throw new IllegalStateException("process date " + processDate
                    + " must follow collection date " + collectionDate
                    + " (processing-lead-days=" + processingLeadDays + ")");
        }
        return processDate;
    }

    public static LocalDate roll(LocalDate candidate, Set<LocalDate> holidays) {
        LocalDate d = candidate;
        while (d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
