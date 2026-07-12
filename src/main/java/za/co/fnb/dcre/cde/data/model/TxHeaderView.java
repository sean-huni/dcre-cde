package za.co.fnb.dcre.cde.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Read model over CRR's tx_header. */
@Table("tx_header")
public class TxHeaderView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private String businessDate;

    public UUID getArrivalId() { return arrivalId; }
    public String getBusinessDate() { return businessDate; }
}
