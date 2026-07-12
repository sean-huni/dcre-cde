package za.co.fnb.dcre.cde.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.cde.data.model.CdeScheduleEntity;
import za.co.fnb.dcre.cde.data.model.PassVerdictView;
import za.co.fnb.dcre.cde.data.model.TxHeaderView;
import za.co.fnb.dcre.cde.data.repo.CdeScheduleRepo;
import za.co.fnb.dcre.cde.data.repo.PassVerdictViewRepo;
import za.co.fnb.dcre.cde.data.repo.TxHeaderViewRepo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Business tier: estimates and persists process_date per PASS transaction
 * (R-37: CDE schedules, never emits). Date rule = business_date + offset-days
 * (SYNTHETIC-CONTRACT, A-3 residual). Upserts keyed (arrival_id, sequence):
 * rescheduling is idempotent (R-05). Zero PASS rows = valid no-op run (A-7).
 */
@Service
public class ScheduleService {

    private final TxHeaderViewRepo headers;
    private final PassVerdictViewRepo verdicts;
    private final CdeScheduleRepo schedules;
    private final int offsetDays;

    public ScheduleService(TxHeaderViewRepo headers, PassVerdictViewRepo verdicts,
                           CdeScheduleRepo schedules,
                           @Value("${dcre.cde.offset-days:2}") int offsetDays) {
        this.headers = headers;
        this.verdicts = verdicts;
        this.schedules = schedules;
        this.offsetDays = offsetDays;
    }

    /** @return number of transactions scheduled. */
    public int schedule(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        LocalDate processDate = LocalDate.parse(header.getBusinessDate().strip(),
                DateTimeFormatter.BASIC_ISO_DATE).plusDays(offsetDays);
        List<PassVerdictView> passes = verdicts.findByArrivalIdAndOutcomeOrderBySequence(arrivalId, "PASS");
        for (PassVerdictView pass : passes) {
            schedules.upsert(CdeScheduleEntity.of(arrivalId, pass.getSequence(), processDate));
        }
        return passes.size();
    }
}
