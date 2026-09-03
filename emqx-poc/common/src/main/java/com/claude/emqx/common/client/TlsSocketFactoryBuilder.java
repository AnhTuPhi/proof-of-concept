package com.claude.emqx.common.client;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Build an {@link SSLSocketFactory} from PEM files for mTLS (POC 05).
 *
 * <p>The standard JDK only loads PKCS#12 / JKS, but EMQX docs ship PEM. Rather
 * than ask devs to convert via openssl every time, we parse PEM here. This is
 * also exactly the flow you'd use to load a per-device cert into an embedded
 * device's JVM.
 */
final class TlsSocketFactoryBuilder {

    private TlsSocketFactoryBuilder() {}

    static SSLSocketFactory build(MqttClientProperties.Tls tls) {
        try {
            // 1. Trust store: just the CA cert (so the client trusts EMQX server)
            X509Certificate caCert = readCert(Path.of(tls.caCertPath()));
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", caCert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // 2. Key store: client cert + private key (only if mTLS - server-only TLS skips this)
            KeyManagerFactory kmf = null;
            if (tls.clientCertPath() != null && tls.clientKeyPath() != null) {
                X509Certificate clientCert = readCert(Path.of(tls.clientCertPath()));
                PrivateKey privateKey = readPkcs8Key(Path.of(tls.clientKeyPath()));
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setKeyEntry("client", privateKey, new char[0], new java.security.cert.Certificate[]{clientCert});
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, new char[0]);
            }

            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf != null ? kmf.getKeyManagers() : null, tmf.getTrustManagers(), null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build TLS socket factory", e);
        }
    }

    private static X509Certificate readCert(Path pemPath) throws IOException, java.security.cert.CertificateException {
        String pem = Files.readString(pemPath);
        String base64 = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static PrivateKey readPkcs8Key(Path pemPath) throws Exception {
        String pem = Files.readString(pemPath);
        String base64 = pem.replaceAll("-----BEGIN (RSA |EC )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA |EC )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // Try RSA first (most common for EMQX demo certs), then EC.
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception ignore) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }
}
