package com.system.reportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateCompanyPgpKeyConfigRequest(
        @NotBlank String bankPrivateKeyArmored,
        @NotBlank String bankKeyPassphrase,
        @NotBlank String companyPublicKeyArmored,
        Boolean active) {}
