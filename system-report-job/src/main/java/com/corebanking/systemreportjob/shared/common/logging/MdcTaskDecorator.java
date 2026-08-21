package com.corebanking.systemreportjob.shared.common.logging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies the submitting thread's MDC (e.g. {@code requestId}) onto the thread that actually runs
 * the task. MDC is thread-local, so a task handed off to an {@link java.util.concurrent.Executor}
 * — like the virtual-thread {@code jobActionTaskExecutor} — would otherwise log without it.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
