package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidationException;
import com.tossinvest.tossinvestbackend.strategy.StrategyValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Strict parsing is local to this new endpoint so legacy API deserialization remains unchanged. */
@RestController
@RequestMapping("/api/backtest")
public class UserStrategyBacktestController {
    private final UserStrategyBacktestService service;
    private final ObjectReader requestReader;

    public UserStrategyBacktestController(UserStrategyBacktestService service, ObjectMapper mapper) {
        this.service = service;
        ObjectMapper strict = mapper.copy();
        for (LogicalType type : List.of(LogicalType.Integer, LogicalType.Float)) {
            strict.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        }
        strict.coercionConfigFor(LogicalType.Enum).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        this.requestReader = strict.readerFor(UserStrategyBacktestRequest.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @PostMapping(value = "/run-strategy", consumes = "application/json")
    public UserStrategyBacktestResult run(@RequestBody String json) throws JsonProcessingException {
        // Read decimal tokens directly into BigDecimal, avoiding an intermediate Double-valued JSON tree.
        UserStrategyBacktestRequest request = requestReader.readValue(json);
        if (request == null) throw new InvalidRequestException();
        return service.run(request);
    }

    public record ApiError(String code, String message, List<StrategyValidator.Issue> issues, Long timestamp) { }

    @ExceptionHandler(StrategyValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation(StrategyValidationException exception) {
        return new ApiError("VALIDATION_ERROR", exception.getMessage(), exception.getIssues(), null);
    }

    @ExceptionHandler(UserStrategyBacktestEngine.DataException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiError data(UserStrategyBacktestEngine.DataException exception) {
        return new ApiError("INVALID_DATA", exception.getMessage(), List.of(), exception.getTimestamp());
    }

    @ExceptionHandler({JsonProcessingException.class, HttpMessageNotReadableException.class, InvalidRequestException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError malformed(Exception exception) {
        String path = "request";
        if (exception instanceof JsonMappingException mapping) {
            StringBuilder value = new StringBuilder();
            for (JsonMappingException.Reference reference : mapping.getPath()) {
                if (reference.getFieldName() != null) {
                    if (!value.isEmpty()) value.append('.');
                    value.append(reference.getFieldName());
                } else if (reference.getIndex() >= 0) value.append('[').append(reference.getIndex()).append(']');
            }
            if (mapping instanceof InvalidTypeIdException) value.append(value.isEmpty() ? "type" : ".type");
            if (!value.isEmpty()) path = value.toString();
        }
        return new ApiError("INVALID_REQUEST", "Use the documented JSON fields, condition types, enums and numeric types.",
                List.of(new StrategyValidator.Issue(path, "INVALID", "Check the JSON value and supported schema at " + path + ".")), null);
    }

    private static class InvalidRequestException extends IllegalArgumentException { }
}
