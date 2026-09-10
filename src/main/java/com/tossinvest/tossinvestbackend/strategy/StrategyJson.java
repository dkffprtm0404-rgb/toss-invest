package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import java.util.List;

/** Strict strategy JSON without changing legacy application-wide coercion settings. */
@Component
public class StrategyJson {
    private final ObjectMapper mapper;

    public StrategyJson(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        for (LogicalType type : List.of(LogicalType.Integer, LogicalType.Float)) {
            this.mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        }
        this.mapper.coercionConfigFor(LogicalType.Enum).setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public <T> T read(String json, Class<T> type) throws JsonProcessingException {
        T value = mapper.readValue(json, type);
        if (value == null) throw new InvalidRequestException();
        return value;
    }

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize history snapshot", e); }
    }

    public <T> T stored(String json, Class<T> type) {
        try { return read(json, type); }
        catch (JsonProcessingException | InvalidRequestException e) {
            throw new IllegalStateException("Could not read stored history snapshot", e);
        }
    }

    public static class InvalidRequestException extends IllegalArgumentException { }
}
