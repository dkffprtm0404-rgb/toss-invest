package com.tossinvest.tossinvestbackend.composite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class CompositeController {
    private final CompositeService service;
    private final StrategyJson json;
    public CompositeController(CompositeService service,StrategyJson json){this.service=service;this.json=json;}
    @PostMapping(value="/api/strategies/{id}/composite-backtests",consumes="application/json")
    public CompositeService.Detail run(@PathVariable long id,@RequestBody String body)throws JsonProcessingException {
        if(body.length()>2000000)throw new StrategyJson.InvalidRequestException();
        return service.run(id,json.read(body,CompositeRequest.class));
    }
    @GetMapping("/api/strategies/{id}/composite-backtests")
    public List<CompositeService.Summary> list(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(id,page,size);}
    @GetMapping("/api/composite/runs/{id}") public CompositeService.Detail get(@PathVariable long id){return service.get(id);}
    @DeleteMapping("/api/composite/runs/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id){service.delete(id);}
}
