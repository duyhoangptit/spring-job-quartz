package com.system.reportjob.domain.exception;

public class PgpDecryptionFailedException extends BusinessException {
    public PgpDecryptionFailedException(String companyCode, String reason) {
        super(ErrorCode.PGP_DECRYPTION_FAILED, companyCode, reason);
    }
}
