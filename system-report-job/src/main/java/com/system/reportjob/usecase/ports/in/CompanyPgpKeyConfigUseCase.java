package com.system.reportjob.usecase.ports.in;

import java.util.List;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface CompanyPgpKeyConfigUseCase {
    CompanyPgpKeyConfig create(CreateCompanyPgpKeyConfigCommand command);

    CompanyPgpKeyConfig update(String companyCode, UpdateCompanyPgpKeyConfigCommand command);

    void delete(String companyCode);

    CompanyPgpKeyConfig getByCompanyCode(String companyCode);

    List<CompanyPgpKeyConfig> list();
}
