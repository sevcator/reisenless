package com.topjohnwu.magisk.core.signing;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;






public class SignApk {
    private static final String CERT_SF_NAME = "META-INF/CERT.SF";
    private static final String CERT_SIG_NAME = "META-INF/CERT.%s";
    private static final String CERT_SF_MULTI_NAME = "META-INF/CERT%d.SF";
    private static final String CERT_SIG_MULTI_NAME = "META-INF/CERT%d.%s";


    private static final int USE_SHA1 = 1;
    private static final int USE_SHA256 = 2;




    private static final String APK_SIG_SCHEME_V2_DIGEST_ALGORITHM = "SHA-256";

    private static final Pattern stripPattern =
            Pattern.compile("^(META-INF/((.*)[.](SF|RSA|DSA|EC)|com/android/otacert))|(" +
                    Pattern.quote(JarFile.MANIFEST_NAME) + ")$");





    private static int getDigestAlgorithm(X509Certificate cert) {
        String sigAlg = cert.getSigAlgName().toUpperCase(Locale.US);
        if ("SHA1WITHRSA".equals(sigAlg) || "MD5WITHRSA".equals(sigAlg)) {
            return USE_SHA1;
        } else if (sigAlg.startsWith("SHA256WITH")) {
            return USE_SHA256;
        } else {
            throw new IllegalArgumentException("unsupported signature algorithm \"" + sigAlg +
                    "\" in cert [" + cert.getSubjectDN());
        }
    }




    private static String getSignatureAlgorithm(X509Certificate cert) {
        String keyType = cert.getPublicKey().getAlgorithm().toUpperCase(Locale.US);
        if ("RSA".equalsIgnoreCase(keyType)) {
            if (getDigestAlgorithm(cert) == USE_SHA256) {
                return "SHA256withRSA";
            } else {
                return "SHA1withRSA";
            }
        } else if ("EC".equalsIgnoreCase(keyType)) {
            return "SHA256withECDSA";
        } else {
            throw new IllegalArgumentException("unsupported key type: " + keyType);
        }
    }





    private static Manifest addDigestsToManifest(JarMap jar, int hashes)
            throws IOException, GeneralSecurityException {
        Manifest input = jar.getManifest();
        Manifest output = new Manifest();
        Attributes main = output.getMainAttributes();
        if (input != null) {
            main.putAll(input.getMainAttributes());
        } else {
            main.putValue("Manifest-Version", "1.0");
            main.putValue("Created-By", "1.0 (Android SignApk)");
        }

        MessageDigest md_sha1 = null;
        MessageDigest md_sha256 = null;
        if ((hashes & USE_SHA1) != 0) {
            md_sha1 = MessageDigest.getInstance("SHA1");
        }
        if ((hashes & USE_SHA256) != 0) {
            md_sha256 = MessageDigest.getInstance("SHA256");
        }

        byte[] buffer = new byte[4096];
        int num;





        TreeMap<String, JarEntry> byName = new TreeMap<>();

        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            byName.put(entry.getName(), entry);
        }

        for (JarEntry entry : byName.values()) {
            String name = entry.getName();
            if (!entry.isDirectory() && !stripPattern.matcher(name).matches()) {
                InputStream data = jar.getInputStream(entry);
                while ((num = data.read(buffer)) > 0) {
                    if (md_sha1 != null) md_sha1.update(buffer, 0, num);
                    if (md_sha256 != null) md_sha256.update(buffer, 0, num);
                }

                Attributes attr = null;
                if (input != null) attr = input.getAttributes(name);
                attr = attr != null ? new Attributes(attr) : new Attributes();

                for (Iterator<Object> i = attr.keySet().iterator(); i.hasNext(); ) {
                    Object key = i.next();
                    if (!(key instanceof Attributes.Name)) {
                        continue;
                    }
                    String attributeNameLowerCase =
                            key.toString().toLowerCase(Locale.US);
                    if (attributeNameLowerCase.endsWith("-digest")) {
                        i.remove();
                    }
                }

                if (md_sha1 != null) {
                    attr.putValue("SHA1-Digest",
                            new String(Base64.encode(md_sha1.digest()), "ASCII"));
                }

                if (md_sha256 != null) {
                    attr.putValue("SHA-256-Digest",
                            new String(Base64.encode(md_sha256.digest()), "ASCII"));
                }
                output.getEntries().put(name, attr);
            }
        }

        return output;
    }




    private static void writeSignatureFile(Manifest manifest, OutputStream out,
                                           int hash)
            throws IOException, GeneralSecurityException {
        Manifest sf = new Manifest();
        Attributes main = sf.getMainAttributes();
        main.putValue("Signature-Version", "1.0");
        main.putValue("Created-By", "1.0 (Android SignApk)");





        main.putValue(
                ApkSignerV2.SF_ATTRIBUTE_ANDROID_APK_SIGNED_NAME,
                ApkSignerV2.SF_ATTRIBUTE_ANDROID_APK_SIGNED_VALUE);

        MessageDigest md = MessageDigest.getInstance(hash == USE_SHA256 ? "SHA256" : "SHA1");
        PrintStream print = new PrintStream(new DigestOutputStream(new ByteArrayOutputStream(), md),
                true, "UTF-8");


        manifest.write(print);
        print.flush();
        main.putValue(hash == USE_SHA256 ? "SHA-256-Digest-Manifest" : "SHA1-Digest-Manifest",
                new String(Base64.encode(md.digest()), "ASCII"));

        Map<String, Attributes> entries = manifest.getEntries();
        for (Map.Entry<String, Attributes> entry : entries.entrySet()) {

            print.print("Name: " + entry.getKey() + "\r\n");
            for (Map.Entry<Object, Object> att : entry.getValue().entrySet()) {
                print.print(att.getKey() + ": " + att.getValue() + "\r\n");
            }
            print.print("\r\n");
            print.flush();

            Attributes sfAttr = new Attributes();
            sfAttr.putValue(hash == USE_SHA256 ? "SHA-256-Digest" : "SHA1-Digest",
                    new String(Base64.encode(md.digest()), "ASCII"));
            sf.getEntries().put(entry.getKey(), sfAttr);
        }

        CountOutputStream cout = new CountOutputStream(out);
        sf.write(cout);





        if ((cout.size() % 1024) == 0) {
            cout.write('\r');
            cout.write('\n');
        }
    }




    private static void writeSignatureBlock(
            CMSTypedData data, X509Certificate publicKey, PrivateKey privateKey, OutputStream out)
            throws IOException,
            CertificateEncodingException,
            OperatorCreationException,
            CMSException {
        ArrayList<X509Certificate> certList = new ArrayList<>(1);
        certList.add(publicKey);
        JcaCertStore certs = new JcaCertStore(certList);

        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder(getSignatureAlgorithm(publicKey))
                .build(privateKey);
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .setDirectSignature(true)
                        .build(signer, publicKey)
        );
        gen.addCertificates(certs);
        CMSSignedData sigData = gen.generate(data, false);

        try (ASN1InputStream asn1 = new ASN1InputStream(sigData.getEncoded())) {
            ASN1OutputStream dos = ASN1OutputStream.create(out, ASN1Encoding.DER);
            dos.writeObject(asn1.readObject());
        }
    }







    private static void copyFiles(Manifest manifest, JarMap in, JarOutputStream out,
                                  long timestamp, int defaultAlignment) throws IOException {
        byte[] buffer = new byte[4096];
        int num;

        Map<String, Attributes> entries = manifest.getEntries();
        ArrayList<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);

        boolean firstEntry = true;
        long offset = 0L;








        for (String name : names) {
            JarEntry inEntry = in.getJarEntry(name);
            JarEntry outEntry;
            if (inEntry.getMethod() != JarEntry.STORED) continue;

            outEntry = new JarEntry(inEntry);
            outEntry.setTime(timestamp);



            outEntry.setComment(null);
            outEntry.setExtra(null);




            offset += JarFile.LOCHDR + outEntry.getName().length();
            if (firstEntry) {





                offset += 4;
                firstEntry = false;
            }
            int alignment = getStoredEntryDataAlignment(name, defaultAlignment);
            if (alignment > 0 && (offset % alignment != 0)) {



                int needed = alignment - (int) (offset % alignment);
                outEntry.setExtra(new byte[needed]);
                offset += needed;
            }

            out.putNextEntry(outEntry);

            InputStream data = in.getInputStream(inEntry);
            while ((num = data.read(buffer)) > 0) {
                out.write(buffer, 0, num);
                offset += num;
            }
            out.flush();
        }





        for (String name : names) {
            JarEntry inEntry = in.getJarEntry(name);
            JarEntry outEntry;
            if (inEntry.getMethod() == JarEntry.STORED) continue;

            outEntry = new JarEntry(name);
            outEntry.setTime(timestamp);
            out.putNextEntry(outEntry);

            InputStream data = in.getInputStream(inEntry);
            while ((num = data.read(buffer)) > 0) {
                out.write(buffer, 0, num);
            }
            out.flush();
        }
    }





    private static int getStoredEntryDataAlignment(String entryName, int defaultAlignment) {
        if (defaultAlignment <= 0) {
            return 0;
        }

        if (entryName.endsWith(".so")) {


            return 4096;
        } else {
            return defaultAlignment;
        }
    }

    private static void signFile(Manifest manifest,
                                 X509Certificate[] publicKey, PrivateKey[] privateKey,
                                 long timestamp, JarOutputStream outputJar) throws Exception {

        JarEntry je = new JarEntry(JarFile.MANIFEST_NAME);
        je.setTime(timestamp);
        outputJar.putNextEntry(je);
        manifest.write(outputJar);

        int numKeys = publicKey.length;
        for (int k = 0; k < numKeys; ++k) {

            je = new JarEntry(numKeys == 1 ? CERT_SF_NAME :
                    (String.format(Locale.US, CERT_SF_MULTI_NAME, k)));
            je.setTime(timestamp);
            outputJar.putNextEntry(je);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeSignatureFile(manifest, baos, getDigestAlgorithm(publicKey[k]));
            byte[] signedData = baos.toByteArray();
            outputJar.write(signedData);


            final String keyType = publicKey[k].getPublicKey().getAlgorithm();
            je = new JarEntry(numKeys == 1 ? (String.format(CERT_SIG_NAME, keyType)) :
                    (String.format(Locale.US, CERT_SIG_MULTI_NAME, k, keyType)));
            je.setTime(timestamp);
            outputJar.putNextEntry(je);
            writeSignatureBlock(new CMSProcessableByteArray(signedData),
                    publicKey[k], privateKey[k], outputJar);
        }
    }





    private static List<ApkSignerV2.SignerConfig> createV2SignerConfigs(
            PrivateKey[] privateKeys, X509Certificate[] certificates, String[] digestAlgorithms)
            throws InvalidKeyException {
        if (privateKeys.length != certificates.length) {
            throw new IllegalArgumentException(
                    "The number of private keys must match the number of certificates: "
                            + privateKeys.length + " vs" + certificates.length);
        }
        List<ApkSignerV2.SignerConfig> result = new ArrayList<>(privateKeys.length);
        for (int i = 0; i < privateKeys.length; i++) {
            PrivateKey privateKey = privateKeys[i];
            X509Certificate certificate = certificates[i];
            PublicKey publicKey = certificate.getPublicKey();
            String keyAlgorithm = privateKey.getAlgorithm();
            if (!keyAlgorithm.equalsIgnoreCase(publicKey.getAlgorithm())) {
                throw new InvalidKeyException(
                        "Key algorithm of private key #" + (i + 1) + " does not match key"
                                + " algorithm of public key #" + (i + 1) + ": " + keyAlgorithm
                                + " vs " + publicKey.getAlgorithm());
            }
            ApkSignerV2.SignerConfig signerConfig = new ApkSignerV2.SignerConfig();
            signerConfig.privateKey = privateKey;
            signerConfig.certificates = Collections.singletonList(certificate);
            List<Integer> signatureAlgorithms = new ArrayList<>(digestAlgorithms.length);
            for (String digestAlgorithm : digestAlgorithms) {
                try {
                    signatureAlgorithms.add(getV2SignatureAlgorithm(keyAlgorithm, digestAlgorithm));
                } catch (IllegalArgumentException e) {
                    throw new InvalidKeyException(
                            "Unsupported key and digest algorithm combination for signer #"
                                    + (i + 1), e);
                }
            }
            signerConfig.signatureAlgorithms = signatureAlgorithms;
            result.add(signerConfig);
        }
        return result;
    }

    private static int getV2SignatureAlgorithm(String keyAlgorithm, String digestAlgorithm) {
        if ("SHA-256".equalsIgnoreCase(digestAlgorithm)) {
            if ("RSA".equalsIgnoreCase(keyAlgorithm)) {



                return ApkSignerV2.SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256;
            } else if ("EC".equalsIgnoreCase(keyAlgorithm)) {
                return ApkSignerV2.SIGNATURE_ECDSA_WITH_SHA256;
            } else if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
                return ApkSignerV2.SIGNATURE_DSA_WITH_SHA256;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm: " + keyAlgorithm);
            }
        } else if ("SHA-512".equalsIgnoreCase(digestAlgorithm)) {
            if ("RSA".equalsIgnoreCase(keyAlgorithm)) {



                return ApkSignerV2.SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512;
            } else if ("EC".equalsIgnoreCase(keyAlgorithm)) {
                return ApkSignerV2.SIGNATURE_ECDSA_WITH_SHA512;
            } else if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
                return ApkSignerV2.SIGNATURE_DSA_WITH_SHA512;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm: " + keyAlgorithm);
            }
        } else {
            throw new IllegalArgumentException("Unsupported digest algorithm: " + digestAlgorithm);
        }
    }

    public static void sign(X509Certificate cert, PrivateKey key,
                            JarMap inputJar, OutputStream outputStream) throws Exception {
        int alignment = 4;
        int hashes = 0;

        X509Certificate[] publicKey = new X509Certificate[1];
        publicKey[0] = cert;
        hashes |= getDigestAlgorithm(publicKey[0]);


        long timestamp = 1230768000000L;



        timestamp -= TimeZone.getDefault().getOffset(timestamp);

        PrivateKey[] privateKey = new PrivateKey[1];
        privateKey[0] = key;


        ByteArrayStream v1SignedApkBuf = new ByteArrayStream();
        JarOutputStream outputJar = new JarOutputStream(v1SignedApkBuf);


        outputJar.setLevel(9);
        Manifest manifest = addDigestsToManifest(inputJar, hashes);
        copyFiles(manifest, inputJar, outputJar, timestamp, alignment);
        signFile(manifest, publicKey, privateKey, timestamp, outputJar);
        outputJar.close();
        ByteBuffer v1SignedApk = v1SignedApkBuf.toByteBuffer();

        ByteBuffer[] outputChunks;
        List<ApkSignerV2.SignerConfig> signerConfigs = createV2SignerConfigs(privateKey, publicKey,
                new String[]{APK_SIG_SCHEME_V2_DIGEST_ALGORITHM});
        outputChunks = ApkSignerV2.sign(v1SignedApk, signerConfigs);



        for (ByteBuffer outputChunk : outputChunks) {
            outputStream.write(outputChunk.array(),
                    outputChunk.arrayOffset() + outputChunk.position(), outputChunk.remaining());
            outputChunk.position(outputChunk.limit());
        }
    }





    private static class CountOutputStream extends FilterOutputStream {
        private int mCount;

        public CountOutputStream(OutputStream out) {
            super(out);
            mCount = 0;
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            mCount++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            mCount += len;
        }

        public int size() {
            return mCount;
        }
    }
}
