package com.system.reportjob.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.system.reportjob.infrastructure.persistence.entity.CompanyPgpKeyConfigEntity;

public interface CompanyPgpKeyConfigJpaRepository extends JpaRepository<CompanyPgpKeyConfigEntity, UUID> {
    Optional<CompanyPgpKeyConfigEntity> findByCompanyCode(String companyCode);
}
