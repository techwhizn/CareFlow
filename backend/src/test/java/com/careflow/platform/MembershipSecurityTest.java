package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MembershipSecurityTest extends ContentTestSupport {
  @Autowired MembershipService memberships;

  @Test
  void memberResponsesNeverExposeEncryptedMfaSecret() {
    db.exec(
        "UPDATE members SET mfa_secret=? WHERE tenant_id=? AND id=?",
        "ciphertext",
        tenant,
        actor.subject());
    var view =
        memberships.list(actor).stream()
            .filter(row -> actor.subject().equals(Db.str(row, "id")))
            .findFirst()
            .orElseThrow();
    assertThat(view).doesNotContainKey("mfa_secret");
  }
}
