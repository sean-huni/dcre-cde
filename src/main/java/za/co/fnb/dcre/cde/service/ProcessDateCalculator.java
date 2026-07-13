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
        LocalDate processDate = roll(collectionDate.plusDays(processingLeadDays), holidays);
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
