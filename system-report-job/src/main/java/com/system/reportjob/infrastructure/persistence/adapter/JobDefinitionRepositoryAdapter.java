package com.system.reportjob.infrastructure.persistence.adapter;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.infrastructure.persistence.entity.JobDefinitionEntity;
import com.system.reportjob.infrastructure.persistence.repository.JobDefinitionJpaRepository;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;

@Component
public class JobDefinitionRepositoryAdapter implements JobDefinitionRepositoryPort {

    private final JobDefinitionJpaRepository jpaRepository;

    public JobDefinitionRepositoryAdapter(JobDefinitionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public JobDefinition save(JobDefinition definition) {
        JobDefinitionEntity entity = toEntity(definition);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<JobDefinition> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    private JobDefinitionEntity toEntity(JobDefinition definition) {
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(definition.id());
        entity.setJobType(definition.jobType());
        entity.setExpression(definition.expression());
        entity.setDescription(definition.description());
        return entity;
    }

    private JobDefinition toDomain(JobDefinitionEntity entity) {
        return new JobDefinition(entity.getId(), entity.getJobType(), entity.getExpression(), entity.getDescription());
    }
}
