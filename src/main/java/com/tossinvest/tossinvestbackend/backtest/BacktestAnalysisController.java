package com.tossinvest.tossinvestbackend.backtest;

import org.springframework.web.bind.annotation.*;
import com.tossinvest.tossinvestbackend.strategy.assistant.StrategyAssistantController;
import com.tossinvest.tossinvestbackend.strategy.assistant.StrategyAssistantErrors;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/backtest/runs/{id}")
public class BacktestAnalysisController {
    private final SavedBacktestService backtests;
    private final BacktestExplanationService explanations;

    public BacktestAnalysisController(SavedBacktestService backtests, BacktestExplanationService explanations) {
        this.backtests = backtests; this.explanations = explanations;
    }

    @GetMapping("/analysis")
    public BacktestAnalysis analysis(@PathVariable long id) { return BacktestAnalysis.from(backtests.get(id)); }

    @GetMapping("/explanation")
    public BacktestExplanationService.Explanation explanation(@PathVariable long id, HttpServletRequest request) {
        StrategyAssistantController.local(request);
        return explanations.get(id);
    }

    @PostMapping("/explanation")
    public BacktestExplanationService.Explanation generate(@PathVariable long id, HttpServletRequest request) {
        StrategyAssistantController.local(request);
        return explanations.generate(id);
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<StrategyAssistantErrors.Error> assistantError(AssistantException exception) {
        return StrategyAssistantErrors.response(exception);
    }
}
