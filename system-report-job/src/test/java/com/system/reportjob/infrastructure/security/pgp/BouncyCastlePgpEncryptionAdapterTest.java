package com.system.reportjob.infrastructure.security.pgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.system.reportjob.domain.exception.PgpEncryptionFailedException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.security.pgp.PgpTestFixtures.PgpKeyPairArmored;

class BouncyCastlePgpEncryptionAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsAndSignsAFileThatTheOtherSideCanDecryptAndVerifyBack() throws Exception {
        // "ngân hàng" và "company" mỗi bên tự sinh 1 cặp key riêng - đúng thực tế 2 bên có 2 cặp
        // key khác nhau, không dùng chung.
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] plaintext = "employeeId,accountNumber,fullName,confirmedAmount\nE1,123,Nguyen Van A,1000000\n"
                .getBytes(StandardCharsets.UTF_8);
        Path plaintextFile = tempDir.resolve("payroll-confirmation.csv");
        Files.write(plaintextFile, plaintext);

        // keyConfig ở chiều gửi đi: bank_private_key = private key ngân hàng (để SIGN),
        // company_public_key = public key company (để ENCRYPT cho họ) - cùng 1 dòng
        // company_pgp_key_config như chiều decrypt, không cần thêm cột nào.
        CompanyPgpKeyConfig outboundKeyConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpEncryptionAdapter encryptionAdapter = new BouncyCastlePgpEncryptionAdapter(tempDir.toString());

        Path encryptedFile = encryptionAdapter.encryptAndSign(plaintextFile, outboundKeyConfig);

        assertThat(encryptedFile).exists();
        assertThat(Files.readString(encryptedFile)).contains("BEGIN PGP MESSAGE");

        // Verify bằng cách đảo vai: company nhận file này phải decrypt được bằng private key của
        // CHÍNH HỌ, và verify được chữ ký bằng public key của NGÂN HÀNG - dùng luôn
        // BouncyCastlePgpDecryptionAdapter (đã có, đã test) đóng vai "company".
        CompanyPgpKeyConfig inboundKeyConfigForCompany = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                companyKeyPair.secretKeyArmored(),
                "company-pass",
                bankKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter decryptionAdapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        Path decryptedFile = decryptionAdapter.decryptAndVerify(encryptedFile, inboundKeyConfigForCompany);

        assertThat(Files.readAllBytes(decryptedFile)).isEqualTo(plaintext);
    }

    @Test
    void throwsPgpEncryptionFailedWhenTheBankPassphraseIsWrong() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        Path plaintextFile = tempDir.resolve("payroll-confirmation.csv");
        Files.writeString(plaintextFile, "data");
        CompanyPgpKeyConfig wrongPassphraseConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "WRONG-PASSPHRASE",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpEncryptionAdapter encryptionAdapter = new BouncyCastlePgpEncryptionAdapter(tempDir.toString());

        assertThatThrownBy(() -> encryptionAdapter.encryptAndSign(plaintextFile, wrongPassphraseConfig))
                .isInstanceOf(PgpEncryptionFailedException.class);
    }
}
