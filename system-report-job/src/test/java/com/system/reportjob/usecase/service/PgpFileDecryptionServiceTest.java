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
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

class PgpFileDecryptionServiceTest {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort = mock(PgpKeyConfigRepositoryPort.class);
    private final PgpDecryptionGatewayPort decryptionGatewayPort = mock(PgpDecryptionGatewayPort.class);
    private final PgpFileDecryptionService service =
            new PgpFileDecryptionService(keyConfigRepositoryPort, decryptionGatewayPort);

    @Test
    void decryptsUsingTheActiveKeyConfigForTheCompany() {
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(config));
        Path decrypted = Path.of("/tmp/decrypted.csv");
        when(decryptionGatewayPort.decryptAndVerify(any(), any())).thenReturn(decrypted);

        Path result = service.decryptFile("FPT_SOFTWARE", Path.of("/tmp/encrypted.csv.pgp"));

        assertThat(result).isEqualTo(decrypted);
        verify(decryptionGatewayPort).decryptAndVerify(Path.of("/tmp/encrypted.csv.pgp"), config);
    }

    @Test
    void throwsWhenNoKeyConfigExistsForTheCompany() {
        when(keyConfigRepositoryPort.findByCompanyCode("UNKNOWN_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decryptFile("UNKNOWN_CO", Path.of("/tmp/x.pgp")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }

    @Test
    void throwsWhenTheKeyConfigIsInactive() {
        CompanyPgpKeyConfig inactiveConfig =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, false);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(inactiveConfig));

        assertThatThrownBy(() -> service.decryptFile("FPT_SOFTWARE", Path.of("/tmp/x.pgp")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }
}
