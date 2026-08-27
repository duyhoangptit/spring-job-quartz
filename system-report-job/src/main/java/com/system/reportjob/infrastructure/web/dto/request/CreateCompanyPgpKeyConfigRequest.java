package com.system.reportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateCompanyPgpKeyConfigRequest(
        @NotBlank String companyCode,
        @NotBlank String bankPrivateKeyArmored,
        @NotBlank String bankKeyPassphrase,
        @NotBlank String companyPublicKeyArmored) {}
