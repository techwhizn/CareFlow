package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Public wire-schema drift must be reviewed alongside SDK changes. */
class PublicApiContractTest extends ContentTestSupport {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void publicContractMatchesReviewedSnapshot() throws Exception {
    var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var spec = JSON.readTree(response.getResponse().getContentAsString());
    ObjectNode snapshot = JSON.createObjectNode();
    ObjectNode paths = snapshot.putObject("paths");
    spec.path("paths")
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey().startsWith("/api/v1/"))
                paths.set(entry.getKey(), entry.getValue());
            });
    assertThat(paths.size()).isGreaterThan(50);
    ObjectNode schemas = snapshot.putObject("schemas");
    var queue = new ArrayDeque<JsonNode>();
    var visited = new HashSet<String>();
    queue.add(paths);
    while (!queue.isEmpty()) {
      JsonNode node = queue.remove();
      if (node.isObject() && node.has("$ref")) {
        String ref = node.get("$ref").asText();
        assertThat(ref).startsWith("#/components/schemas/");
        if (visited.add(ref)) {
          JsonNode schema = spec.at(ref.substring(1));
          assertThat(schema.isMissingNode()).as(ref).isFalse();
          schemas.set(ref.substring(ref.lastIndexOf('/') + 1), schema);
          queue.add(schema);
        }
      }
      node.elements().forEachRemaining(queue::add);
    }
    // Explicit local maintenance mode; CI never sets this property.
    String update = System.getProperty("careflow.contract.snapshot");
    if (update != null) {
      Path target = Path.of(update);
      Files.createDirectories(target.getParent());
      Files.writeString(
          target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + "\n");
    }
    try (var input = getClass().getResourceAsStream("/contracts/public-api.json")) {
      if (update != null) return;
      assertThat(input).as("Reviewed public API snapshot must exist").isNotNull();
      assertThat(snapshot).isEqualTo(JSON.readTree(input));
    }
  }
}
