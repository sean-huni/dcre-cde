package za.co.fnb.dcre.cde.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.cde.data.model.CdeScheduleEntity;

import java.time.LocalDate;
import java.util.UUID;

public interface CdeScheduleRepo extends CrudRepository<CdeScheduleEntity, UUID> {

    /**
     * R-41 set-based write: schedules every PASS row of the arrival in ONE
     * INSERT..SELECT, replacing the per-row loop. Upsert stays keyed
     * (arrival_id, sequence) so a rerun is idempotent (R-05); the conflict
     * branch bumps updated_at and the optimistic-lock version.
     *
     * @return number of rows written (inserted or updated).
     */
    @Modifying
    @Query("""
            INSERT INTO cde_schedule (arrival_id, sequence, process_date)
            SELECT vl.arrival_id, vl.sequence, :processDate
            FROM validation_log vl
            WHERE vl.arrival_id = :arrivalId AND vl.outcome = 'PASS'
            ON CONFLICT (arrival_id, sequence) DO UPDATE SET process_date = EXCLUDED.process_date,
                updated_at = now(), version = cde_schedule.version + 1""")
    int upsertAllPassRows(@Param("arrivalId") UUID arrivalId, @Param("processDate") LocalDate processDate);

    long countByArrivalId(UUID arrivalId);
}
