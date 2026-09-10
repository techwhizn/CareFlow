package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DatabaseClockTest {
  @Test
  void databaseClockKeepsUnrelatedOptionsAndReplacesConflictingTimeSettings() {
    String result =
        DatabaseClock.utcUrl(
            "jdbc:mysql://localhost/db?useSSL=false&serverTimezone=Asia%2FShanghai&preserveInstants=false&connectionTimeZone=LOCAL");
    assertThat(result)
        .contains(
            "useSSL=false",
            "connectionTimeZone=%2B00:00",
            "forceConnectionTimeZoneToSession=true",
            "preserveInstants=true")
        .doesNotContain("LOCAL", "serverTimezone", "Asia");
    assertThat(DatabaseClock.utcUrl(result)).isEqualTo(result);
    assertThat(DatabaseClock.utcUrl("jdbc:h2:mem:test")).isEqualTo("jdbc:h2:mem:test");
  }
}
