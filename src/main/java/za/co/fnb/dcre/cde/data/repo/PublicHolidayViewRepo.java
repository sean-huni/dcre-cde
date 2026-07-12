package za.co.fnb.dcre.cde.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.cde.data.model.PublicHolidayView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only over HCS's public_holiday (CDE never writes it, R-04). */
public interface PublicHolidayViewRepo extends Repository<PublicHolidayView, UUID> {

    @Query(value = """
            SELECT holiday_date
            FROM public_holiday
            WHERE country = :country AND holiday_date BETWEEN :from AND :to""",
            rowMapperClass = HolidayDateMapper.class)
    List<LocalDate> holidaysBetween(@Param("country") String country,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /** Fail-closed guard input: synced holiday count for the collection year. */
    @Query("""
            SELECT count(*)
            FROM public_holiday
            WHERE country = :country AND holiday_date >= :yearStart AND holiday_date < :nextYearStart""")
    long countForYear(@Param("country") String country,
                      @Param("yearStart") LocalDate yearStart,
                      @Param("nextYearStart") LocalDate nextYearStart);
}
