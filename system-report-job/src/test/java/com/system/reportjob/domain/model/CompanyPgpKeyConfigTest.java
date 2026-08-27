package com.system.reportjob.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CompanyPgpKeyConfigTest {

    @Test
    void rejectsBlankCompanyCode() {
        assertThatThrownBy(() -> new CompanyPgpKeyConfig(UUID.randomUUID(), " ", "priv", "pass", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankBankPrivateKey() {
        assertThatThrownBy(() ->
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", " ", "pass", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPassphrase() {
        assertThatThrownBy(() ->
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", " ", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankCompanyPublicKey() {
        assertThatThrownBy(() ->
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", " ", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValidValues() {
        UUID id = UUID.randomUUID();
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv", "pass", "pub", "AB12", true);

        assertThat(config.id()).isEqualTo(id);
        assertThat(config.active()).isTrue();
    }
}
