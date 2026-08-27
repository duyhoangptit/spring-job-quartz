package com.system.reportjob.usecase.ports.in;

public record UpdateCompanyPgpKeyConfigCommand(
        String bankPrivateKeyArmored, String bankKeyPassphrase, String companyPublicKeyArmored, Boolean active) {}
