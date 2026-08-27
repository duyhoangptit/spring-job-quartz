package com.system.reportjob.domain.exception;

public class PgpSignatureInvalidException extends BusinessException {
    public PgpSignatureInvalidException(String companyCode, String reason) {
        super(ErrorCode.PGP_SIGNATURE_INVALID, companyCode, reason);
    }
}
