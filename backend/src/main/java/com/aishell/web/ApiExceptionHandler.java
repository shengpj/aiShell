package com.aishell.web;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Returns RFC 7807 bodies with a `detail` field so the webview can surface the reason. */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  ProblemDetail statusException(ResponseStatusException exception) {
    String detail =
        exception.getReason() == null
            ? exception.getStatusCode().toString()
            : exception.getReason();
    return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), detail);
  }
}
