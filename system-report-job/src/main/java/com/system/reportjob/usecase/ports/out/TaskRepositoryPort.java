package com.system.reportjob.usecase.ports.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;

public interface TaskRepositoryPort {
    ScheduledTask save(ScheduledTask task);

    Optional<ScheduledTask> findById(UUID id);

    List<ScheduledTask> findAll();

    PageResult<ScheduledTask> search(String keyword, Pageable pageable);

    boolean existsByJobDefinitionId(UUID jobDefinitionId);

    void delete(UUID id);
}
