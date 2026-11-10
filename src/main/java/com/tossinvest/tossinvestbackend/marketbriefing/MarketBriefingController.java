package com.tossinvest.tossinvestbackend.marketbriefing;

import com.tossinvest.tossinvestbackend.strategy.assistant.StrategyAssistantController;
import com.tossinvest.tossinvestbackend.strategy.assistant.AssistantException;
import com.tossinvest.tossinvestbackend.strategy.assistant.StrategyAssistantErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-briefings")
public class MarketBriefingController {
    private final MarketBriefingService service;
    public MarketBriefingController(MarketBriefingService service){this.service=service;}
    @GetMapping("/latest") public MarketBriefing latest(){return service.latest();}
    @GetMapping public List<MarketBriefing> history(){return service.history();}
    @GetMapping("/{date}") public MarketBriefing get(@PathVariable LocalDate date){return service.get(date);}
    @PostMapping("/today") public MarketBriefing generate(HttpServletRequest request){StrategyAssistantController.local(request);return service.generateToday();}
    @ExceptionHandler(MarketBriefingService.NotFound.class) public ResponseEntity<Void> absent(){return ResponseEntity.notFound().build();}
    @ExceptionHandler(MarketBriefingService.TooEarly.class) public ResponseEntity<Map<String,String>> early(){return ResponseEntity.status(409).body(Map.of("message","오전 8:30 한국시간 이후에 오늘 요약을 생성할 수 있습니다."));}
    @ExceptionHandler(AssistantException.class) public ResponseEntity<StrategyAssistantErrors.Error> assistant(AssistantException e){return StrategyAssistantErrors.response(e);}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(503).body(Map.of("message","시장 자료 또는 휴장일 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."));}
}
