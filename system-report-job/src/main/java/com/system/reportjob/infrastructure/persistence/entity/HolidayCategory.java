package com.system.reportjob.infrastructure.persistence.entity;

/**
 * Mirrors the {@code holiday_category} Postgres enum type (see
 * {@code V9__create_holidays.sql}). Constant names match the DB labels exactly so
 * {@code @JdbcTypeCode(SqlTypes.NAMED_ENUM)} can map them without a converter.
 */
public enum HolidayCategory {
    FIXED,
    LUNAR,
    SUBSTITUTED,
    BRIDGE
}
