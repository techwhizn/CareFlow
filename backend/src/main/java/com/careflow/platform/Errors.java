package com.careflow.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e, HttpServletRequest r) {
    return error(e.status, e.code, e.getMessage(), r);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return error(400, "INVALID_ARGUMENT", "参数不合法，请检查输入", r);
  }

  @ExceptionHandler(
      org.springframework.web.method.annotation.HandlerMethodValidationException.class)
  ResponseEntity<?> methodValidation(
      org.springframework.web.method.annotation.HandlerMethodValidationException e,
      HttpServletRequest r) {
    return e.isForReturnValue() ? failure(e, r) : invalid(e, r);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> size(Exception e, HttpServletRequest r) {
    return error(413, "FILE_TOO_LARGE", "文件不能超过 50 MiB", r);
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<?> duplicate(Exception e, HttpServletRequest r) {
    return error(409, "CONFLICT", "重复请求或资源冲突", r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> failure(Exception e, HttpServletRequest r) {
    log.warn(
        "request_id={} error_type={}", r.getAttribute("request_id"), e.getClass().getSimpleName());
    return error(503, "SERVICE_UNAVAILABLE", "服务暂不可用，请使用请求 ID 排查服务日志", r);
  }

  private ResponseEntity<?> error(int status, String code, String message, HttpServletRequest r) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "request_id",
                String.valueOf(r.getAttribute("request_id"))));
  }
}
