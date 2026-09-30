package com.tossinvest.tossinvestbackend.strategypaper;
import com.tossinvest.tossinvestbackend.strategy.StrategyJson;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;

@RestController @RequestMapping("/api/strategy-paper/runs")
public class StrategyPaperController {
    private final StrategyPaperService service;private final StrategyJson json;
    public StrategyPaperController(StrategyPaperService service,StrategyJson json){this.service=service;this.json=json;}
    @PostMapping public ResponseEntity<StrategyPaperService.Detail> create(@RequestBody String body) throws Exception {var d=service.create(json.read(body,PaperDefinition.class));return ResponseEntity.created(URI.create("/api/strategy-paper/runs/"+d.id())).body(d);}
    @GetMapping public List<StrategyPaperService.Detail> list(@RequestParam(required=false) Long strategyId,@RequestParam(required=false) String symbol,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(strategyId,symbol,status,page,size);}
    @GetMapping("/{id}") public StrategyPaperService.Detail get(@PathVariable long id){return service.get(id);}
    @PostMapping("/{id}/refresh") public StrategyPaperService.Detail refresh(@PathVariable long id){return service.refresh(id);}
    @PostMapping("/{id}/stop") public StrategyPaperService.Detail stop(@PathVariable long id){return service.stop(id);}
    @GetMapping("/{id}/{type:trades|logs|equity}") public List<?> history(@PathVariable long id,@PathVariable String type,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.history(id,type,page,size);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable long id){service.delete(id);return ResponseEntity.noContent().build();}
}
