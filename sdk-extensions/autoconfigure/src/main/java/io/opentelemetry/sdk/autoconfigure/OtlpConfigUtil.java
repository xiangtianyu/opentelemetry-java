/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class OtlpConfigUtil {

  static final String DATA_TYPE_TRACES = "traces";
  static final String DATA_TYPE_METRICS = "metrics";

  static void configureOtlpExporterBuilder(
      String dataType,
      ConfigProperties config,
      Consumer<String> setEndpoint,
      BiConsumer<String, String> addHeader,
      Consumer<Duration> setTimeout,
      Consumer<byte[]> setTrustedCertificates) {
    String endpoint = config.getString(String.format("otel.exporter.otlp.%s.endpoint", dataType));
    if (endpoint == null) {
      endpoint = config.getString("otel.exporter.otlp.endpoint");
    }
    if (endpoint != null) {
      setEndpoint.accept(endpoint);
    }

    Map<String, String> headers =
        config.getCommaSeparatedMap(String.format("otel.exporter.otlp.%s.headers", dataType));
    if (headers.isEmpty()) {
      headers = config.getCommaSeparatedMap("otel.exporter.otlp.headers");
    }
    headers.forEach(addHeader);

    Duration timeout = config.getDuration(String.format("otel.exporter.otlp.%s.timeout", dataType));
    if (timeout == null) {
      timeout = config.getDuration("otel.exporter.otlp.timeout");
    }
    if (timeout != null) {
      setTimeout.accept(timeout);
    }

    String certificate =
        config.getString(String.format("otel.exporter.otlp.%s.certificate", dataType));
    if (certificate == null) {
      certificate = config.getString("otel.exporter.otlp.certificate");
    }
    if (certificate != null) {
      Path path = Paths.get(certificate);
      if (!Files.exists(path)) {
        throw new ConfigurationException("Invalid OTLP certificate path: " + path);
      }
      final byte[] certificateBytes;
      try {
        certificateBytes = Files.readAllBytes(path);
      } catch (IOException e) {
        throw new ConfigurationException("Error reading OTLP certificate.", e);
      }
      setTrustedCertificates.accept(certificateBytes);
    }
  }

  private OtlpConfigUtil() {}
}