package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tossinvest.tossinvestbackend.strategy.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/strategy-assistant")
public class StrategyAssistantController {
    private final CodexClient codex;
    private final StrategyAssistantService service;
    private final StrategyJson json;
    public StrategyAssistantController(CodexClient codex, StrategyAssistantService service, StrategyJson json) {
        this.codex = codex; this.service = service; this.json = json;
    }
    @GetMapping("/status")
    public CodexClient.Status status(HttpServletRequest request) {
        local(request); return codex.status();
    }
    @PostMapping(value = "/interpret", consumes = "application/json")
    public StrategyAssistantService.Draft interpret(HttpServletRequest request, @RequestBody String body) throws JsonProcessingException {
        local(request); limit(body);
        return service.interpret(json.read(body, StrategyAssistantService.Request.class));
    }
    @PostMapping(value = "/validate", consumes = "application/json")
    public StrategyAssistantService.Draft validate(HttpServletRequest request, @RequestBody String body) throws JsonProcessingException {
        local(request); limit(body);
        return service.validate(json.read(body, StrategyDefinition.class));
    }
    private static void limit(String body) {
        if (body.length() > 64000) throw new StrategyJson.InvalidRequestException();
    }
    private static boolean loopback(String host) {
        return host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")
                .contains(host.toLowerCase(java.util.Locale.ROOT));
    }
    public static void local(HttpServletRequest request) {
        if (!loopback(request.getRemoteAddr()) || !loopback(request.getServerName()))
            throw new AssistantException("LOCAL_ONLY");
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!request.getScheme().equals(uri.getScheme()) || !request.getServerName().equalsIgnoreCase(uri.getHost())
                        || request.getServerPort() != port || uri.getUserInfo() != null)
                    throw new AssistantException("LOCAL_ONLY");
            } catch (IllegalArgumentException e) { throw new AssistantException("LOCAL_ONLY"); }
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site)) throw new AssistantException("LOCAL_ONLY");
        if (!request.getMethod().equals("GET") && !"1".equals(request.getHeader("X-Strategy-Local")))
            throw new AssistantException("LOCAL_ONLY");
    }
}
