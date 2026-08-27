package com.system.reportjob.domain.exception;

public class PgpEncryptionFailedException extends BusinessException {
    public PgpEncryptionFailedException(String companyCode, String reason) {
        super(ErrorCode.PGP_ENCRYPTION_FAILED, companyCode, reason);
    }
}
