package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestControllerAdvice(assignableTypes = StrategyAssistantController.class)
public class StrategyAssistantErrors {
    public record Error(String code, String message, List<StrategyValidator.Issue> issues) { }
    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<Error> assistant(AssistantException e) {
        int status = switch (e.code()) {
            case "LOCAL_ONLY" -> 403;
            case "CODEX_BUSY", "CODEX_LIMIT_REACHED" -> 429;
            case "CODEX_TIMEOUT" -> 504;
            case "CODEX_INVALID_RESPONSE", "CODEX_FAILED" -> 502;
            default -> 503;
        };
        return ResponseEntity.status(status).body(new Error(e.code(), e.getMessage(), List.of()));
    }
    @ExceptionHandler(StrategyValidationException.class)
    public ResponseEntity<Error> validation(StrategyValidationException e) {
        return ResponseEntity.badRequest().body(new Error("VALIDATION_ERROR", "입력값을 확인해 주세요.", e.getIssues()));
    }
    @ExceptionHandler({JsonProcessingException.class, StrategyJson.InvalidRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Error> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "지원하는 전략 형식과 필드값을 확인해 주세요.", List.of()));
    }
}
