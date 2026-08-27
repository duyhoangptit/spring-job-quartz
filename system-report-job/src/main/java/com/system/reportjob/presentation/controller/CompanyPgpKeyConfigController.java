package com.system.reportjob.presentation.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.system.reportjob.infrastructure.common.ApiResponse;
import com.system.reportjob.presentation.dto.request.CreateCompanyPgpKeyConfigRequest;
import com.system.reportjob.presentation.dto.request.UpdateCompanyPgpKeyConfigRequest;
import com.system.reportjob.presentation.dto.response.CompanyPgpKeyConfigResponse;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/company-pgp-key-configs")
@Tag(
        name = "Company PGP key config",
        description = "Quản lý PGP key theo company, dùng để decrypt file trước khi xử lý")
@SecurityRequirement(name = "bearerAuth")
public class CompanyPgpKeyConfigController {

    private final CompanyPgpKeyConfigUseCase useCase;

    public CompanyPgpKeyConfigController(CompanyPgpKeyConfigUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ApiResponse<CompanyPgpKeyConfigResponse> create(
            @RequestBody @Valid CreateCompanyPgpKeyConfigRequest request) {
        var config = useCase.create(new CreateCompanyPgpKeyConfigCommand(
                request.companyCode(),
                request.bankPrivateKeyArmored(),
                request.bankKeyPassphrase(),
                request.companyPublicKeyArmored()));
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(config));
    }

    @GetMapping("/{companyCode}")
    public ApiResponse<CompanyPgpKeyConfigResponse> get(@PathVariable String companyCode) {
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(useCase.getByCompanyCode(companyCode)));
    }

    @GetMapping
    public ApiResponse<List<CompanyPgpKeyConfigResponse>> list() {
        return ApiResponse.ok(
                useCase.list().stream().map(CompanyPgpKeyConfigResponse::from).toList());
    }

    @PutMapping("/{companyCode}")
    public ApiResponse<CompanyPgpKeyConfigResponse> update(
            @PathVariable String companyCode, @RequestBody @Valid UpdateCompanyPgpKeyConfigRequest request) {
        var config = useCase.update(
                companyCode,
                new UpdateCompanyPgpKeyConfigCommand(
                        request.bankPrivateKeyArmored(),
                        request.bankKeyPassphrase(),
                        request.companyPublicKeyArmored(),
                        request.active()));
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(config));
    }

    @DeleteMapping("/{companyCode}")
    public ApiResponse<Void> delete(@PathVariable String companyCode) {
        useCase.delete(companyCode);
        return ApiResponse.ok();
    }
}
