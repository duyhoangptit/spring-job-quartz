package com.system.reportjob.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.system.reportjob.infrastructure.persistence.entity.TaskExecutionHistoryEntity;

public interface TaskExecutionHistoryJpaRepository extends JpaRepository<TaskExecutionHistoryEntity, UUID> {
    Page<TaskExecutionHistoryEntity> findByTaskNameContainingIgnoreCase(String taskName, Pageable pageable);
}
