CREATE TABLE user_exports (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL,
    username       VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(20),
    address        VARCHAR(500),
    gender         VARCHAR(10),
    dob            DATE,
    description    VARCHAR(500),
    status         VARCHAR(20) NOT NULL,
    exported_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_exports_user_id ON user_exports (user_id);
CREATE INDEX idx_user_exports_exported_at ON user_exports (exported_at);
