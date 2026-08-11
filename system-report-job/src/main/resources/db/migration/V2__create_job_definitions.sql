CREATE TABLE job_definitions (
    id UUID PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    expression TEXT,
    description VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE
);
