package com.tossinvest.tossinvestbackend.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.backtest.SavedBacktestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SavedStrategyController {
    private final SavedStrategyService strategies;
    private final SavedBacktestService backtests;
    private final StrategyJson json;

    public SavedStrategyController(SavedStrategyService strategies, SavedBacktestService backtests, StrategyJson json) {
        this.strategies = strategies;
        this.backtests = backtests;
        this.json = json;
    }

    @PostMapping(value = "/strategies", consumes = "application/json")
    public ResponseEntity<SavedStrategyService.SavedVersion> create(@RequestBody String body) throws JsonProcessingException {
        var saved = strategies.create(json.read(body, StrategyDefinition.class));
        return ResponseEntity.created(URI.create("/api/strategies/" + saved.id())).body(saved);
    }

    @PutMapping(value = "/strategies/{id}", consumes = "application/json")
    public SavedStrategyService.SavedVersion update(@PathVariable long id, @RequestBody String body) throws JsonProcessingException {
        return strategies.update(id, json.read(body, SavedStrategyService.UpdateRequest.class));
    }

    @GetMapping("/strategies")
    public List<SavedStrategyService.SavedVersion> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return strategies.list(page, size);
    }

    @GetMapping("/strategies/{id}")
    public SavedStrategyService.SavedVersion get(@PathVariable long id) { return strategies.get(id); }

    @GetMapping("/strategies/{id}/versions")
    public List<SavedStrategyService.SavedVersion> versions(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return strategies.versions(id, page, size);
    }

    @GetMapping("/strategies/{id}/versions/{version}")
    public SavedStrategyService.SavedVersion version(@PathVariable long id, @PathVariable int version) {
        return strategies.version(id, version);
    }

    @PostMapping(value = "/strategies/{id}/backtests", consumes = "application/json")
    public ResponseEntity<SavedBacktestService.RunDetail> run(@PathVariable long id, @RequestBody String body) throws JsonProcessingException {
        var run = backtests.run(id, json.read(body, SavedBacktestService.RunRequest.class));
        return ResponseEntity.created(URI.create("/api/backtest/runs/" + run.id())).body(run);
    }

    @GetMapping("/strategies/{id}/backtests")
    public List<SavedBacktestService.RunSummary> runs(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return backtests.list(id, page, size);
    }

    @GetMapping("/backtest/runs/{id}")
    public SavedBacktestService.RunDetail run(@PathVariable long id) { return backtests.get(id); }
}
