package za.co.fnb.dcre.cde.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.cde.data.model.CdeScheduleEntity;

import java.util.UUID;

public interface CdeScheduleRepo extends CrudRepository<CdeScheduleEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO cde_schedule (arrival_id, sequence, process_date)
            VALUES (:#{#e.arrivalId}, :#{#e.sequence}, :#{#e.processDate})
            ON CONFLICT (arrival_id, sequence) DO UPDATE SET process_date = EXCLUDED.process_date""")
    void upsert(@Param("e") CdeScheduleEntity e);

    long countByArrivalId(UUID arrivalId);
}
