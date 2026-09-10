package com.careflow.platform;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException hidden() {
    return new ApiException(404, "NOT_ACCESSIBLE", "对象不存在或不可访问");
  }

  public static ApiException conflict() {
    return new ApiException(409, "VERSION_CONFLICT", "版本已变化，请刷新后重试");
  }
}
