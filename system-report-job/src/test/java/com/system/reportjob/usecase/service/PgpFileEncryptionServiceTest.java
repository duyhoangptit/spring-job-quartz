package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.out.PgpEncryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

class PgpFileEncryptionServiceTest {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort = mock(PgpKeyConfigRepositoryPort.class);
    private final PgpEncryptionGatewayPort encryptionGatewayPort = mock(PgpEncryptionGatewayPort.class);
    private final PgpFileEncryptionService service =
            new PgpFileEncryptionService(keyConfigRepositoryPort, encryptionGatewayPort);

    @Test
    void encryptsUsingTheActiveKeyConfigForTheCompany() {
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(config));
        Path encrypted = Path.of("/tmp/encrypted.csv.pgp");
        when(encryptionGatewayPort.encryptAndSign(any(), any())).thenReturn(encrypted);

        Path result = service.encryptFile("FPT_SOFTWARE", Path.of("/tmp/plaintext.csv"));

        assertThat(result).isEqualTo(encrypted);
        verify(encryptionGatewayPort).encryptAndSign(Path.of("/tmp/plaintext.csv"), config);
    }

    @Test
    void throwsWhenNoKeyConfigExistsForTheCompany() {
        when(keyConfigRepositoryPort.findByCompanyCode("UNKNOWN_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.encryptFile("UNKNOWN_CO", Path.of("/tmp/x.csv")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }

    @Test
    void throwsWhenTheKeyConfigIsInactive() {
        CompanyPgpKeyConfig inactiveConfig =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, false);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(inactiveConfig));

        assertThatThrownBy(() -> service.encryptFile("FPT_SOFTWARE", Path.of("/tmp/x.csv")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }
}
