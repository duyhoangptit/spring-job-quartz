package com.system.reportjob.usecase.service;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase;
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Service
public class PgpFileDecryptionService implements DecryptCompanyFileUseCase {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort;
    private final PgpDecryptionGatewayPort decryptionGatewayPort;

    public PgpFileDecryptionService(
            PgpKeyConfigRepositoryPort keyConfigRepositoryPort, PgpDecryptionGatewayPort decryptionGatewayPort) {
        this.keyConfigRepositoryPort = keyConfigRepositoryPort;
        this.decryptionGatewayPort = decryptionGatewayPort;
    }

    @Override
    public Path decryptFile(String companyCode, Path encryptedFilePath) {
        CompanyPgpKeyConfig keyConfig = keyConfigRepositoryPort
                .findByCompanyCode(companyCode)
                .filter(CompanyPgpKeyConfig::active)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        return decryptionGatewayPort.decryptAndVerify(encryptedFilePath, keyConfig);
    }
}
