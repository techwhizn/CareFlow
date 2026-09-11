package com.careflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class IntegrationDeliveryService {
  private final Db db;
  private final ModelKeyVault vault;
  private final ObjectMapper json;

  public IntegrationDeliveryService(Db db, ModelKeyVault vault, ObjectMapper json) {
    this.db = db;
    this.vault = vault;
    this.json = json;
  }

  @Transactional
  public void enqueue(String tenant, String event, Map<String, Object> payload) {
    enqueue(tenant, event, null, payload);
  }

  @Transactional
  public void enqueue(String tenant, String event, String resourceId, Map<String, Object> payload) {
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize integration event", e);
    }
    for (Map<String, Object> endpoint :
        db.list(
            "SELECT id,events FROM integration_endpoints WHERE tenant_id=? AND active=TRUE",
            tenant)) {
      String events = Db.str(endpoint, "events");
      if (!events.isBlank() && !Set.of(events.split(",")).contains(event)) continue;
      db.exec(
          "INSERT INTO integration_deliveries(id,tenant_id,endpoint_id,event_type,payload,resource_id) VALUES(?,?,?,?,?,?)",
          Db.id(),
          tenant,
          Db.str(endpoint, "id"),
          event,
          body,
          resourceId);
    }
  }

  @Scheduled(fixedDelay = 10000)
  @Transactional
  public void deliverPending() {
    for (Map<String, Object> delivery :
        db
            .list(
                "SELECT d.*,e.endpoint_url,e.secret_ciphertext FROM integration_deliveries d JOIN integration_endpoints e ON e.id=d.endpoint_id AND e.tenant_id=d.tenant_id WHERE d.status='PENDING' AND d.next_attempt_at<=CURRENT_TIMESTAMP AND e.active=TRUE ORDER BY d.created_at LIMIT 10")
            .stream()
            .toList()) {
      deliver(delivery);
    }
  }

  private void deliver(Map<String, Object> delivery) {
    String id = Db.str(delivery, "id");
    int attempts = (int) Db.num(delivery, "attempts") + 1;
    try {
      String secret =
          vault.decrypt(
              Db.str(delivery, "tenant_id"),
              Db.str(delivery, "endpoint_id"),
              Db.str(delivery, "secret_ciphertext"));
      String signature = sign(secret, Db.str(delivery, "payload"));
      int status =
          client(Db.str(delivery, "endpoint_url"))
              .post()
              .header("Content-Type", "application/json")
              .header("X-CareFlow-Event", Db.str(delivery, "event_type"))
              .header("X-CareFlow-Delivery", id)
              .header("X-CareFlow-Signature", "sha256=" + signature)
              .body(Db.str(delivery, "payload"))
              .exchange((request, response) -> response.getStatusCode().value());
      if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
      db.exec(
          "UPDATE integration_deliveries SET status='DELIVERED',attempts=?,delivered_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
          attempts,
          id);
    } catch (Exception e) {
      String state = attempts >= 5 ? "FAILED" : "PENDING";
      db.exec(
          "UPDATE integration_deliveries SET status=?,attempts=?,next_attempt_at=CURRENT_TIMESTAMP,last_error=? WHERE id=? AND status='PENDING'",
          state,
          attempts,
          e.getClass().getSimpleName(),
          id);
    }
  }

  static String sign(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Webhook signing unavailable", e);
    }
  }

  private static RestClient client(String endpoint) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(endpoint).requestFactory(factory).build();
  }
}
