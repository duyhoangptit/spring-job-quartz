package com.system.reportjob.usecase.service;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.EncryptCompanyFileUseCase;
import com.system.reportjob.usecase.ports.out.PgpEncryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Service
public class PgpFileEncryptionService implements EncryptCompanyFileUseCase {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort;
    private final PgpEncryptionGatewayPort encryptionGatewayPort;

    public PgpFileEncryptionService(
            PgpKeyConfigRepositoryPort keyConfigRepositoryPort, PgpEncryptionGatewayPort encryptionGatewayPort) {
        this.keyConfigRepositoryPort = keyConfigRepositoryPort;
        this.encryptionGatewayPort = encryptionGatewayPort;
    }

    @Override
    public Path encryptFile(String companyCode, Path plaintextFilePath) {
        CompanyPgpKeyConfig keyConfig = keyConfigRepositoryPort
                .findByCompanyCode(companyCode)
                .filter(CompanyPgpKeyConfig::active)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        return encryptionGatewayPort.encryptAndSign(plaintextFilePath, keyConfig);
    }
}
