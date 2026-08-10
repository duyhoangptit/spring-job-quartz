package com.corebanking.systemreportjob.usecase.ports.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;

public interface TaskRepositoryPort {
    ScheduledTask save(ScheduledTask task);

    Optional<ScheduledTask> findById(UUID id);

    List<ScheduledTask> findAll();

    PageResult<ScheduledTask> search(String keyword, Pageable pageable);

    void delete(UUID id);
}
