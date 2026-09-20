package com.agentdesk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 统一错误出口：对外只暴露可读消息，堆栈与内部细节仅写日志。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 业务侧主动抛出的状态异常保持原样（消息本就是面向用户的中文说明） */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("status", e.getStatusCode().value(), "message", e.getReason() == null ? e.getStatusCode().toString() : e.getReason()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream().findFirst()
        .map(FieldError::getDefaultMessage).orElse("请求参数不合法");
    return ResponseEntity.badRequest().body(Map.of("status", 400, "message", detail));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
    log.warn("上传超出大小限制", e);
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("status", 413, "message", "上传内容超出大小限制（单文件 50MB，单次请求 300MB）"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    log.error("未处理异常", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("status", 500, "message", "服务器内部错误，请稍后重试"));
  }
}
