package com.corebanking.systemreportjob.usecase.ports.in;

import java.util.UUID;

public interface ExecuteScheduledJobUseCase {
    void execute(UUID taskId);
}
