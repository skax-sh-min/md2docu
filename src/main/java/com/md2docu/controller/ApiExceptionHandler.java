package com.md2docu.controller;

import com.md2docu.util.PageLayout;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = Logger.getLogger(ApiExceptionHandler.class.getName());

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message;
        if (exception instanceof ResponseStatusException response && response.getReason() != null) {
            message = response.getReason();
        } else if (exception instanceof HttpMessageNotReadableException) {
            message = "요청 본문은 올바른 JSON 객체여야 하며 필드 타입을 확인해 주세요.";
        } else if (exception instanceof MaxUploadSizeExceededException) {
            message = "업로드 파일 크기가 최대 허용량을 초과했습니다.";
        } else if (exception instanceof MissingServletRequestPartException missing) {
            message = "필수 파일 '" + missing.getRequestPartName() + "'이 누락되었습니다.";
        } else if (exception instanceof HttpMediaTypeNotSupportedException) {
            message = "지원하지 않는 Content-Type입니다. JSON 또는 multipart 요청 형식을 확인해 주세요.";
        } else {
            message = "요청을 처리할 수 없습니다 (HTTP " + status.value() + ").";
        }
        if (status.is5xxServerError()) log.log(Level.WARNING, message, exception);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.putAll(headers);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, Map.of("error", message), jsonHeaders, status, request);
    }

    @ExceptionHandler(PageLayout.InvalidLayoutException.class)
    public ResponseEntity<Map<String, String>> invalidLayout(PageLayout.InvalidLayoutException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> invalidMultipart(MultipartException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "올바른 multipart 파일 요청이 아닙니다."));
    }

    @ExceptionHandler({IOException.class, UncheckedIOException.class})
    public ResponseEntity<Map<String, String>> ioFailure(Exception exception) {
        log.log(Level.SEVERE, "문서 처리 중 I/O 오류가 발생했습니다.", exception);
        String message = exception.getMessage();
        return ResponseEntity.internalServerError().body(Map.of("error",
            message == null || message.isBlank() ? "문서 처리 중 서버 I/O 오류가 발생했습니다." : message));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> internalFailure(RuntimeException exception) {
        log.log(Level.SEVERE, "문서 처리 중 내부 오류가 발생했습니다.", exception);
        return ResponseEntity.internalServerError().body(Map.of("error", "문서 처리 중 서버 내부 오류가 발생했습니다."));
    }
}
