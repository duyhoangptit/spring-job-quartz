package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

class CompanyPgpKeyConfigServiceTest {

    private final PgpKeyConfigRepositoryPort repositoryPort = mock(PgpKeyConfigRepositoryPort.class);
    private final CompanyPgpKeyConfigService service = new CompanyPgpKeyConfigService(repositoryPort);

    @Test
    void createSavesANewActiveConfig() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.empty());
        when(repositoryPort.save(any(CompanyPgpKeyConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyPgpKeyConfig result =
                service.create(new CreateCompanyPgpKeyConfigCommand("FPT_SOFTWARE", "priv", "pass", "pub"));

        assertThat(result.companyCode()).isEqualTo("FPT_SOFTWARE");
        assertThat(result.active()).isTrue();
    }

    @Test
    void createRejectsADuplicateCompanyCode() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE"))
                .thenReturn(Optional.of(
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true)));

        assertThatThrownBy(
                        () -> service.create(new CreateCompanyPgpKeyConfigCommand("FPT_SOFTWARE", "p2", "pw2", "pub2")))
                .isInstanceOf(PgpKeyConfigAlreadyExistsException.class);
        verify(repositoryPort, never()).save(any());
    }

    @Test
    void updateReplacesKeyMaterialButKeepsTheId() {
        UUID id = UUID.randomUUID();
        CompanyPgpKeyConfig existing =
                new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v1", "pass-v1", "pub-v1", null, true);
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(existing));
        when(repositoryPort.save(any(CompanyPgpKeyConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyPgpKeyConfig result = service.update(
                "FPT_SOFTWARE", new UpdateCompanyPgpKeyConfigCommand("priv-v2", "pass-v2", "pub-v2", null));

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.bankPrivateKeyArmored()).isEqualTo("priv-v2");
        assertThat(result.active()).isTrue();
    }

    @Test
    void updateThrowsWhenMissing() {
        when(repositoryPort.findByCompanyCode("UNKNOWN_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.update("UNKNOWN_CO", new UpdateCompanyPgpKeyConfigCommand("p", "pw", "pub", null)))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }

    @Test
    void deleteDelegatesToRepositoryPort() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE"))
                .thenReturn(Optional.of(
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true)));

        service.delete("FPT_SOFTWARE");

        verify(repositoryPort).delete("FPT_SOFTWARE");
    }

    @Test
    void listReturnsEverythingFromTheRepository() {
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true);
        when(repositoryPort.findAll()).thenReturn(List.of(config));

        assertThat(service.list()).containsExactly(config);
    }
}
