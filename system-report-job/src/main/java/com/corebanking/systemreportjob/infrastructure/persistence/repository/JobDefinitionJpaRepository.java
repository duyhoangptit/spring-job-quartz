package com.corebanking.systemreportjob.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.corebanking.systemreportjob.infrastructure.persistence.entity.JobDefinitionEntity;

public interface JobDefinitionJpaRepository extends JpaRepository<JobDefinitionEntity, UUID> {}
