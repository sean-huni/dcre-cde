package za.co.fnb.dcre.cde.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R-38 2nd amendment: candidate = collection date + processing lead, then the
 * final roll past Sundays and ZA public holidays only (Saturdays valid), and
 * the computed process date must land strictly after the collection date.
 */
class ProcessDateCalculatorTest {

    @Test
    void ratifiedExampleLeadLandsOnPlainWednesdayUnchanged() {
        // Sean's worked example: collection Mon 2026-07-13 + 2 = Wed 2026-07-15, no roll.
        assertEquals(LocalDate.parse("2026-07-15"),
                ProcessDateCalculator.calculate(LocalDate.parse("2026-07-13"), 2, Set.of()));
    }

    @Test
    void candidateOnSundayRollsToMonday() {
        // Collection Fri 2026-02-20 + 2 = Sun 2026-02-22 -> Mon 2026-02-23.
        assertEquals(LocalDate.parse("2026-02-23"),
                ProcessDateCalculator.calculate(LocalDate.parse("2026-02-20"), 2, Set.of()));
    }

    @Test
    void candidateOnHolidaySaturdayRollsPastSundayAndHolidayMondayToTuesday() {
        // Collection Thu 2026-02-12 + 2 = Sat 2026-02-14 (holiday) -> Sun 15 -> Mon 16 (holiday) -> Tue 17.
        Set<LocalDate> hols = Set.of(LocalDate.parse("2026-02-14"), LocalDate.parse("2026-02-16"));
        assertEquals(LocalDate.parse("2026-02-17"),
                ProcessDateCalculator.calculate(LocalDate.parse("2026-02-12"), 2, hols));
    }

    @Test
    void candidateOnPlainSaturdayStands() {
        // Collection Thu 2026-02-19 + 2 = Sat 2026-02-21, valid as-is.
        assertEquals(LocalDate.parse("2026-02-21"),
                ProcessDateCalculator.calculate(LocalDate.parse("2026-02-19"), 2, Set.of()));
    }

    @Test
    void failsClosedWhenProcessDateWouldNotFollowCollectionDate() {
        // Lead 0 on a plain weekday leaves process == collection, violating the ruled invariant.
        assertThrows(IllegalStateException.class,
                () -> ProcessDateCalculator.calculate(LocalDate.parse("2026-07-13"), 0, Set.of()));
    }

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
