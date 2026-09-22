package com.tossinvest.tossinvestbackend.comparison;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/comparisons")
public class ComparisonController {
    private final ComparisonService service;private final StrategyJson json;
    public ComparisonController(ComparisonService service,StrategyJson json){this.service=service;this.json=json;}
    @PostMapping(consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public ComparisonService.Detail run(@RequestBody String body)throws JsonProcessingException {
        if(body.length()>2_000_000)throw new StrategyJson.InvalidRequestException();
        return service.run(json.read(body,ComparisonService.Request.class));
    }
    @GetMapping public List<ComparisonService.Summary> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(page,size);}
    @GetMapping("/{id}") public ComparisonService.Detail get(@PathVariable long id){return service.get(id);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable long id){service.delete(id);}
}
