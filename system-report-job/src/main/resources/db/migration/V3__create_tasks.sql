CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    task_group VARCHAR(100) NOT NULL,
    job_definition_id UUID NOT NULL REFERENCES job_definitions (id),
    trigger_type VARCHAR(30) NOT NULL,
    cron_expression VARCHAR(100),
    interval_in_seconds INTEGER,
    repeat_count INTEGER,
    interval_in_days INTEGER,
    interval_in_minutes INTEGER,
    starting_daily_at TIME,
    ending_daily_at TIME,
    timezone_id VARCHAR(50),
    priority INTEGER,
    description VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_tasks_name ON tasks (name) WHERE is_deleted = FALSE;
