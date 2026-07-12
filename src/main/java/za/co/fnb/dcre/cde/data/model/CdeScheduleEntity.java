package za.co.fnb.dcre.cde.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.time.LocalDate;
import java.util.UUID;

@Table("cde_schedule")
public class CdeScheduleEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private LocalDate processDate;

    public static CdeScheduleEntity of(UUID arrivalId, int sequence, LocalDate processDate) {
        CdeScheduleEntity e = new CdeScheduleEntity();
        e.arrivalId = arrivalId;
        e.sequence = sequence;
        e.processDate = processDate;
        return e;
    }

    public UUID getArrivalId() { return arrivalId; }
    public Integer getSequence() { return sequence; }
    public LocalDate getProcessDate() { return processDate; }
}
