package za.co.fnb.dcre.cde.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.cde.data.model.PassVerdictView;

import java.util.List;
import java.util.UUID;

public interface PassVerdictViewRepo extends CrudRepository<PassVerdictView, UUID> {

    List<PassVerdictView> findByArrivalIdAndOutcomeOrderBySequence(UUID arrivalId, String outcome);
}
