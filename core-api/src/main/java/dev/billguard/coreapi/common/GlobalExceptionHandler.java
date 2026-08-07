package dev.billguard.coreapi.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, List<String>> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.computeIfAbsent(error.getField(), ignored -> new java.util.ArrayList<>())
                .add(error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Validation error",
            "details", details
        ));
    }

    @ExceptionHandler(HttpException.class)
    ResponseEntity<Map<String, Object>> handleHttp(HttpException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.getMessage());
        if (exception.code() != null) {
            body.put("code", exception.code());
        }
        return ResponseEntity.status(exception.status()).body(body);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Internal server error"));
    }
}
