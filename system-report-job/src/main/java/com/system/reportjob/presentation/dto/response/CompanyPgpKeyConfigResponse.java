package com.system.reportjob.presentation.dto.response;

import java.util.UUID;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public record CompanyPgpKeyConfigResponse(UUID id, String companyCode, String keyFingerprint, boolean active) {
    public static CompanyPgpKeyConfigResponse from(CompanyPgpKeyConfig config) {
        return new CompanyPgpKeyConfigResponse(
                config.id(), config.companyCode(), config.keyFingerprint(), config.active());
    }
}
