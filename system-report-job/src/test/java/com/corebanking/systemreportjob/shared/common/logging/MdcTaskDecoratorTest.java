package com.corebanking.systemreportjob.shared.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void copiesCallingThreadMdcIntoTheThreadThatRunsTheTask() throws InterruptedException {
        MDC.put("requestId", "abc-123");
        AtomicReference<String> seenOnWorkerThread = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> seenOnWorkerThread.set(MDC.get("requestId")));
        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seenOnWorkerThread.get()).isEqualTo("abc-123");
    }

    @Test
    void clearsMdcOnTheWorkerThreadAfterTheTaskFinishes() throws InterruptedException {
        MDC.put("requestId", "abc-123");
        AtomicReference<String> seenAfterRunOnWorkerThread = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> {});
        Thread worker = new Thread(() -> {
            decorated.run();
            seenAfterRunOnWorkerThread.set(MDC.get("requestId"));
        });
        worker.start();
        worker.join();

        assertThat(seenAfterRunOnWorkerThread.get()).isNull();
    }

    @Test
    void runsTheTaskEvenWhenCallingThreadHasNoMdcContext() throws InterruptedException {
        AtomicReference<String> seenOnWorkerThread = new AtomicReference<>();
        AtomicReference<Boolean> ran = new AtomicReference<>(false);

        Runnable decorated = decorator.decorate(() -> {
            seenOnWorkerThread.set(MDC.get("requestId"));
            ran.set(true);
        });
        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(ran.get()).isTrue();
        assertThat(seenOnWorkerThread.get()).isNull();
    }
}
