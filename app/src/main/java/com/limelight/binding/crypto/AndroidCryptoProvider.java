package com.limelight.binding.crypto;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.LimelightCryptoProvider;

public class AndroidCryptoProvider implements LimelightCryptoProvider {

    private final File certFile;
    private final File keyFile;

    private X509Certificate cert;
    private PrivateKey key;
    private byte[] pemCertBytes;

    private static final Object globalCryptoLock = new Object();

    private static final Provider bcProvider = new BouncyCastleProvider();

    public AndroidCryptoProvider(Context c) {
        String dataPath = c.getFilesDir().getAbsolutePath();

        certFile = new File(dataPath + File.separator + "client.crt");
        keyFile = new File(dataPath + File.separator + "client.key");
    }

    private byte[] loadFileToBytes(File f) {
        if (!f.exists()) {
            return null;
        }

        try (final FileInputStream fin = new FileInputStream(f)) {
            byte[] fileData = new byte[(int) f.length()];
            if (fin.read(fileData) != f.length()) {
                // Failed to read
                fileData = null;
            }
            return fileData;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean loadCertKeyPair() {
        byte[] certBytes = loadFileToBytes(certFile);
        byte[] keyBytes = loadFileToBytes(keyFile);

        // If either file was missing, we definitely can't succeed
        if (certBytes == null || keyBytes == null) {
            LimeLog.info("Missing cert or key; need to generate a new one");
            return false;
        }

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509", bcProvider);
            cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
            pemCertBytes = certBytes;
            KeyFactory keyFactory = KeyFactory.getInstance("RSA", bcProvider);
            key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (CertificateException e) {
            // May happen if the cert is corrupt
            LimeLog.warning("Corrupted certificate");
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            // May happen if the key is corrupt
            LimeLog.warning("Corrupted key");
            return false;
        }

        return true;
    }

    @SuppressLint("TrulyRandom")
    private boolean generateCertKeyPair() {
        byte[] snBytes = new byte[8];
        new SecureRandom().nextBytes(snBytes);

        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider);
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        Date now = new Date();

        // Expires in 20 years
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.YEAR, 20);
        Date expirationDate = calendar.getTime();

        BigInteger serial = new BigInteger(snBytes).abs();

        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Client");
        X500Name name = nameBuilder.build();

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(name, serial, now, expirationDate, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));

        try {
            ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.getPrivate());
            cert = new JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(sigGen));
            key = keyPair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LimeLog.info("Generated a new key pair");

        // Save the resulting pair
        saveCertKeyPair();

        return true;
    }

    private void saveCertKeyPair() {
        try (final FileOutputStream certOut = new FileOutputStream(certFile);
             final FileOutputStream keyOut = new FileOutputStream(keyFile)
        ) {
            // Write the certificate in OpenSSL PEM format (important for the server)
            StringWriter strWriter = new StringWriter();
            try (final JcaPEMWriter pemWriter = new JcaPEMWriter(strWriter)) {
                pemWriter.writeObject(cert);
            }

            // Line endings MUST be UNIX for the PC to accept the cert properly
            try (final OutputStreamWriter certWriter = new OutputStreamWriter(certOut)) {
                String pemStr = strWriter.getBuffer().toString();
                for (int i = 0; i < pemStr.length(); i++) {
                    char c = pemStr.charAt(i);
                    if (c != '\r')
                        certWriter.append(c);
                }
            }

            // Write the private out in PKCS8 format
            keyOut.write(key.getEncoded());

            LimeLog.info("Saved generated key pair to disk");
        } catch (IOException e) {
            // This isn't good because it means we'll have
            // to re-pair next time
            e.printStackTrace();
        }
    }

    public X509Certificate getClientCertificate() {
        // Use a lock here to ensure only one guy will be generating or loading
        // the certificate and key at a time
        synchronized (globalCryptoLock) {
            // Return a loaded cert if we have one
            if (cert != null) {
                return cert;
            }

            // No loaded cert yet, let's see if we have one on disk
            if (loadCertKeyPair()) {
                // Got one
                return cert;
            }

            // Try to generate a new key pair
            if (!generateCertKeyPair()) {
                // Failed
                return null;
            }

            // Load the generated pair
            loadCertKeyPair();
            return cert;
        }
    }

    public PrivateKey getClientPrivateKey() {
        // Use a lock here to ensure only one guy will be generating or loading
        // the certificate and key at a time
        synchronized (globalCryptoLock) {
            // Return a loaded key if we have one
            if (key != null) {
                return key;
            }

            // No loaded key yet, let's see if we have one on disk
            if (loadCertKeyPair()) {
                // Got one
                return key;
            }

            // Try to generate a new key pair
            if (!generateCertKeyPair()) {
                // Failed
                return null;
            }

            // Load the generated pair
            loadCertKeyPair();
            return key;
        }
    }

    public byte[] getPemEncodedClientCertificate() {
        synchronized (globalCryptoLock) {
            // Call our helper function to do the cert loading/generation for us
            getClientCertificate();

            // Return a cached value if we have it
            return pemCertBytes;
        }
    }

    @Override
    public String encodeBase64String(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }
}
