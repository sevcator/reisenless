package com.topjohnwu.magisk.core.signing;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;








public abstract class ApkSignerV2 {

















    public static final int SIGNATURE_RSA_PSS_WITH_SHA256 = 0x0101;
    public static final int SIGNATURE_RSA_PSS_WITH_SHA512 = 0x0102;
    public static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    public static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
    public static final int SIGNATURE_ECDSA_WITH_SHA256 = 0x0201;
    public static final int SIGNATURE_ECDSA_WITH_SHA512 = 0x0202;
    public static final int SIGNATURE_DSA_WITH_SHA256 = 0x0301;
    public static final int SIGNATURE_DSA_WITH_SHA512 = 0x0302;








    public static final String SF_ATTRIBUTE_ANDROID_APK_SIGNED_NAME = "X-Android-APK-Signed";
    public static final String SF_ATTRIBUTE_ANDROID_APK_SIGNED_VALUE = "2";

    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 0;
    private static final int CONTENT_DIGEST_CHUNKED_SHA512 = 1;

    private static final int CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES = 1024 * 1024;

    private static final byte[] APK_SIGNING_BLOCK_MAGIC =
          new byte[] {
              0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
              0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32,
          };
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;

    private ApkSignerV2() {}




    public static final class SignerConfig {

        public PrivateKey privateKey;





        public List<X509Certificate> certificates;




        public List<Integer> signatureAlgorithms;
    }



















    public static ByteBuffer[] sign(
            ByteBuffer inputApk,
            List<SignerConfig> signerConfigs)
                    throws ApkParseException, InvalidKeyException, SignatureException {



        ByteBuffer originalInputApk = inputApk;
        inputApk = originalInputApk.slice();
        inputApk.order(ByteOrder.LITTLE_ENDIAN);



        int eocdOffset = ZipUtils.findZipEndOfCentralDirectoryRecord(inputApk);
        if (eocdOffset == -1) {
            throw new ApkParseException("Failed to locate ZIP End of Central Directory");
        }
        if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(inputApk, eocdOffset)) {
            throw new ApkParseException("ZIP64 format not supported");
        }
        inputApk.position(eocdOffset);
        long centralDirSizeLong = ZipUtils.getZipEocdCentralDirectorySizeBytes(inputApk);
        if (centralDirSizeLong > Integer.MAX_VALUE) {
            throw new ApkParseException(
                    "ZIP Central Directory size out of range: " + centralDirSizeLong);
        }
        int centralDirSize = (int) centralDirSizeLong;
        long centralDirOffsetLong = ZipUtils.getZipEocdCentralDirectoryOffset(inputApk);
        if (centralDirOffsetLong > Integer.MAX_VALUE) {
            throw new ApkParseException(
                    "ZIP Central Directory offset in file out of range: " + centralDirOffsetLong);
        }
        int centralDirOffset = (int) centralDirOffsetLong;
        int expectedEocdOffset = centralDirOffset + centralDirSize;
        if (expectedEocdOffset < centralDirOffset) {
            throw new ApkParseException(
                    "ZIP Central Directory extent too large. Offset: " + centralDirOffset
                            + ", size: " + centralDirSize);
        }
        if (eocdOffset != expectedEocdOffset) {
            throw new ApkParseException(
                    "ZIP Central Directory not immeiately followed by ZIP End of"
                            + " Central Directory. CD end: " + expectedEocdOffset
                            + ", EoCD start: " + eocdOffset);
        }



        inputApk.clear();
        ByteBuffer beforeCentralDir = getByteBuffer(inputApk, centralDirOffset);
        ByteBuffer centralDir = getByteBuffer(inputApk, eocdOffset - centralDirOffset);

        byte[] eocdBytes = new byte[inputApk.remaining()];
        inputApk.get(eocdBytes);
        ByteBuffer eocd = ByteBuffer.wrap(eocdBytes);
        eocd.order(inputApk.order());


        Set<Integer> contentDigestAlgorithms = new HashSet<>();
        for (SignerConfig signerConfig : signerConfigs) {
            for (int signatureAlgorithm : signerConfig.signatureAlgorithms) {
                contentDigestAlgorithms.add(
                        getSignatureAlgorithmContentDigestAlgorithm(signatureAlgorithm));
            }
        }


        Map<Integer, byte[]> contentDigests;
        try {
            contentDigests =
                    computeContentDigests(
                            contentDigestAlgorithms,
                            new ByteBuffer[] {beforeCentralDir, centralDir, eocd});
        } catch (DigestException e) {
            throw new SignatureException("Failed to compute digests of APK", e);
        }


        ByteBuffer apkSigningBlock =
                ByteBuffer.wrap(generateApkSigningBlock(signerConfigs, contentDigests));



        centralDirOffset += apkSigningBlock.remaining();
        eocd.clear();
        ZipUtils.setZipEocdCentralDirectoryOffset(eocd, centralDirOffset);


        originalInputApk.position(originalInputApk.limit());




        beforeCentralDir.clear();
        centralDir.clear();
        eocd.clear();


        return new ByteBuffer[] {
            beforeCentralDir,
            apkSigningBlock,
            centralDir,
            eocd,
        };
    }

    private static Map<Integer, byte[]> computeContentDigests(
            Set<Integer> digestAlgorithms,
            ByteBuffer[] contents) throws DigestException {










        int chunkCount = 0;
        for (ByteBuffer input : contents) {
            chunkCount += getChunkCount(input.remaining(), CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES);
        }

        final Map<Integer, byte[]> digestsOfChunks = new HashMap<>(digestAlgorithms.size());
        for (int digestAlgorithm : digestAlgorithms) {
            int digestOutputSizeBytes = getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
            byte[] concatenationOfChunkCountAndChunkDigests =
                    new byte[5 + chunkCount * digestOutputSizeBytes];
            concatenationOfChunkCountAndChunkDigests[0] = 0x5a;
            setUnsignedInt32LittleEngian(
                    chunkCount, concatenationOfChunkCountAndChunkDigests, 1);
            digestsOfChunks.put(digestAlgorithm, concatenationOfChunkCountAndChunkDigests);
        }

        int chunkIndex = 0;
        byte[] chunkContentPrefix = new byte[5];
        chunkContentPrefix[0] = (byte) 0xa5;

        for (ByteBuffer input : contents) {
            while (input.hasRemaining()) {
                int chunkSize =
                        Math.min(input.remaining(), CONTENT_DIGESTED_CHUNK_MAX_SIZE_BYTES);
                final ByteBuffer chunk = getByteBuffer(input, chunkSize);
                for (int digestAlgorithm : digestAlgorithms) {
                    String jcaAlgorithmName =
                            getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm);
                    MessageDigest md;
                    try {
                        md = MessageDigest.getInstance(jcaAlgorithmName);
                    } catch (NoSuchAlgorithmException e) {
                        throw new DigestException(
                                jcaAlgorithmName + " MessageDigest not supported", e);
                    }



                    chunk.clear();
                    setUnsignedInt32LittleEngian(chunk.remaining(), chunkContentPrefix, 1);
                    md.update(chunkContentPrefix);
                    md.update(chunk);
                    byte[] concatenationOfChunkCountAndChunkDigests =
                            digestsOfChunks.get(digestAlgorithm);
                    int expectedDigestSizeBytes =
                            getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
                    int actualDigestSizeBytes =
                            md.digest(
                                    concatenationOfChunkCountAndChunkDigests,
                                    5 + chunkIndex * expectedDigestSizeBytes,
                                    expectedDigestSizeBytes);
                    if (actualDigestSizeBytes != expectedDigestSizeBytes) {
                        throw new DigestException(
                                "Unexpected output size of " + md.getAlgorithm()
                                        + " digest: " + actualDigestSizeBytes);
                    }
                }
                chunkIndex++;
            }
        }

        Map<Integer, byte[]> result = new HashMap<>(digestAlgorithms.size());
        for (Map.Entry<Integer, byte[]> entry : digestsOfChunks.entrySet()) {
            int digestAlgorithm = entry.getKey();
            byte[] concatenationOfChunkCountAndChunkDigests = entry.getValue();
            String jcaAlgorithmName = getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm);
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(jcaAlgorithmName);
            } catch (NoSuchAlgorithmException e) {
                throw new DigestException(jcaAlgorithmName + " MessageDigest not supported", e);
            }
            result.put(digestAlgorithm, md.digest(concatenationOfChunkCountAndChunkDigests));
        }
        return result;
    }

    private static int getChunkCount(int inputSize, int chunkSize) {
        return (inputSize + chunkSize - 1) / chunkSize;
    }

    private static void setUnsignedInt32LittleEngian(int value, byte[] result, int offset) {
        result[offset] = (byte) (value & 0xff);
        result[offset + 1] = (byte) ((value >> 8) & 0xff);
        result[offset + 2] = (byte) ((value >> 16) & 0xff);
        result[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static byte[] generateApkSigningBlock(
            List<SignerConfig> signerConfigs,
            Map<Integer, byte[]> contentDigests) throws InvalidKeyException, SignatureException {
        byte[] apkSignatureSchemeV2Block =
                generateApkSignatureSchemeV2Block(signerConfigs, contentDigests);
        return generateApkSigningBlock(apkSignatureSchemeV2Block);
    }

    private static byte[] generateApkSigningBlock(byte[] apkSignatureSchemeV2Block) {









        int resultSize =
                8
                + 8 + 4 + apkSignatureSchemeV2Block.length
                + 8
                + 16
                ;
        ByteBuffer result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        long blockSizeFieldValue = resultSize - 8;
        result.putLong(blockSizeFieldValue);

        long pairSizeFieldValue = 4 + apkSignatureSchemeV2Block.length;
        result.putLong(pairSizeFieldValue);
        result.putInt(APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
        result.put(apkSignatureSchemeV2Block);

        result.putLong(blockSizeFieldValue);
        result.put(APK_SIGNING_BLOCK_MAGIC);

        return result.array();
    }

    private static byte[] generateApkSignatureSchemeV2Block(
            List<SignerConfig> signerConfigs,
            Map<Integer, byte[]> contentDigests) throws InvalidKeyException, SignatureException {



        List<byte[]> signerBlocks = new ArrayList<>(signerConfigs.size());
        int signerNumber = 0;
        for (SignerConfig signerConfig : signerConfigs) {
            signerNumber++;
            byte[] signerBlock;
            try {
                signerBlock = generateSignerBlock(signerConfig, contentDigests);
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Signer #" + signerNumber + " failed", e);
            } catch (SignatureException e) {
                throw new SignatureException("Signer #" + signerNumber + " failed", e);
            }
            signerBlocks.add(signerBlock);
        }

        return encodeAsSequenceOfLengthPrefixedElements(
                new byte[][] {
                    encodeAsSequenceOfLengthPrefixedElements(signerBlocks),
                });
    }

    private static byte[] generateSignerBlock(
            SignerConfig signerConfig,
            Map<Integer, byte[]> contentDigests) throws InvalidKeyException, SignatureException {
        if (signerConfig.certificates.isEmpty()) {
            throw new SignatureException("No certificates configured for signer");
        }
        PublicKey publicKey = signerConfig.certificates.get(0).getPublicKey();

        byte[] encodedPublicKey = encodePublicKey(publicKey);

        V2SignatureSchemeBlock.SignedData signedData = new V2SignatureSchemeBlock.SignedData();
        try {
            signedData.certificates = encodeCertificates(signerConfig.certificates);
        } catch (CertificateEncodingException e) {
            throw new SignatureException("Failed to encode certificates", e);
        }

        List<Pair<Integer, byte[]>> digests =
                new ArrayList<>(signerConfig.signatureAlgorithms.size());
        for (int signatureAlgorithm : signerConfig.signatureAlgorithms) {
            int contentDigestAlgorithm =
                    getSignatureAlgorithmContentDigestAlgorithm(signatureAlgorithm);
            byte[] contentDigest = contentDigests.get(contentDigestAlgorithm);
            if (contentDigest == null) {
                throw new RuntimeException(
                        getContentDigestAlgorithmJcaDigestAlgorithm(contentDigestAlgorithm)
                        + " content digest for "
                        + getSignatureAlgorithmJcaSignatureAlgorithm(signatureAlgorithm)
                        + " not computed");
            }
            digests.add(Pair.create(signatureAlgorithm, contentDigest));
        }
        signedData.digests = digests;

        V2SignatureSchemeBlock.Signer signer = new V2SignatureSchemeBlock.Signer();









        signer.signedData = encodeAsSequenceOfLengthPrefixedElements(new byte[][] {
            encodeAsSequenceOfLengthPrefixedPairsOfIntAndLengthPrefixedBytes(signedData.digests),
            encodeAsSequenceOfLengthPrefixedElements(signedData.certificates),

            new byte[0],
        });
        signer.publicKey = encodedPublicKey;
        signer.signatures = new ArrayList<>();
        for (int signatureAlgorithm : signerConfig.signatureAlgorithms) {
            Pair<String, ? extends AlgorithmParameterSpec> signatureParams =
                    getSignatureAlgorithmJcaSignatureAlgorithm(signatureAlgorithm);
            String jcaSignatureAlgorithm = signatureParams.getFirst();
            AlgorithmParameterSpec jcaSignatureAlgorithmParams = signatureParams.getSecond();
            byte[] signatureBytes;
            try {
                Signature signature = Signature.getInstance(jcaSignatureAlgorithm);
                signature.initSign(signerConfig.privateKey);
                if (jcaSignatureAlgorithmParams != null) {
                    signature.setParameter(jcaSignatureAlgorithmParams);
                }
                signature.update(signer.signedData);
                signatureBytes = signature.sign();
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Failed sign using " + jcaSignatureAlgorithm, e);
            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                    | SignatureException e) {
                throw new SignatureException("Failed sign using " + jcaSignatureAlgorithm, e);
            }

            try {
                Signature signature = Signature.getInstance(jcaSignatureAlgorithm);
                signature.initVerify(publicKey);
                if (jcaSignatureAlgorithmParams != null) {
                    signature.setParameter(jcaSignatureAlgorithmParams);
                }
                signature.update(signer.signedData);
                if (!signature.verify(signatureBytes)) {
                    throw new SignatureException("Signature did not verify");
                }
            } catch (InvalidKeyException e) {
                throw new InvalidKeyException("Failed to verify generated " + jcaSignatureAlgorithm
                        + " signature using public key from certificate", e);
            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                    | SignatureException e) {
                throw new SignatureException("Failed to verify generated " + jcaSignatureAlgorithm
                        + " signature using public key from certificate", e);
            }

            signer.signatures.add(Pair.create(signatureAlgorithm, signatureBytes));
        }







        return encodeAsSequenceOfLengthPrefixedElements(
                new byte[][] {
                    signer.signedData,
                    encodeAsSequenceOfLengthPrefixedPairsOfIntAndLengthPrefixedBytes(
                            signer.signatures),
                    signer.publicKey,
                });
    }

    private static final class V2SignatureSchemeBlock {
        private static final class Signer {
            public byte[] signedData;
            public List<Pair<Integer, byte[]>> signatures;
            public byte[] publicKey;
        }

        private static final class SignedData {
            public List<Pair<Integer, byte[]>> digests;
            public List<byte[]> certificates;
        }
    }

    private static byte[] encodePublicKey(PublicKey publicKey) throws InvalidKeyException {
        byte[] encodedPublicKey = null;
        if ("X.509".equals(publicKey.getFormat())) {
            encodedPublicKey = publicKey.getEncoded();
        }
        if (encodedPublicKey == null) {
            try {
                encodedPublicKey =
                        KeyFactory.getInstance(publicKey.getAlgorithm())
                                .getKeySpec(publicKey, X509EncodedKeySpec.class)
                                .getEncoded();
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new InvalidKeyException(
                        "Failed to obtain X.509 encoded form of public key " + publicKey
                                + " of class " + publicKey.getClass().getName(),
                        e);
            }
        }
        if ((encodedPublicKey == null) || (encodedPublicKey.length == 0)) {
            throw new InvalidKeyException(
                    "Failed to obtain X.509 encoded form of public key " + publicKey
                            + " of class " + publicKey.getClass().getName());
        }
        return encodedPublicKey;
    }

    public static List<byte[]> encodeCertificates(List<X509Certificate> certificates)
            throws CertificateEncodingException {
        List<byte[]> result = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            result.add(certificate.getEncoded());
        }
        return result;
    }

    private static byte[] encodeAsSequenceOfLengthPrefixedElements(List<byte[]> sequence) {
        return encodeAsSequenceOfLengthPrefixedElements(
                sequence.toArray(new byte[sequence.size()][]));
    }

    private static byte[] encodeAsSequenceOfLengthPrefixedElements(byte[][] sequence) {
        int payloadSize = 0;
        for (byte[] element : sequence) {
            payloadSize += 4 + element.length;
        }
        ByteBuffer result = ByteBuffer.allocate(payloadSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] element : sequence) {
            result.putInt(element.length);
            result.put(element);
        }
        return result.array();
      }

    private static byte[] encodeAsSequenceOfLengthPrefixedPairsOfIntAndLengthPrefixedBytes(
            List<Pair<Integer, byte[]>> sequence) {
        int resultSize = 0;
        for (Pair<Integer, byte[]> element : sequence) {
            resultSize += 12 + element.getSecond().length;
        }
        ByteBuffer result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (Pair<Integer, byte[]> element : sequence) {
            byte[] second = element.getSecond();
            result.putInt(8 + second.length);
            result.putInt(element.getFirst());
            result.putInt(second.length);
            result.put(second);
        }
        return result.array();
    }










    private static ByteBuffer getByteBuffer(ByteBuffer source, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size: " + size);
        }
        int originalLimit = source.limit();
        int position = source.position();
        int limit = position + size;
        if ((limit < position) || (limit > originalLimit)) {
            throw new BufferUnderflowException();
        }
        source.limit(limit);
        try {
            ByteBuffer result = source.slice();
            result.order(source.order());
            source.position(limit);
            return result;
        } finally {
            source.limit(originalLimit);
        }
    }

    private static Pair<String, ? extends AlgorithmParameterSpec>
            getSignatureAlgorithmJcaSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
                return Pair.create(
                        "SHA256withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
            case SIGNATURE_RSA_PSS_WITH_SHA512:
                return Pair.create(
                        "SHA512withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
                return Pair.create("SHA256withRSA", null);
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return Pair.create("SHA512withRSA", null);
            case SIGNATURE_ECDSA_WITH_SHA256:
                return Pair.create("SHA256withECDSA", null);
            case SIGNATURE_ECDSA_WITH_SHA512:
                return Pair.create("SHA512withECDSA", null);
            case SIGNATURE_DSA_WITH_SHA256:
                return Pair.create("SHA256withDSA", null);
            case SIGNATURE_DSA_WITH_SHA512:
                return Pair.create("SHA512withDSA", null);
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static int getSignatureAlgorithmContentDigestAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA256:
                return CONTENT_DIGEST_CHUNKED_SHA256;
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_DSA_WITH_SHA512:
                return CONTENT_DIGEST_CHUNKED_SHA512;
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static String getContentDigestAlgorithmJcaDigestAlgorithm(int digestAlgorithm) {
        switch (digestAlgorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return "SHA-256";
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return "SHA-512";
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorithm: " + digestAlgorithm);
        }
    }

    private static int getContentDigestAlgorithmOutputSizeBytes(int digestAlgorithm) {
        switch (digestAlgorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return 256 / 8;
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return 512 / 8;
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorithm: " + digestAlgorithm);
        }
    }




    public static class ApkParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ApkParseException(String message) {
            super(message);
        }

        public ApkParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }




    private static class Pair<A, B> {
        private final A mFirst;
        private final B mSecond;

        private Pair(A first, B second) {
            mFirst = first;
            mSecond = second;
        }

        public static <A, B> Pair<A, B> create(A first, B second) {
            return new Pair<>(first, second);
        }

        public A getFirst() {
            return mFirst;
        }

        public B getSecond() {
            return mSecond;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((mFirst == null) ? 0 : mFirst.hashCode());
            result = prime * result + ((mSecond == null) ? 0 : mSecond.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            @SuppressWarnings("rawtypes")
            Pair other = (Pair) obj;
            if (mFirst == null) {
                if (other.mFirst != null) {
                    return false;
                }
            } else if (!mFirst.equals(other.mFirst)) {
                return false;
            }
            if (mSecond == null) {
                return other.mSecond == null;
            } else return mSecond.equals(other.mSecond);
        }
    }
}
