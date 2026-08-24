package com.system.reportjob.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.system.reportjob.infrastructure.persistence.entity.JobDefinitionEntity;

public interface JobDefinitionJpaRepository extends JpaRepository<JobDefinitionEntity, UUID> {}
