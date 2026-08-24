package com.system.reportjob.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerDefinition;
import com.system.reportjob.infrastructure.persistence.entity.TaskEntity;
import com.system.reportjob.infrastructure.persistence.repository.TaskJpaRepository;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

@Component
public class TaskRepositoryAdapter implements TaskRepositoryPort {

    private final TaskJpaRepository jpaRepository;

    public TaskRepositoryAdapter(TaskJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ScheduledTask save(ScheduledTask task) {
        return toDomain(jpaRepository.save(toEntity(task)));
    }

    @Override
    public Optional<ScheduledTask> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<ScheduledTask> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<ScheduledTask> search(String keyword, Pageable pageable) {
        Page<TaskEntity> page = (keyword == null || keyword.isBlank())
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findByNameContainingIgnoreCase(keyword, pageable);
        return new PageResult<>(
                page.getContent().stream().map(this::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @Override
    public boolean existsByJobDefinitionId(UUID jobDefinitionId) {
        return jpaRepository.existsByJobDefinitionId(jobDefinitionId);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    private TaskEntity toEntity(ScheduledTask task) {
        TaskEntity entity = new TaskEntity();
        entity.setId(task.id());
        entity.setName(task.name());
        entity.setTaskGroup(task.group());
        entity.setJobDefinitionId(task.jobDefinitionId());
        entity.setTimezoneId(task.timezoneId());
        entity.setPriority(task.priority());
        entity.setDescription(task.description());
        entity.setCalendarName(task.calendarName());
        entity.setTriggerType(task.trigger().type());
        switch (task.trigger()) {
            case TriggerDefinition.Cron c -> entity.setCronExpression(c.cronExpression());
            case TriggerDefinition.Simple s -> {
                entity.setIntervalInSeconds(s.intervalInSeconds());
                entity.setRepeatCount(s.repeatCount());
            }
            case TriggerDefinition.CalendarInterval ci -> entity.setIntervalInDays(ci.intervalInDays());
            case TriggerDefinition.DailyTimeInterval d -> {
                entity.setStartingDailyAt(d.startingDailyAt());
                entity.setEndingDailyAt(d.endingDailyAt());
                entity.setIntervalInMinutes(d.intervalInMinutes());
            }
        }
        return entity;
    }

    private ScheduledTask toDomain(TaskEntity entity) {
        TriggerDefinition trigger =
                switch (entity.getTriggerType()) {
                    case CRON -> new TriggerDefinition.Cron(entity.getCronExpression());
                    case SIMPLE -> new TriggerDefinition.Simple(entity.getIntervalInSeconds(), entity.getRepeatCount());
                    case CALENDAR_INTERVAL -> new TriggerDefinition.CalendarInterval(entity.getIntervalInDays());
                    case DAILY_TIME_INTERVAL -> new TriggerDefinition.DailyTimeInterval(
                            entity.getStartingDailyAt(), entity.getEndingDailyAt(), entity.getIntervalInMinutes());
                };
        return new ScheduledTask(
                entity.getId(),
                entity.getName(),
                entity.getTaskGroup(),
                entity.getJobDefinitionId(),
                trigger,
                entity.getCalendarName(),
                entity.getTimezoneId(),
                entity.getPriority(),
                entity.getDescription());
    }
}
