package za.co.fnb.dcre.cde.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.cde.data.model.ExcludedVerdict;
import za.co.fnb.dcre.cde.data.model.PassVerdictView;

import java.util.List;
import java.util.UUID;

public interface PassVerdictViewRepo extends CrudRepository<PassVerdictView, UUID> {

    /** Non-PASS verdicts joined to the spine for e2e (exclusion WARNs, R-38). */
    @Query(value = """
            SELECT v.sequence, t.e2e, v.outcome
            FROM validation_log v
            LEFT JOIN tx_entry t ON t.arrival_id = v.arrival_id AND t.sequence = v.sequence
            WHERE v.arrival_id = :arrivalId AND v.outcome <> 'PASS'
            ORDER BY v.sequence""", rowMapperClass = ExcludedVerdictMapper.class)
    List<ExcludedVerdict> findExcluded(@Param("arrivalId") UUID arrivalId);
}
