# dcre-cde

Collection Day Estimator (R-37: schedules only, never emits). PASS verdicts + tx_header.business_date + offset-days -> cde_schedule upsert keyed (arrival_id, sequence). 3-tier: ScheduleTasklet -> ScheduleService -> data/repo. SYNTHETIC-CONTRACT date rule (A-3 residual).
