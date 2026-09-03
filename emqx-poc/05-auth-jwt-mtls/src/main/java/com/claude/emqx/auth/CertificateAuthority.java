package com.claude.emqx.auth;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Minimal CA + per-device cert issuer for the mTLS POC.
 *
 * <p>Why we do this in code rather than via openssl:
 *  - Production fleets need automated cert issuance at device-onboarding time.
 *    Shelling out to openssl is fine for 100 devices and a nightmare for 100k.
 *  - This is the exact code path you'd put behind your provisioning API.
 *  - The CA private key lives in memory only - obviously DO NOT do that in
 *    production. Real CA keys live in an HSM / cloud KMS.
 *
 * <p>What we DO model correctly:
 *  - X.509 extensions (BasicConstraints, KeyUsage) - EMQX rejects certs without these.
 *  - PEM output that matches what emqx.conf expects.
 *  - Subject CN = deviceId, so EMQX's "use CN as username" config works.
 */
@Service
public class CertificateAuthority {

    static { Security.addProvider(new BouncyCastleProvider()); }

    private final KeyPair caKeyPair;
    private final X509Certificate caCert;
    private final Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "emqx-poc-certs");

    public CertificateAuthority() throws Exception {
        Files.createDirectories(workDir);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        this.caKeyPair = kpg.generateKeyPair();
        this.caCert = buildCa();
        writePem(workDir.resolve("ca.crt"), caCert);
    }

    private X509Certificate buildCa() throws Exception {
        X500Name issuer = new X500Name("CN=POC EMQX CA,O=Demo,C=US");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(Duration.ofDays(365)));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, caKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /**
     * Issue a client cert with Subject CN = deviceId, signed by the in-memory CA.
     * Writes {@code <deviceId>.crt} + {@code <deviceId>.key} into the workDir.
     */
    public DeviceCert issueForDevice(String deviceId) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=" + deviceId);
        BigInteger serial = BigInteger.valueOf(System.nanoTime());
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(Duration.ofDays(30)));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(caCert.getSubjectX500Principal().getName()),
                serial, notBefore, notAfter, subject, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        Path certPath = workDir.resolve(deviceId + ".crt");
        Path keyPath  = workDir.resolve(deviceId + ".key");
        writePem(certPath, cert);
        writePem(keyPath, kp.getPrivate());
        return new DeviceCert(deviceId, certPath.toString(), keyPath.toString(),
                workDir.resolve("ca.crt").toString());
    }

    private static void writePem(Path path, Object obj) throws IOException {
        try (FileWriter fw = new FileWriter(path.toFile());
             JcaPEMWriter pem = new JcaPEMWriter(fw)) {
            pem.writeObject(obj);
        }
    }

    public Path getCaCertPath() { return workDir.resolve("ca.crt"); }

    public record DeviceCert(String deviceId, String certPath, String keyPath, String caCertPath) {}
}
