package com.system.reportjob.usecase.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Service
public class CompanyPgpKeyConfigService implements CompanyPgpKeyConfigUseCase {

    private final PgpKeyConfigRepositoryPort repositoryPort;

    public CompanyPgpKeyConfigService(PgpKeyConfigRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public CompanyPgpKeyConfig create(CreateCompanyPgpKeyConfigCommand command) {
        if (repositoryPort.findByCompanyCode(command.companyCode()).isPresent()) {
            throw new PgpKeyConfigAlreadyExistsException(command.companyCode());
        }
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                command.companyCode(),
                command.bankPrivateKeyArmored(),
                command.bankKeyPassphrase(),
                command.companyPublicKeyArmored(),
                null,
                true);
        return repositoryPort.save(config);
    }

    @Override
    public CompanyPgpKeyConfig update(String companyCode, UpdateCompanyPgpKeyConfigCommand command) {
        CompanyPgpKeyConfig existing = repositoryPort
                .findByCompanyCode(companyCode)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        CompanyPgpKeyConfig updated = new CompanyPgpKeyConfig(
                existing.id(),
                companyCode,
                command.bankPrivateKeyArmored(),
                command.bankKeyPassphrase(),
                command.companyPublicKeyArmored(),
                existing.keyFingerprint(),
                command.active() != null ? command.active() : existing.active());
        return repositoryPort.save(updated);
    }

    @Override
    public void delete(String companyCode) {
        repositoryPort.findByCompanyCode(companyCode).orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        repositoryPort.delete(companyCode);
    }

    @Override
    public CompanyPgpKeyConfig getByCompanyCode(String companyCode) {
        return repositoryPort
                .findByCompanyCode(companyCode)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
    }

    @Override
    public List<CompanyPgpKeyConfig> list() {
        return repositoryPort.findAll();
    }
}
