package com.system.reportjob.usecase.ports.out;

import java.nio.file.Path;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface PgpEncryptionGatewayPort {
    Path encryptAndSign(Path plaintextFile, CompanyPgpKeyConfig keyConfig);
}
