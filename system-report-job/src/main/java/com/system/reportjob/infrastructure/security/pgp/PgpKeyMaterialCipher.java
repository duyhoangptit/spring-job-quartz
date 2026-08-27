package com.system.reportjob.infrastructure.security.pgp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seal/unseal PGP private key + passphrase trước khi lưu DB, dùng AES-256-GCM với 1 master key đọc
 * từ app.pgp.master-key. Không dùng cho company_public_key_armored (không nhạy cảm). Xem
 * docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md, Section 5.
 */
@Component
public class PgpKeyMaterialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PgpKeyMaterialCipher(@Value("${app.pgp.master-key}") String masterKeyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("app.pgp.master-key phải là 256-bit (32 byte) sau khi decode base64,"
                    + " hiện tại là " + keyBytes.length + " byte");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không seal được PGP key material", e);
        }
    }

    public String unseal(String sealedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(sealedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            byte[] ciphertext = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không unseal được PGP key material", e);
        }
    }
}
