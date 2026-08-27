package com.system.reportjob.usecase.ports.in;

public record CreateCompanyPgpKeyConfigCommand(
        String companyCode, String bankPrivateKeyArmored, String bankKeyPassphrase, String companyPublicKeyArmored) {}
