package com.system.reportjob.domain.exception;

public class PgpKeyConfigNotFoundException extends BusinessException {
    public PgpKeyConfigNotFoundException(String companyCode) {
        super(ErrorCode.PGP_KEY_CONFIG_NOT_FOUND, companyCode);
    }
}
