package com.careflow.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class HealthTest extends ContentTestSupport {
  @Test
  void probesAndMissingRoutesUseAccurateStatus() throws Exception {
    mvc.perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("DATABASE"));
    mvc.perform(get("/route-that-does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(post("/health/live")).andExpect(status().isMethodNotAllowed());
  }
}
