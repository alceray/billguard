package dev.billguard.coreapi.common;

import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:${error.path:/error}}")
    ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = statusAttribute instanceof Integer status ? status : 500;
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = status == HttpStatus.NOT_FOUND ? "Not found" :
            status.is4xxClientError() ? status.getReasonPhrase() : "Internal server error";
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
