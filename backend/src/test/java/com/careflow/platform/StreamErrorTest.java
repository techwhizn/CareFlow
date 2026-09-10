package com.careflow.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class StreamErrorTest {
  @RestController
  static class Fixture {
    @GetMapping(value = "/closed", produces = "text/event-stream")
    String closed() throws AsyncRequestNotUsableException {
      throw new AsyncRequestNotUsableException("Synthetic disconnected response");
    }

    @GetMapping("/failure")
    String failure() {
      throw new IllegalStateException("Synthetic private error");
    }
  }

  @Test
  void disconnectedResponsesAreNotWrittenAgainButOtherFailuresRemainVisible() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new Errors()).build();
    mvc.perform(get("/closed")).andExpect(content().string(""));
    mvc.perform(get("/failure"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
  }
}
