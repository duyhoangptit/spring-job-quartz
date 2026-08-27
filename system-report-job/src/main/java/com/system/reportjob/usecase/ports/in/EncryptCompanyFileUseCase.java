package com.system.reportjob.usecase.ports.in;

import java.nio.file.Path;

public interface EncryptCompanyFileUseCase {
    Path encryptFile(String companyCode, Path plaintextFilePath);
}
