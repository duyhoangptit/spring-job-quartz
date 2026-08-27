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
        // Ký bởi ĐÚNG company key (không phải key lạ) nhưng nội dung literal data thực tế bị tamper sau
        // khi ký - decrypt phải thành công (khớp key, khớp signer key ID), file phải được ghi ra đĩa,
        // rồi mới đến bước onePassSignature.verify(...) trả về false. Đây là nhánh nguy hiểm nhất: khác
        // với throwsSignatureInvalidWhenSignedByAnUnrelatedKey (ném exception TRƯỚC khi tạo file vì
        // không tìm thấy signer key), test này phải đi xuyên qua toàn bộ write-to-disk rồi mới fail ở
        // verify(), để thực sự exercise được Files.deleteIfExists(decryptedFile) ở cuối method.
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] signedPlaintext = "data".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedPlaintext = "hack".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = PgpTestFixtures.encryptAndSignMismatchedContent(
                signedPlaintext,
                tamperedPlaintext,
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
        long filesBefore = Files.list(tempDir).count();

        // Assert cụ thể reason text thay vì chỉ isInstanceOf(...) - để chứng minh test này thực sự đi
        // tới nhánh "verify() trả về false" (cuối method, SAU khi đã ghi file ra đĩa), chứ không phải
        // lỡ rơi vào nhánh "không tìm thấy signer key" (đầu method, TRƯỚC khi tạo file) như
        // throwsSignatureInvalidWhenSignedByAnUnrelatedKey.
        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, keyConfig))
                .isInstanceOf(PgpSignatureInvalidException.class)
                .extracting(e -> ((PgpSignatureInvalidException) e).getMessageArgs()[1])
                .isEqualTo("Chữ ký PGP không khớp - file có thể đã bị sửa đổi hoặc giả mạo");

        assertThat(Files.list(tempDir).count()).isEqualTo(filesBefore);
    }
}
