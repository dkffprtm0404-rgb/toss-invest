package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestController;
import com.tossinvest.tossinvestbackend.backtest.BacktestAnalysisController;
import com.tossinvest.tossinvestbackend.backtest.UserStrategyBacktestEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Shared error contract, scoped to user strategy APIs only. */
@RestControllerAdvice(assignableTypes = {UserStrategyBacktestController.class, SavedStrategyController.class, BacktestAnalysisController.class, com.tossinvest.tossinvestbackend.portfolio.PortfolioController.class, com.tossinvest.tossinvestbackend.composite.CompositeController.class, com.tossinvest.tossinvestbackend.comparison.ComparisonController.class, com.tossinvest.tossinvestbackend.strategypaper.StrategyPaperController.class})
public class StrategyApiErrors {
    public record ApiError(String code, String message, List<StrategyValidator.Issue> issues, Long timestamp) { }

    @ExceptionHandler(com.tossinvest.tossinvestbackend.strategypaper.PaperConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError paperConflict(RuntimeException exception) {return new ApiError("PAPER_CONFLICT",exception.getMessage(),List.of(),null);}

    @ExceptionHandler(com.tossinvest.tossinvestbackend.strategypaper.StrategyPaperService.MarketUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError paperMarket(RuntimeException exception) {return new ApiError("MARKET_UNAVAILABLE",exception.getMessage(),List.of(),null);}

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

    @ExceptionHandler(SavedStrategyService.NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError missing(SavedStrategyService.NotFoundException exception) {
        return new ApiError("NOT_FOUND", exception.getMessage(), List.of(), null);
    }

    @ExceptionHandler(SavedStrategyService.VersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(SavedStrategyService.VersionConflictException exception) {
        return new ApiError("VERSION_CONFLICT", exception.getMessage(), List.of(), null);
    }

    @ExceptionHandler({JsonProcessingException.class, HttpMessageNotReadableException.class,
            StrategyJson.InvalidRequestException.class, MethodArgumentTypeMismatchException.class})
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
}
