package com.system.reportjob.infrastructure.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the {@code holidays} table (see {@code V9__create_holidays.sql}). Not a {@link
 * BaseEntity} subclass: the table uses a {@code BIGSERIAL} id (not UUID) and has no soft-delete
 * column.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "holidays",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "unique_holiday_date_per_branch",
                        columnNames = {"holiday_date", "country_code", "branch_id"}))
public class HolidayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "holiday_name", nullable = false, length = 150)
    private String holidayName;

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode = "VN";

    @Column(name = "branch_id", nullable = false, length = 20)
    private String branchId = "ALL";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "category", columnDefinition = "holiday_category", nullable = false)
    private HolidayCategory category = HolidayCategory.FIXED;

    // Ngày nghỉ bù/hoán đổi thuộc về ngày lễ gốc nào (FK tới holidays.id, không map quan hệ JPA
    // để tránh load lồng nhau không cần thiết - theo cùng cách TaskEntity.jobDefinitionId làm).
    @Column(name = "parent_holiday_id")
    private Long parentHolidayId;

    @Column(name = "is_clearing_day", nullable = false)
    private boolean clearingDay = false;

    @Column(name = "is_working_day", nullable = false)
    private boolean workingDay = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;
}
