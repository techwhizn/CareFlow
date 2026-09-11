package com.careflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SsoService {
  private final Db db;
  private final Identity auth;
  private final ObjectMapper json;
  private final String introspectionUrl;
  private final String clientId;
  private final String clientSecret;
  private final HttpClient client;

  public SsoService(
      Db db,
      Identity auth,
      ObjectMapper json,
      @Value("${careflow.sso.introspection-url:}") String introspectionUrl,
      @Value("${careflow.sso.client-id:}") String clientId,
      @Value("${careflow.sso.client-secret:}") String clientSecret) {
    this.db = db;
    this.auth = auth;
    this.json = json;
    this.introspectionUrl = introspectionUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Transactional
  public Map<String, Object> exchange(String externalToken) {
    if (externalToken == null || externalToken.isBlank())
      throw new ApiException(401, "UNAUTHENTICATED", "请提供外部身份凭证");
    URI endpoint;
    try {
      endpoint = URI.create(introspectionUrl);
    } catch (IllegalArgumentException e) {
      throw new ApiException(503, "SSO_UNAVAILABLE", "企业统一身份认证未配置");
    }
    if (!"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || clientId.isBlank()
        || clientSecret.isBlank()) throw new ApiException(503, "SSO_UNAVAILABLE", "企业统一身份认证未配置");
    try {
      String body =
          "token="
              + java.net.URLEncoder.encode(externalToken, java.nio.charset.StandardCharsets.UTF_8)
              + "&token_type_hint=access_token";
      String basic =
          Base64.getEncoder()
              .encodeToString(
                  (clientId + ":" + clientSecret)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Basic " + basic)
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ApiException(401, "UNAUTHENTICATED", "外部身份凭证无效");
      Map<String, Object> claims = json.readValue(response.body(), Map.class);
      if (!Boolean.TRUE.equals(claims.get("active")))
        throw new ApiException(401, "UNAUTHENTICATED", "外部身份凭证已失效");
      String subject = Objects.toString(claims.get("sub"), "");
      String tenant = Objects.toString(claims.get("tenant_id"), "");
      String issuer = Objects.toString(claims.get("iss"), introspectionUrl);
      if (subject.isBlank() || tenant.isBlank())
        throw new ApiException(401, "UNAUTHENTICATED", "外部身份缺少主体映射");
      var member =
          db.one(
              "SELECT * FROM members WHERE tenant_id=? AND external_issuer=? AND external_subject=? AND active=TRUE AND removed=FALSE",
              tenant,
              issuer,
              subject);
      String memberId = Db.str(member, "id");
      String credential =
          auth.credential(
              tenant, memberId, "MEMBER", Timestamp.from(Instant.now().plus(Duration.ofHours(8))));
      auth.audit(
          new Identity.Actor(tenant, memberId, "MEMBER", Db.str(member, "role")),
          "SSO_EXCHANGE",
          memberId,
          "issuer=" + issuer);
      return Map.of("token", credential, "expires_in_seconds", 28800, "member_id", memberId);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(503, "SSO_UNAVAILABLE", "企业统一身份认证暂时不可用");
    }
  }
}
