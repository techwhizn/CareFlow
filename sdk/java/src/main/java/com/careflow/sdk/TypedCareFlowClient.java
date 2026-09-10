package com.careflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Typed core views over the same transport and identity; the original JSON API remains available.
 */
public final class TypedCareFlowClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final CareFlowClient client;

  TypedCareFlowClient(CareFlowClient client) {
    this.client = client;
  }

  private <T> T decode(JsonNode value, Class<T> type) throws IOException {
    try {
      return JSON.treeToValue(value, type);
    } catch (Exception invalid) {
      throw new IOException("Invalid typed CareFlow response");
    }
  }

  private <T> List<T> list(JsonNode value, Class<T> type) throws IOException {
    if (!value.isArray()) throw new IOException("Invalid CareFlow collection response");
    var result = new java.util.ArrayList<T>();
    for (var row : value) result.add(decode(row, type));
    return List.copyOf(result);
  }

  public Responses.Me me() throws IOException {
    return decode(client.me(), Responses.Me.class);
  }

  public List<Responses.KnowledgeBase> knowledgeBases() throws IOException {
    return list(client.knowledgeBases(), Responses.KnowledgeBase.class);
  }

  public Responses.KnowledgeBase knowledgeBase(String id) throws IOException {
    return decode(client.knowledgeBase(id), Responses.KnowledgeBase.class);
  }

  public List<Responses.Document> documents(String kb, int page) throws IOException {
    return list(client.documents(kb, page), Responses.Document.class);
  }

  public List<Responses.DocumentVersion> documentVersions(String document) throws IOException {
    return list(client.documentVersions(document), Responses.DocumentVersion.class);
  }

  public List<Responses.Chunk> documentChunks(String version, int page) throws IOException {
    return list(client.documentChunks(version, page), Responses.Chunk.class);
  }

  public Responses.Job job(String id) throws IOException {
    return decode(client.job(id), Responses.Job.class);
  }

  public Responses.UploadResult upload(String kb, Path file, String key) throws IOException {
    return decode(client.upload(kb, file, key), Responses.UploadResult.class);
  }

  public Responses.SearchResult search(CareFlowClient.Query query, String key) throws IOException {
    return decode(client.search(query, key), Responses.SearchResult.class);
  }
}
