package com.worktrack.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Authenticated but not allowed (or a bad CSRF token) -> HTTP 403 with JSON. */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String message = accessDeniedException instanceof CsrfException
                ? "Invalid or missing CSRF token. Reload the page and try again"
                : "You do not have permission to perform this action";
        ApiErrorWriter.write(response, objectMapper, HttpStatus.FORBIDDEN, message, request.getRequestURI());
    }
}
