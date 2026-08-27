package com.system.reportjob.infrastructure.security.pgp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.exception.PgpEncryptionFailedException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.out.PgpEncryptionGatewayPort;

/**
 * Encrypt + sign file PGP để gửi cho company, bằng Bouncy Castle, dùng API lightweight thuần
 * org.bouncycastle.openpgp.operator.bc.* (đối xứng với {@link BouncyCastlePgpDecryptionAdapter}).
 * Encrypt cho {@code companyPublicKeyArmored} (recipient), sign bằng {@code bankPrivateKeyArmored}
 * (signer) - dùng đúng key material đã có trong {@link CompanyPgpKeyConfig}, không cần thêm cột nào
 * dù đây là chiều ngược lại với decrypt: private key của ngân hàng vừa dùng để decrypt file company
 * gửi tới, vừa dùng để sign file ngân hàng gửi đi; public key của company vừa dùng để verify chữ ký
 * họ, vừa dùng để encrypt file gửi cho họ.
 */
@Component
public class BouncyCastlePgpEncryptionAdapter implements PgpEncryptionGatewayPort {

    private final Path tempDir;

    public BouncyCastlePgpEncryptionAdapter(@Value("${app.pgp.temp-dir:${java.io.tmpdir}}") String tempDir) {
        this.tempDir = Path.of(tempDir);
    }

    @Override
    public Path encryptAndSign(Path plaintextFile, CompanyPgpKeyConfig keyConfig) {
        String companyCode = keyConfig.companyCode();
        try {
            PGPSecretKeyRing signerKeyRing = new PGPSecretKeyRing(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.bankPrivateKeyArmored())),
                    new BcKeyFingerprintCalculator());
            PGPSecretKey signerSecretKey = signerKeyRing.getSecretKey();
            PGPPrivateKey signerPrivateKey = signerSecretKey.extractPrivateKey(
                    new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
                            .build(keyConfig.bankKeyPassphrase().toCharArray()));

            PGPPublicKeyRing recipientKeyRing = new PGPPublicKeyRing(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.companyPublicKeyArmored())),
                    new BcKeyFingerprintCalculator());
            PGPPublicKey recipientEncryptionKey = recipientKeyRing.getPublicKey();

            PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(new BcPGPContentSignerBuilder(
                    signerSecretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
            signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, signerPrivateKey);

            byte[] plaintext = Files.readAllBytes(plaintextFile);
            String literalFileName = plaintextFile.getFileName().toString();

            Files.createDirectories(tempDir);
            Path encryptedFile = tempDir.resolve(UUID.randomUUID() + ".pgp");

            PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                    new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true));
            encryptedDataGenerator.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(recipientEncryptionKey));

            try (OutputStream fileOut = Files.newOutputStream(encryptedFile);
                    ArmoredOutputStream armoredOut = new ArmoredOutputStream(fileOut);
                    OutputStream cipherOut = encryptedDataGenerator.open(armoredOut, new byte[1 << 16])) {
                PGPCompressedDataGenerator compressedDataGenerator =
                        new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
                try (OutputStream compressedOut = compressedDataGenerator.open(cipherOut)) {
                    signatureGenerator.generateOnePassVersion(false).encode(compressedOut);

                    PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
                    try (OutputStream literalOut = literalDataGenerator.open(
                            compressedOut, PGPLiteralData.BINARY, literalFileName, plaintext.length, new Date())) {
                        literalOut.write(plaintext);
                        signatureGenerator.update(plaintext);
                    }
                    signatureGenerator.generate().encode(compressedOut);
                }
            }
            return encryptedFile;
        } catch (IOException | PGPException e) {
            throw new PgpEncryptionFailedException(companyCode, e.getMessage());
        }
    }

    private static InputStream armoredStream(String armored) {
        return new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII));
    }
}
