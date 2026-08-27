package com.system.reportjob.usecase.ports.in;

import java.nio.file.Path;

public interface DecryptCompanyFileUseCase {
    Path decryptFile(String companyCode, Path encryptedFilePath);
}
