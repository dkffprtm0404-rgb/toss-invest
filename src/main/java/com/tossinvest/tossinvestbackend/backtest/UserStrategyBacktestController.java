package com.tossinvest.tossinvestbackend.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.web.bind.annotation.*;

/** Strict parsing is local to this new endpoint so legacy API deserialization remains unchanged. */
@RestController
@RequestMapping("/api/backtest")
public class UserStrategyBacktestController {
    private final UserStrategyBacktestService service;
    private final StrategyJson json;

    public UserStrategyBacktestController(UserStrategyBacktestService service, StrategyJson json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping(value = "/run-strategy", consumes = "application/json")
    public UserStrategyBacktestResult run(@RequestBody String body) throws JsonProcessingException {
        // Read decimal tokens directly into BigDecimal, avoiding an intermediate Double-valued JSON tree.
        UserStrategyBacktestRequest request = json.read(body, UserStrategyBacktestRequest.class);
        return service.run(request);
    }
}
