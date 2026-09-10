package com.careflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HttpBoundary extends OncePerRequestFilter {
  private final Identity identity;
  private final ObjectMapper json;
  private final String internal;

  public HttpBoundary(
      Identity identity, ObjectMapper json, @Value("${careflow.internal-token}") String internal) {
    this.identity = identity;
    this.json = json;
    this.internal = internal;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String requestId = Db.id();
    req.setAttribute("request_id", requestId);
    res.setHeader("X-Request-ID", requestId);
    res.setHeader("Cache-Control", "no-store");
    try {
      if (req.getRequestURI().startsWith("/internal/")) {
        String supplied = req.getHeader("X-Internal-Token");
        if (internal.length() < 32
            || supplied == null
            || !java.security.MessageDigest.isEqual(
                internal.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          throw new ApiException(401, "UNAUTHENTICATED", "内部身份无效");
      } else if (req.getRequestURI().startsWith("/api/v1/")
          && !req.getRequestURI().equals("/api/v1/bootstrap")
          && !(req.getMethod().equals("POST")
              && req.getRequestURI().equals("/api/v1/enterprises"))) {
        var actor = identity.authenticate(req.getHeader("Authorization"));
        identity.authorizeRequest(actor, req.getMethod(), req.getRequestURI());
        req.setAttribute("actor", actor);
      }
      chain.doFilter(req, res);
    } catch (ApiException e) {
      res.setStatus(e.status);
      res.setContentType("application/json;charset=UTF-8");
      json.writeValue(
          res.getOutputStream(),
          Map.of("code", e.code, "message", e.getMessage(), "request_id", requestId));
    }
  }
}
