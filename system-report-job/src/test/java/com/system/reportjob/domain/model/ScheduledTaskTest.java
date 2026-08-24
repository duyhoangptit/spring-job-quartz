package com.system.reportjob.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ScheduledTaskTest {

    @Test
    void constructsWithValidFields() {
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"),
                "Asia/Ho_Chi_Minh",
                5,
                "Daily report task");

        assertThat(task.name()).isEqualTo("daily-report");
        assertThat(task.jobDefinitionId()).isEqualTo(jobDefinitionId);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ScheduledTask(
                        UUID.randomUUID(),
                        "  ",
                        "reports",
                        UUID.randomUUID(),
                        new TriggerDefinition.Simple(60, 0),
                        "UTC",
                        1,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tên task");
    }

    @Test
    void rejectsNullJobDefinitionId() {
        assertThatThrownBy(() -> new ScheduledTask(
                        UUID.randomUUID(),
                        "daily-report",
                        "reports",
                        null,
                        new TriggerDefinition.Simple(60, 0),
                        "UTC",
                        1,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JobDefinition");
    }
}
