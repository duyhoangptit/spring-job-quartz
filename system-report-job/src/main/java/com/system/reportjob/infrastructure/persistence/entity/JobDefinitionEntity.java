package com.system.reportjob.infrastructure.persistence.entity;

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
@Table(name = "job_definitions")
@SQLDelete(sql = "UPDATE job_definitions SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class JobDefinitionEntity extends BaseEntity {
    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "expression")
    private String expression;

    @Column(name = "description")
    private String description;
}
