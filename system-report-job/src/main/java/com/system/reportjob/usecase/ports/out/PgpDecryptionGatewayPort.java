package com.system.reportjob.usecase.ports.out;

import java.nio.file.Path;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface PgpDecryptionGatewayPort {
    Path decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig);
}
