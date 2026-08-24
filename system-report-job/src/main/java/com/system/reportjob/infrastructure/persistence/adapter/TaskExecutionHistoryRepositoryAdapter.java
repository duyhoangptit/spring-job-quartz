package com.system.reportjob.infrastructure.persistence.adapter;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.TaskExecutionRecord;
import com.system.reportjob.infrastructure.persistence.entity.TaskExecutionHistoryEntity;
import com.system.reportjob.infrastructure.persistence.repository.TaskExecutionHistoryJpaRepository;
import com.system.reportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

@Component
public class TaskExecutionHistoryRepositoryAdapter implements TaskExecutionHistoryRepositoryPort {

    private final TaskExecutionHistoryJpaRepository jpaRepository;

    public TaskExecutionHistoryRepositoryAdapter(TaskExecutionHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public TaskExecutionRecord save(TaskExecutionRecord record) {
        TaskExecutionHistoryEntity entity = new TaskExecutionHistoryEntity();
        entity.setId(record.id() != null ? record.id() : UUID.randomUUID());
        entity.setTaskId(record.taskId());
        entity.setTaskName(record.taskName());
        entity.setStartTime(record.startTime());
        entity.setEndTime(record.endTime());
        entity.setExceptionMessage(record.exceptionMessage());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable) {
        Page<TaskExecutionHistoryEntity> page = (taskName == null || taskName.isBlank())
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findByTaskNameContainingIgnoreCase(taskName, pageable);
        return new PageResult<>(
                page.getContent().stream().map(this::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private TaskExecutionRecord toDomain(TaskExecutionHistoryEntity entity) {
        return new TaskExecutionRecord(
                entity.getId(),
                entity.getTaskId(),
                entity.getTaskName(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getExceptionMessage());
    }
}
