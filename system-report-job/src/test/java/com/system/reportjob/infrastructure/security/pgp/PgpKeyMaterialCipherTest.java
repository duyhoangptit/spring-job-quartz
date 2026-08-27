package com.system.reportjob.infrastructure.security.pgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class PgpKeyMaterialCipherTest {

    private static final String MASTER_KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void sealThenUnsealReturnsOriginalPlaintext() {
        PgpKeyMaterialCipher cipher = new PgpKeyMaterialCipher(MASTER_KEY_BASE64);

        String sealed = cipher.seal("-----BEGIN PGP PRIVATE KEY BLOCK-----\nsecret\n-----END-----");

        assertThat(sealed).doesNotContain("secret");
        assertThat(cipher.unseal(sealed)).isEqualTo("-----BEGIN PGP PRIVATE KEY BLOCK-----\nsecret\n-----END-----");
    }

    @Test
    void sealIsNonDeterministic() {
        PgpKeyMaterialCipher cipher = new PgpKeyMaterialCipher(MASTER_KEY_BASE64);

        assertThat(cipher.seal("same-plaintext")).isNotEqualTo(cipher.seal("same-plaintext"));
    }

    @Test
    void rejectsMasterKeyThatIsNot32BytesAfterDecoding() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PgpKeyMaterialCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
