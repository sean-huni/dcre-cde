package za.co.fnb.dcre.cde.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class ScheduleTasklet implements Tasklet {

    private final ScheduleService service;

    public ScheduleTasklet(ScheduleService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        int scheduled = service.schedule(arrivalId);
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("scheduled", scheduled);
        return RepeatStatus.FINISHED;
    }
}
