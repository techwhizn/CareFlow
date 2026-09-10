package com.careflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Explicit management operations; authorization and revision checks remain server-side. */
public final class ManagementClient {
  private final CareFlowClient client;

  ManagementClient(CareFlowClient client) {
    this.client = client;
  }

  public record KnowledgeAttributes(
      String name,
      String description,
      String language,
      List<String> tags,
      String owner_id,
      long revision) {}

  public record DocumentMetadata(
      String title,
      String source,
      String language,
      List<String> tags,
      List<String> product_models,
      long revision,
      String valid_from,
      String valid_until) {}

  public record PermissionChange(Map<String, List<String>> grants, long revision) {}

  public record MemberChange(String name, String role, String state, long revision) {}

  public JsonNode knowledgeSettings(String id) throws IOException {
    return client.request(
        "GET", "knowledge-bases/" + CareFlowClient.id(id) + "/settings", null, null);
  }

  public JsonNode knowledgeOverview(String id) throws IOException {
    return client.request(
        "GET", "knowledge-bases/" + CareFlowClient.id(id) + "/overview", null, null);
  }

  public JsonNode knowledgeImpact(String id) throws IOException {
    return client.request(
        "GET", "knowledge-bases/" + CareFlowClient.id(id) + "/impact", null, null);
  }

  public JsonNode documentMetadata(String id) throws IOException {
    return client.request("GET", "documents/" + CareFlowClient.id(id) + "/metadata", null, null);
  }

  public JsonNode documentPublications(String id) throws IOException {
    return client.request(
        "GET", "documents/" + CareFlowClient.id(id) + "/publications", null, null);
  }

  public JsonNode draftVersion(String id) throws IOException {
    return client.request(
        "POST", "document-versions/" + CareFlowClient.id(id) + "/draft", null, null);
  }

  public JsonNode knowledgeAuthorization(String id) throws IOException {
    return client.request(
        "GET", "knowledge-bases/" + CareFlowClient.id(id) + "/authorization", null, null);
  }

  public JsonNode documentAuthorization(String id) throws IOException {
    return client.request(
        "GET", "documents/" + CareFlowClient.id(id) + "/authorization", null, null);
  }

  public JsonNode updateKnowledge(String id, KnowledgeAttributes change) throws IOException {
    return client.request("PUT", "knowledge-bases/" + CareFlowClient.id(id) + "", change, null);
  }

  public JsonNode updateDocumentMetadata(String id, DocumentMetadata change) throws IOException {
    return client.request("PUT", "documents/" + CareFlowClient.id(id) + "/metadata", change, null);
  }

  public JsonNode updateKnowledgePermissions(String id, PermissionChange change)
      throws IOException {
    return client.request(
        "PUT", "knowledge-bases/" + CareFlowClient.id(id) + "/permissions", change, null);
  }

  public JsonNode updateDocumentPermissions(String id, PermissionChange change) throws IOException {
    return client.request(
        "PUT", "documents/" + CareFlowClient.id(id) + "/permissions", change, null);
  }

  public JsonNode updateMember(String id, MemberChange change) throws IOException {
    return client.request("PUT", "members/" + CareFlowClient.id(id) + "", change, null);
  }

  public JsonNode members() throws IOException {
    return client.request("GET", "members", null, null);
  }

  public JsonNode createMember(String name, String role) throws IOException {
    return client.request("POST", "members", Map.of("name", name, "role", role), null);
  }

  public JsonNode knowledgeState(String id, String status, long revision) throws IOException {
    return client.request(
        "PUT",
        "knowledge-bases/" + CareFlowClient.id(id) + "/state",
        Map.of("status", status, "revision", revision),
        null);
  }

  public void deleteDocument(String id, long revision) throws IOException {
    client.request(
        "DELETE", "documents/" + CareFlowClient.id(id) + "?revision=" + revision, null, null);
  }

  public JsonNode documentMetadataHistory(String id, int page) throws IOException {
    if (page < 0) throw new IllegalArgumentException("Negative page");
    return client.request(
        "GET", "documents/" + CareFlowClient.id(id) + "/metadata-history?page=" + page, null, null);
  }

  public JsonNode replaceDocument(
      String id, java.nio.file.Path file, String key, Long billingRevision) throws IOException {
    if (billingRevision != null && billingRevision < 0) throw new IllegalArgumentException();
    return client.uploadTo(
        "documents/"
            + CareFlowClient.id(id)
            + "/versions"
            + (billingRevision == null ? "" : "?billing_revision=" + billingRevision),
        file,
        key);
  }

  /** Writes to a caller-owned sink; an interrupted transfer can leave partial bytes. */
  public long downloadSource(String version, java.io.OutputStream sink) throws IOException {
    return client.downloadTo("document-versions/" + CareFlowClient.id(version) + "/source", sink);
  }
}
