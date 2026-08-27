package com.system.reportjob.infrastructure.security.pgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.system.reportjob.domain.exception.PgpDecryptionFailedException;
import com.system.reportjob.domain.exception.PgpSignatureInvalidException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.security.pgp.PgpTestFixtures.PgpKeyPairArmored;

class BouncyCastlePgpDecryptionAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void decryptsAndVerifiesAFileEncryptedAndSignedByTheCompany() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] plaintext = "employeeId,accountNumber,fullName,salaryAmount\nE1,123,Nguyen Van A,1000000\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                plaintext,
                "payroll.csv",
                companyKeyPair.secretKeyArmored(),
                "company-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        Path decryptedFile = adapter.decryptAndVerify(encryptedFile, keyConfig);

        assertThat(Files.readAllBytes(decryptedFile)).isEqualTo(plaintext);
    }

    @Test
    void throwsDecryptionFailedWhenThePassphraseIsWrong() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                companyKeyPair.secretKeyArmored(),
                "company-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig wrongPassphraseConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "WRONG-PASSPHRASE",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, wrongPassphraseConfig))
                .isInstanceOf(PgpDecryptionFailedException.class);
    }

    @Test
    void throwsSignatureInvalidWhenSignedByAnUnrelatedKey() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        PgpKeyPairArmored impostorKeyPair =
                PgpTestFixtures.generateKeyPair("impostor@evil.test", "impostor-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                impostorKeyPair.secretKeyArmored(),
                "impostor-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfigExpectingCompanyKey = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, keyConfigExpectingCompanyKey))
                .isInstanceOf(PgpSignatureInvalidException.class);
    }

    @Test
    void deletesTheDecryptedFileWhenSignatureVerificationFails() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored impostorKeyPair =
                PgpTestFixtures.generateKeyPair("impostor@evil.test", "impostor-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                impostorKeyPair.secretKeyArmored(),
                "impostor-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());
        long filesBefore = Files.list(tempDir).count();

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, keyConfig))
                .isInstanceOf(PgpSignatureInvalidException.class);

        assertThat(Files.list(tempDir).count()).isEqualTo(filesBefore);
    }
}
