package com.corebanking.systemreportjob.infrastructure.persistence.entity;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tasks")
@SQLDelete(sql = "UPDATE tasks SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class TaskEntity extends BaseEntity {
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "task_group", nullable = false)
    private String taskGroup;

    @Column(name = "job_definition_id", nullable = false)
    private UUID jobDefinitionId;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "cron_expression")
    private String cronExpression;

    @Column(name = "interval_in_seconds")
    private Integer intervalInSeconds;

    @Column(name = "repeat_count")
    private Integer repeatCount;

    @Column(name = "interval_in_days")
    private Integer intervalInDays;

    @Column(name = "interval_in_minutes")
    private Integer intervalInMinutes;

    @Column(name = "starting_daily_at")
    private LocalTime startingDailyAt;

    @Column(name = "ending_daily_at")
    private LocalTime endingDailyAt;

    @Column(name = "timezone_id")
    private String timezoneId;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "description")
    private String description;
}
