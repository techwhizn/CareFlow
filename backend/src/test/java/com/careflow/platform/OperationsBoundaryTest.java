package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class OperationsBoundaryTest extends ContentTestSupport {
  @Autowired OperationsStatusService operations;

  @Test
  void operationsRoleCannotReadContentEvenWithExplicitGrantsAndOwnership() throws Exception {
    String member = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Synthetic operator','OPS')",
        member,
        tenant);
    String credential = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    var operator = auth.authenticate(credential);
    String kb =
        Db.str(
            db.one(
                "SELECT kb_id FROM documents WHERE id=(SELECT document_id FROM document_versions WHERE id=?)",
                version),
            "kb_id");
    db.exec("UPDATE knowledge_bases SET owner_id=? WHERE id=?", member, kb);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        member);
    assertThat(auth.granted(operator, kb, "read")).isFalse();
    assertThatThrownBy(() -> auth.version(operator, version, "read"))
        .isInstanceOf(ApiException.class);
    for (String path :
        List.of(
            "/knowledge-bases",
            "/knowledge-bases/" + kb,
            "/documents/" + Db.id(),
            "/document-versions/" + version + "/chunks",
            "/document-versions/" + version + "/source",
            "/jobs",
            "/jobs/" + Db.id(),
            "/answers",
            "/answers/" + Db.id() + "/feedback",
            "/retrieval/search",
            "/applications",
            "/model-profiles",
            "/audit",
            "/usage",
            "/cleanup-requests",
            "/credentials",
            "/members")) {
      for (HttpMethod method :
          List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE))
        mvc.perform(
                request(method, "/api/v1" + path)
                    .header("Authorization", credential)
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isNotFound());
    }
    mvc.perform(get("/api/v1/me").header("Authorization", credential))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("OPS"));
    mvc.perform(post("/api/v1/operations/status").header("Authorization", credential))
        .andExpect(status().isNotFound());
  }

  @Test
  void statusOnlyContainsTenantScopedAggregateMetadata() throws Exception {
    String member = Db.id(), other = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Operator','OPS')", member, tenant);
    String credential = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    for (String t : List.of(tenant, other))
      db.exec(
          "INSERT INTO jobs(id,tenant_id,version_id,kind,state,request_key) VALUES(?,?,?,'PARSE','QUEUED',?)",
          Db.id(),
          t,
          version,
          Db.id());
    mvc.perform(get("/api/v1/operations/status").header("Authorization", credential))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs.length()").value(1))
        .andExpect(jsonPath("$.jobs[0].count").value(1))
        .andExpect(jsonPath("$.jobs[0].kind").value("PARSE"))
        .andExpect(jsonPath("$.jobs[0].version_id").doesNotExist())
        .andExpect(jsonPath("$.jobs[0].lease_token").doesNotExist());
    assertThatThrownBy(
            () -> operations.status(new Identity.Actor(tenant, member, "MEMBER", "USER")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> operations.status(new Identity.Actor(tenant, member, "APP", "APPLICATION")))
        .isInstanceOf(ApiException.class);
    assertThat(operations.status(actor).toString()).doesNotContain(version, "fixture.csv");
  }

  @Test
  void applicationGrantAloneCannotBypassPublishedBindingOnContentEndpoints() throws Exception {
    String app = Db.id();
    String kb =
        Db.str(
            db.one(
                "SELECT kb_id FROM documents WHERE id=(SELECT document_id FROM document_versions WHERE id=?)",
                version),
            "kb_id");
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,published) VALUES(?,?,'Synthetic app','',TRUE)",
        app,
        tenant);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        app);
    db.exec("UPDATE document_versions SET ever_published=TRUE,state='READY' WHERE id=?", version);
    String key = "Bearer " + auth.credential(tenant, app, "APP", null);
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/chunks").header("Authorization", key))
        .andExpect(status().isNotFound());
    db.exec(
        "INSERT INTO application_bindings(tenant_id,application_id,kb_id) VALUES(?,?,?)",
        tenant,
        app,
        kb);
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/chunks").header("Authorization", key))
        .andExpect(status().isOk());
    db.exec("DELETE FROM application_bindings WHERE application_id=?", app);
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/contexts").header("Authorization", key))
        .andExpect(status().isNotFound());
  }
}
