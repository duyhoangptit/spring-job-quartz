package com.system.reportjob.domain.model;

import java.util.UUID;

public record CompanyPgpKeyConfig(
        UUID id,
        String companyCode,
        String bankPrivateKeyArmored,
        String bankKeyPassphrase,
        String companyPublicKeyArmored,
        String keyFingerprint,
        boolean active) {
    public CompanyPgpKeyConfig {
        if (companyCode == null || companyCode.isBlank()) {
            throw new IllegalArgumentException("companyCode không được rỗng");
        }
        if (bankPrivateKeyArmored == null || bankPrivateKeyArmored.isBlank()) {
            throw new IllegalArgumentException("bankPrivateKeyArmored không được rỗng");
        }
        if (bankKeyPassphrase == null || bankKeyPassphrase.isBlank()) {
            throw new IllegalArgumentException("bankKeyPassphrase không được rỗng");
        }
        if (companyPublicKeyArmored == null || companyPublicKeyArmored.isBlank()) {
            throw new IllegalArgumentException("companyPublicKeyArmored không được rỗng");
        }
    }
}
