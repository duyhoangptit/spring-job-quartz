package com.system.reportjob.infrastructure.security.pgp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

/**
 * Sinh key pair PGP test và encrypt+sign một payload - mô phỏng phía "company" (họ encrypt bằng
 * public key ngân hàng, sign bằng private key của họ) để test
 * {@link BouncyCastlePgpDecryptionAdapter} (đóng vai "ngân hàng", decrypt + verify). Chỉ dùng
 * trong test, không phải production code.
 */
final class PgpTestFixtures {

    private PgpTestFixtures() {}

    record PgpKeyPairArmored(String publicKeyArmored, String secretKeyArmored) {}

    static PgpKeyPairArmored generateKeyPair(String userId, char[] passphrase) throws Exception {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 80));
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();
        PGPKeyPair pgpKeyPair = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, new Date());

        PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PBESecretKeyEncryptor keyEncryptor =
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc).build(passphrase);

        PGPKeyRingGenerator keyRingGenerator = new PGPKeyRingGenerator(
                org.bouncycastle.openpgp.PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                sha1Calc,
                null,
                null,
                new BcPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                keyEncryptor);

        return new PgpKeyPairArmored(
                armor(keyRingGenerator.generatePublicKeyRing().getEncoded()),
                armor(keyRingGenerator.generateSecretKeyRing().getEncoded()));
    }

    static byte[] encryptAndSign(
            byte[] plaintext,
            String fileName,
            String signerSecretKeyArmored,
            char[] signerPassphrase,
            String recipientPublicKeyArmored)
            throws Exception {
        PGPSecretKeyRing signerKeyRing = new PGPSecretKeyRing(
                PGPUtil.getDecoderStream(armoredStream(signerSecretKeyArmored)), new BcKeyFingerprintCalculator());
        PGPSecretKey signerSecretKey = signerKeyRing.getSecretKey();
        PGPPrivateKey signerPrivateKey = signerSecretKey.extractPrivateKey(
                new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(signerPassphrase));

        PGPPublicKeyRing recipientKeyRing = new PGPPublicKeyRing(
                PGPUtil.getDecoderStream(armoredStream(recipientPublicKeyArmored)), new BcKeyFingerprintCalculator());
        PGPPublicKey recipientEncryptionKey = recipientKeyRing.getPublicKey();

        PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(signerSecretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
        signatureGenerator.init(org.bouncycastle.openpgp.PGPSignature.BINARY_DOCUMENT, signerPrivateKey);

        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
        PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true));
        encryptedDataGenerator.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(recipientEncryptionKey));

        try (ArmoredOutputStream armoredOut = new ArmoredOutputStream(encryptedOut);
                OutputStream cipherOut = encryptedDataGenerator.open(armoredOut, new byte[1 << 16])) {
            PGPCompressedDataGenerator compressedDataGenerator =
                    new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
            try (OutputStream compressedOut = compressedDataGenerator.open(cipherOut)) {
                signatureGenerator.generateOnePassVersion(false).encode(compressedOut);

                PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
                try (OutputStream literalOut = literalDataGenerator.open(
                        compressedOut, PGPLiteralData.BINARY, fileName, plaintext.length, new Date())) {
                    literalOut.write(plaintext);
                    signatureGenerator.update(plaintext);
                }
                signatureGenerator.generate().encode(compressedOut);
            }
        }
        return encryptedOut.toByteArray();
    }

    private static String armor(byte[] encoded) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
            armored.write(encoded);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static ByteArrayInputStream armoredStream(String armored) {
        return new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII));
    }
}
