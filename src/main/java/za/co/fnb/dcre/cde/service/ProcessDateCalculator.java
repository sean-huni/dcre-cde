package za.co.fnb.dcre.cde.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Pure R-38 roll rule (no I/O): Process_Date = Collection_Date rolled forward one
 * day at a time while the date is a Sunday or a ZA public holiday. Saturdays are
 * valid process dates (Sean-ruled 2026-07-12): the historical "next available
 * weekday" phrasing is loose wording, not a Mon-Fri constraint.
 */
public final class ProcessDateCalculator {

    private ProcessDateCalculator() {
    }

    public static LocalDate roll(LocalDate collectionDate, Set<LocalDate> holidays) {
        LocalDate d = collectionDate;
        while (d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
