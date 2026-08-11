package com.corebanking.systemreportjob.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.corebanking.systemreportjob.infrastructure.persistence.entity.TaskEntity;

public interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {
    Page<TaskEntity> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
