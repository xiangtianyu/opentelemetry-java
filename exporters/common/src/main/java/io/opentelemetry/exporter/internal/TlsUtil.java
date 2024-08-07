/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Utilities for working with TLS.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class TlsUtil {

  private static final String PEM_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
  private static final String PEM_KEY_FOOTER = "-----END PRIVATE KEY-----";
  private static final List<KeyFactory> SUPPORTED_KEY_FACTORIES;

  static {
    SUPPORTED_KEY_FACTORIES = new ArrayList<>();
    try {
      SUPPORTED_KEY_FACTORIES.add(KeyFactory.getInstance("RSA"));
    } catch (NoSuchAlgorithmException e) {
      // Ignore and continue
    }
    try {
      SUPPORTED_KEY_FACTORIES.add(KeyFactory.getInstance("EC"));
    } catch (NoSuchAlgorithmException e) {
      // Ignore and continue
    }
  }

  private TlsUtil() {}

  /**
   * Creates {@link KeyManager} initiated by keystore containing single private key with matching
   * certificate chain.
   */
  public static X509KeyManager keyManager(byte[] privateKeyPem, byte[] certificatePem)
      throws SSLException {
    requireNonNull(privateKeyPem, "privateKeyPem");
    requireNonNull(certificatePem, "certificatePem");
    try {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(null);
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodePem(privateKeyPem));
      PrivateKey key = generatePrivateKey(keySpec, SUPPORTED_KEY_FACTORIES);

      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      ByteArrayInputStream is = new ByteArrayInputStream(certificatePem);
      // pass the input stream to generateCertificates to get a list of certificates
      // generateCertificates can handle multiple certificates in a single input stream
      // including PEM files with explanatory text
      List<? extends Certificate> chain = (List<? extends Certificate>) cf.generateCertificates(is);
      ks.setKeyEntry("trusted", key, "".toCharArray(), chain.toArray(new Certificate[] {}));

      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, "".toCharArray());
      return (X509KeyManager) kmf.getKeyManagers()[0];
    } catch (CertificateException
        | KeyStoreException
        | IOException
        | NoSuchAlgorithmException
        | UnrecoverableKeyException e) {
      throw new SSLException("Could not build KeyManagerFactory from clientKeysPem.", e);
    }
  }

  // Visible for testing
  static PrivateKey generatePrivateKey(PKCS8EncodedKeySpec keySpec, List<KeyFactory> keyFactories)
      throws SSLException {
    // Try to generate key using supported key factories
    for (KeyFactory factory : keyFactories) {
      try {
        return factory.generatePrivate(keySpec);
      } catch (InvalidKeySpecException e) {
        // Ignore
      }
    }
    throw new SSLException(
        "Unable to generate key from supported algorithms: "
            + keyFactories.stream().map(KeyFactory::getAlgorithm).collect(joining(",", "[", "]")));
  }

  /** Returns a {@link TrustManager} for the given trusted certificates. */
  public static X509TrustManager trustManager(byte[] trustedCertificatesPem) throws SSLException {
    requireNonNull(trustedCertificatesPem, "trustedCertificatesPem");
    try {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(null);

      ByteArrayInputStream is = new ByteArrayInputStream(trustedCertificatesPem);
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      int i = 0;
      // pass the input stream to generateCertificates to get a list of certificates
      // generateCertificates can handle multiple certificates in a single input stream
      // including PEM files with explanatory text
      List<? extends Certificate> certificates =
          (List<? extends Certificate>) factory.generateCertificates(is);
      for (Certificate certificate : certificates) {
        ks.setCertificateEntry("cert_" + i, certificate);
        i++;
      }

      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      return (X509TrustManager) tmf.getTrustManagers()[0];
    } catch (CertificateException | KeyStoreException | IOException | NoSuchAlgorithmException e) {
      throw new SSLException("Could not build TrustManagerFactory from trustedCertificatesPem.", e);
    }
  }

  // Visible for testing
  static byte[] decodePem(byte[] pem) {
    String pemStr = new String(pem, StandardCharsets.UTF_8).trim();
    if (!pemStr.startsWith(PEM_KEY_HEADER) || !pemStr.endsWith(PEM_KEY_FOOTER)) {
      // pem may already be a decoded binary key, try to use it.
      return pem;
    }

    String contentWithNewLines =
        pemStr.substring(PEM_KEY_HEADER.length(), pemStr.length() - PEM_KEY_FOOTER.length());
    String content = contentWithNewLines.replaceAll("\\s", "");

    return Base64.getDecoder().decode(content);
  }
}
