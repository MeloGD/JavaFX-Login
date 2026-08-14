package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * The request was refused. {@code Error} on the wire; named ErrorResponse in Java so that it does
 * not shadow {@link java.lang.Error} wherever it is caught or imported.
 */
public record ErrorResponse(ErrorCode code) implements Response {

  public ErrorResponse {
    Objects.requireNonNull(code, "code");
  }
}
