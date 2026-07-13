package za.co.fnb.dcre.cde.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Read model over CRR's tx_header. */
@Table("tx_header")
public class TxHeaderView {

    @Id
    private UUID id;
    private UUID arrivalId;

    /**
     * Client-supplied collection date (R-38 2nd amendment). The physical column
     * is still business_date; the CRR-side rename is registered code debt.
     */
    @Column("business_date")
    private String collectionDate;

    public UUID getArrivalId() { return arrivalId; }
    public String getCollectionDate() { return collectionDate; }
}
