package za.co.fnb.dcre.cde.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R-38 roll rule: only Sundays and ZA public holidays roll; Saturdays are valid. */
class ProcessDateCalculatorTest {

    @Test
    void rollsPastHolidaySaturdaySundayAndHolidayMonday() {
        // 2026-02-14 is a Saturday; Sat 14 and Mon 16 are public holidays (Sean's 1.1 example).
        Set<LocalDate> hols = Set.of(LocalDate.parse("2026-02-14"), LocalDate.parse("2026-02-16"));
        assertEquals(LocalDate.parse("2026-02-17"),
                ProcessDateCalculator.roll(LocalDate.parse("2026-02-14"), hols));
    }

    @Test
    void plainSaturdayStands() {
        assertEquals(LocalDate.parse("2026-02-21"),
                ProcessDateCalculator.roll(LocalDate.parse("2026-02-21"), Set.of()));
    }

    @Test
    void sundayRollsToMonday() {
        assertEquals(LocalDate.parse("2026-02-23"),
                ProcessDateCalculator.roll(LocalDate.parse("2026-02-22"), Set.of()));
    }

    @Test
    void plainWeekdayUnchanged() {
        Set<LocalDate> hols = Set.of(LocalDate.parse("2026-02-14"), LocalDate.parse("2026-02-16"));
        assertEquals(LocalDate.parse("2026-02-18"),
                ProcessDateCalculator.roll(LocalDate.parse("2026-02-18"), hols));
    }
}
