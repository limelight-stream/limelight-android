package com.limelight.nvstream.http;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public interface LimelightCryptoProvider {
    X509Certificate getClientCertificate();
    PrivateKey getClientPrivateKey();
    byte[] getPemEncodedClientCertificate();
    String encodeBase64String(byte[] data);
}
