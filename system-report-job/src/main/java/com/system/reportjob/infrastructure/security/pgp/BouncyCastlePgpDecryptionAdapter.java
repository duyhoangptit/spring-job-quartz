package com.system.reportjob.infrastructure.security.pgp;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.UUID;

import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.exception.PgpDecryptionFailedException;
import com.system.reportjob.domain.exception.PgpSignatureInvalidException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;

/**
 * Decrypt + verify chữ ký file PGP bằng Bouncy Castle, dùng API lightweight thuần
 * org.bouncycastle.openpgp.operator.bc.* (không cần đăng ký JCE Security provider). Traversal dựa
 * trên pattern chuẩn org.bouncycastle.openpgp.examples.KeyBasedFileProcessor, với verify signature
 * BẮT BUỘC (xem docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md, Section 5).
 */
@Component
public class BouncyCastlePgpDecryptionAdapter implements PgpDecryptionGatewayPort {

    private final Path tempDir;

    public BouncyCastlePgpDecryptionAdapter(@Value("${app.pgp.temp-dir:${java.io.tmpdir}}") String tempDir) {
        this.tempDir = Path.of(tempDir);
    }

    @Override
    public Path decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig) {
        String companyCode = keyConfig.companyCode();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(encryptedFile))) {
            PGPObjectFactory pgpFactory =
                    new PGPObjectFactory(PGPUtil.getDecoderStream(fileIn), new BcKeyFingerprintCalculator());

            Object firstObject = pgpFactory.nextObject();
            PGPEncryptedDataList encryptedDataList = firstObject instanceof PGPEncryptedDataList list
                    ? list
                    : (PGPEncryptedDataList) pgpFactory.nextObject();

            PGPSecretKeyRingCollection secretKeyRings = new PGPSecretKeyRingCollection(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.bankPrivateKeyArmored())),
                    new BcKeyFingerprintCalculator());

            PGPPrivateKey privateKey = null;
            PGPPublicKeyEncryptedData encryptedData = null;
            Iterator<PGPEncryptedData> encryptedObjects = encryptedDataList.getEncryptedDataObjects();
            while (privateKey == null && encryptedObjects.hasNext()) {
                PGPPublicKeyEncryptedData candidate = (PGPPublicKeyEncryptedData) encryptedObjects.next();
                PGPSecretKey secretKey = secretKeyRings.getSecretKey(candidate.getKeyID());
                if (secretKey != null) {
                    privateKey = secretKey.extractPrivateKey(
                            new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
                                    .build(keyConfig.bankKeyPassphrase().toCharArray()));
                    encryptedData = candidate;
                }
            }
            if (privateKey == null || encryptedData == null) {
                throw new PgpDecryptionFailedException(
                        companyCode, "Không tìm thấy private key khớp với file (key ID không trùng)");
            }

            InputStream clearStream = encryptedData.getDataStream(new BcPublicKeyDataDecryptorFactory(privateKey));
            PGPObjectFactory plainFactory = new PGPObjectFactory(clearStream, new BcKeyFingerprintCalculator());
            Object message = plainFactory.nextObject();
            if (message instanceof PGPCompressedData compressedData) {
                plainFactory = new PGPObjectFactory(compressedData.getDataStream(), new BcKeyFingerprintCalculator());
                message = plainFactory.nextObject();
            }

            if (!(message instanceof PGPOnePassSignatureList onePassSignatureList) || onePassSignatureList.isEmpty()) {
                throw new PgpSignatureInvalidException(
                        companyCode, "File không có chữ ký PGP đi kèm (thiếu one-pass signature)");
            }
            PGPOnePassSignature onePassSignature = onePassSignatureList.get(0);

            PGPPublicKeyRingCollection publicKeyRings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.companyPublicKeyArmored())),
                    new BcKeyFingerprintCalculator());
            PGPPublicKey signerKey = publicKeyRings.getPublicKey(onePassSignature.getKeyID());
            if (signerKey == null) {
                throw new PgpSignatureInvalidException(
                        companyCode, "Không tìm thấy public key của company để verify chữ ký (key ID không trùng)");
            }
            onePassSignature.init(new BcPGPContentVerifierBuilderProvider(), signerKey);

            message = plainFactory.nextObject();
            if (!(message instanceof PGPLiteralData literalData)) {
                throw new PgpDecryptionFailedException(companyCode, "Định dạng PGP không hợp lệ: thiếu literal data");
            }

            Files.createDirectories(tempDir);
            Path decryptedFile = tempDir.resolve(UUID.randomUUID() + ".decrypted");
            // Từ đây trở đi file đã được tạo trên đĩa (chứa dữ liệu payroll/PII chưa được verify) - BẮT
            // BUỘC phải xoá file này ở MỌI exception path phía dưới, không được để sót branch nào. Dùng
            // catch(Exception) cấu trúc thay vì rải Files.deleteIfExists() ở từng nhánh throw riêng lẻ,
            // để không phụ thuộc vào việc nhớ đủ mọi nhánh lỗi (IOException khi ghi file, khi set
            // permission, khi đọc signature packet bị corrupt, v.v).
            try {
                writeDecryptedFileAndVerifySignature(
                        plainFactory, literalData, onePassSignature, decryptedFile, companyCode);
            } catch (RuntimeException | IOException | PGPException e) {
                deleteQuietly(decryptedFile);
                throw e;
            }
            return decryptedFile;
        } catch (IOException | PGPException e) {
            throw new PgpDecryptionFailedException(companyCode, e.getMessage());
        }
    }

    /**
     * Ghi literal data đã decrypt ra {@code decryptedFile} rồi verify chữ ký PGP đi kèm. Không tự xoá
     * file khi lỗi - việc xoá file khi thất bại là trách nhiệm của caller ({@link #decryptAndVerify}),
     * để đảm bảo MỌI exception (kể cả IOException khi ghi file/đọc signature packet corrupt) đều dẫn
     * đến cleanup, không chỉ những nhánh throw tường minh trong method này.
     */
    private static void writeDecryptedFileAndVerifySignature(
            PGPObjectFactory plainFactory,
            PGPLiteralData literalData,
            PGPOnePassSignature onePassSignature,
            Path decryptedFile,
            String companyCode)
            throws IOException, PGPException {
        try (InputStream literalIn = literalData.getInputStream();
                OutputStream fileOut = Files.newOutputStream(decryptedFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = literalIn.read(buffer)) >= 0) {
                onePassSignature.update(buffer, 0, read);
                fileOut.write(buffer, 0, read);
            }
        }
        setOwnerOnlyPermissionsIfSupported(decryptedFile);

        Object signatureObject = plainFactory.nextObject();
        if (!(signatureObject instanceof PGPSignatureList signatureList) || signatureList.isEmpty()) {
            throw new PgpSignatureInvalidException(
                    companyCode, "File không có chữ ký PGP hợp lệ đi kèm (thiếu signature packet)");
        }
        PGPSignature signature = signatureList.get(0);
        boolean verified;
        try {
            verified = onePassSignature.verify(signature);
        } catch (PGPException e) {
            throw new PgpSignatureInvalidException(companyCode, "Lỗi khi verify chữ ký: " + e.getMessage());
        }
        if (!verified) {
            throw new PgpSignatureInvalidException(
                    companyCode, "Chữ ký PGP không khớp - file có thể đã bị sửa đổi hoặc giả mạo");
        }
    }

    private static InputStream armoredStream(String armored) {
        return new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII));
    }

    private static void setOwnerOnlyPermissionsIfSupported(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Hệ điều hành không hỗ trợ POSIX permission (vd Windows) - bỏ qua, không phải lỗi.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Không được để lỗi cleanup che khuất exception chính (decrypt/verify) đang được ném ra.
        }
    }
}
