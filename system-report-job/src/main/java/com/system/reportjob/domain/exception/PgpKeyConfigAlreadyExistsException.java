package com.system.reportjob.domain.exception;

public class PgpKeyConfigAlreadyExistsException extends BusinessException {
    public PgpKeyConfigAlreadyExistsException(String companyCode) {
        super(ErrorCode.PGP_KEY_CONFIG_ALREADY_EXISTS, companyCode);
    }
}
