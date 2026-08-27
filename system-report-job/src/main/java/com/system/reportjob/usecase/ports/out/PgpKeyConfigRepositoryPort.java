package com.system.reportjob.usecase.ports.out;

import java.util.List;
import java.util.Optional;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface PgpKeyConfigRepositoryPort {
    CompanyPgpKeyConfig save(CompanyPgpKeyConfig config);

    Optional<CompanyPgpKeyConfig> findByCompanyCode(String companyCode);

    List<CompanyPgpKeyConfig> findAll();

    void delete(String companyCode);
}
