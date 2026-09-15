package com.tossinvest.tossinvestbackend.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class PortfolioController {
    private final PortfolioService service;private final StrategyJson json;
    public PortfolioController(PortfolioService service,StrategyJson json){this.service=service;this.json=json;}
    @PostMapping(value="/api/strategies/{id}/portfolio-backtests",consumes="application/json")
    public PortfolioService.Detail run(@PathVariable long id,@RequestBody String body)throws JsonProcessingException {
        if(body.length()>2000000)throw new StrategyJson.InvalidRequestException();return service.run(id,json.read(body,PortfolioRequest.class));
    }
    @GetMapping("/api/strategies/{id}/portfolio-backtests")
    public List<PortfolioService.Summary> list(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(id,page,size);}
    @GetMapping("/api/portfolio/runs/{id}") public PortfolioService.Detail get(@PathVariable long id){return service.get(id);}
    @DeleteMapping("/api/portfolio/runs/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id){service.delete(id);}
}
