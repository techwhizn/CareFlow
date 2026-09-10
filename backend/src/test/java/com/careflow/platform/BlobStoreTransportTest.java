package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BlobStoreTransportTest {
  @Test
  void actualMinioTransportCanReadSourceBytes() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          boolean location = "location=".equals(exchange.getRequestURI().getRawQuery());
          byte[] body =
              (location
                      ? "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                      : "synthetic source")
                  .getBytes(StandardCharsets.UTF_8);
          exchange
              .getResponseHeaders()
              .set("Content-Type", location ? "application/xml" : "application/octet-stream");
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      var blobs =
          new BlobStore(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "synthetic-access",
              "synthetic-secret",
              "fixture");
      assertThat(blobs.get("source.txt"))
          .isEqualTo("synthetic source".getBytes(StandardCharsets.UTF_8));
    } finally {
      server.stop(0);
    }
  }
}
