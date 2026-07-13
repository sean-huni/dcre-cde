package za.co.fnb.dcre.cde.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Read model over CTV's validation_log (grants-based, R-04/R-06). */
@Table("validation_log")
public class PassVerdictView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer sequence;
    private String outcome;

    public Integer getSequence() { return sequence; }
}
