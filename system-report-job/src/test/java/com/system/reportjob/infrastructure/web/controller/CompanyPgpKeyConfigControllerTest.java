package com.system.reportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.common.GlobalExceptionHandler;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;

@WebMvcTest(CompanyPgpKeyConfigController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class CompanyPgpKeyConfigControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CompanyPgpKeyConfigUseCase useCase;

    @Test
    void createReturnsOkAndNeverEchoesKeyMaterial() throws Exception {
        when(useCase.create(any()))
                .thenReturn(new CompanyPgpKeyConfig(
                        UUID.randomUUID(), "FPT_SOFTWARE", "priv-secret", "pass-secret", "pub", null, true));

        mockMvc.perform(post("/api/company-pgp-key-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyCode\":\"FPT_SOFTWARE\",\"bankPrivateKeyArmored\":\"priv-secret\","
                                + "\"bankKeyPassphrase\":\"pass-secret\",\"companyPublicKeyArmored\":\"pub\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyCode").value("FPT_SOFTWARE"))
                .andExpect(jsonPath("$.data.bankPrivateKeyArmored").doesNotExist())
                .andExpect(jsonPath("$.data.bankKeyPassphrase").doesNotExist());
    }

    @Test
    void createReturns409WhenCompanyCodeAlreadyExists() throws Exception {
        doThrow(new PgpKeyConfigAlreadyExistsException("FPT_SOFTWARE"))
                .when(useCase)
                .create(any());

        mockMvc.perform(post("/api/company-pgp-key-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyCode\":\"FPT_SOFTWARE\",\"bankPrivateKeyArmored\":\"p\","
                                + "\"bankKeyPassphrase\":\"pw\",\"companyPublicKeyArmored\":\"pub\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(useCase.getByCompanyCode("UNKNOWN_CO")).thenThrow(new PgpKeyConfigNotFoundException("UNKNOWN_CO"));

        mockMvc.perform(get("/api/company-pgp-key-configs/{companyCode}", "UNKNOWN_CO"))
                .andExpect(status().isNotFound());
    }

    @Test
    void putCallsUpdateNotDelete() throws Exception {
        when(useCase.update(eq("FPT_SOFTWARE"), any()))
                .thenReturn(
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "p2", "pw2", "pub2", null, true));

        mockMvc.perform(put("/api/company-pgp-key-configs/{companyCode}", "FPT_SOFTWARE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankPrivateKeyArmored\":\"p2\",\"bankKeyPassphrase\":\"pw2\","
                                + "\"companyPublicKeyArmored\":\"pub2\"}"))
                .andExpect(status().isOk());

        verify(useCase).update(eq("FPT_SOFTWARE"), any());
        verify(useCase, never()).delete(any());
    }

    @Test
    void deleteReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/company-pgp-key-configs/{companyCode}", "FPT_SOFTWARE"))
                .andExpect(status().isOk());

        verify(useCase).delete("FPT_SOFTWARE");
    }
}
