package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:careflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "careflow.scheduling=false",
      "careflow.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "careflow.internal-token=internal-testing-secret-at-least-32-chars",
      "careflow.bootstrap-token=bootstrap-testing-secret-at-least-32-chars"
    })
@AutoConfigureMockMvc
abstract class ContentTestSupport {
  @Autowired QueryReservationService reservations;
  @Autowired Db db;
  @Autowired Identity auth;
  @Autowired MockMvc mvc;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;
  String tenant, version, token;
  Actor actor;

  @BeforeEach
  void setup() {
    tenant = Db.id();
    String member = Db.id(), kb = Db.id(), document = Db.id();
    version = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,'quality fixture')", tenant);
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Owner','OWNER')", member, tenant);
    token = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    actor = auth.authenticate(token);
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,'Quality','',?)",
        kb,
        tenant,
        member);
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,'Synthetic')",
        document,
        tenant,
        kb);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state) VALUES(?,?,?,'fixture','fixture.csv','digest','PARSED')",
        version,
        tenant,
        document);
  }

  String reservedRequest(Actor who) {
    return reservations.reserve(who, Db.id(), who.app() ? who.subject() : "");
  }

  String chunk(int ordinal, String content, int tokens, boolean enabled, String location) {
    String id = Db.id();
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        tenant,
        version,
        ordinal,
        content,
        content,
        location,
        tokens,
        enabled);
    return id;
  }
}
