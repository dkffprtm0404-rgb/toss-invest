package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tossinvest.tossinvestbackend.backtest.BacktestExplanationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class CodexClient {
    private final CodexProcessFactory processes;
    private final ObjectMapper mapper;
    private final String model;
    private final long timeoutMillis;
    private final Semaphore active = new Semaphore(1);

    public CodexClient(CodexProcessFactory processes, ObjectMapper mapper,
                       @Value("${strategy.codex.model:gpt-5.6-terra}") String model,
                       @Value("${strategy.codex.timeout-millis:120000}") long timeoutMillis) {
        this.processes = processes;
        this.mapper = mapper;
        this.model = model;
        this.timeoutMillis = Math.max(1000, Math.min(timeoutMillis, 300000));
    }

    public record Status(boolean ready, String code, String message, String model) { }
    public Status status() {
        try {
            withSession(false, null, null, null);
            return new Status(true, "READY", "ChatGPT 구독 로그인 연결됨 · API 키 사용 안 함", model);
        } catch (AssistantException e) { return new Status(false, e.code(), e.getMessage(), model); }
    }

    public String interpret(String input) {
        return withSession(true, input, StrategyAssistantService.INSTRUCTIONS, "/codex/strategy-output.schema.json");
    }
    public String explain(String input) {
        return withSession(true, input, BacktestExplanationService.INSTRUCTIONS, "/codex/backtest-explanation.schema.json");
    }
    public String model() { return model; }

    private String withSession(boolean generate, String input, String instructions, String schemaResource) {
        if (!active.tryAcquire()) throw new AssistantException("CODEX_BUSY");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("toss-strategy-");
            try (CodexSession session = new CodexSession(processes.start(directory), mapper, generate ? timeoutMillis : 20000)) {
                session.rpc("initialize", Map.of("clientInfo", Map.of("name", "toss_strategy", "version", "1.0.0"),
                        "capabilities", Map.of("experimentalApi", true)));
                session.notify("initialized");
                requireSubscription(session.rpc("account/read", Map.of("refreshToken", false)));
                if (!generate) return "";
                JsonNode config = session.rpc("config/read", Map.of("includeLayers", false, "cwd", directory.toString())).path("config");
                // A user-defined OpenAI endpoint/provider may have different billing/auth semantics. Fail closed.
                if (!config.path("model_providers").path("openai").isMissingNode()
                        && !config.path("model_providers").path("openai").isNull())
                    throw new AssistantException("CODEX_API_KEY_NOT_ALLOWED");
                Map<String, Object> overrides = new LinkedHashMap<>();
                config.path("mcp_servers").fieldNames().forEachRemaining(name -> overrides.put("mcp_servers." + name + ".enabled", false));
                Map<String, Object> thread = new LinkedHashMap<>();
                thread.put("model", model); thread.put("modelProvider", "openai");
                thread.put("cwd", directory.toString()); thread.put("ephemeral", true);
                thread.put("approvalPolicy", "never"); thread.put("sandbox", "read-only");
                thread.put("environments", List.of()); thread.put("selectedCapabilityRoots", List.of());
                thread.put("config", overrides); thread.put("baseInstructions", instructions);
                thread.put("developerInstructions", "주어진 데이터와 출력 스키마에 맞는 JSON만 출력한다. 도구를 호출하거나 파일을 읽지 않는다.");
                String threadId = session.rpc("thread/start", thread).path("thread").path("id").asText();
                if (threadId.isBlank()) throw new AssistantException("CODEX_INVALID_RESPONSE");
                requireSubscription(session.rpc("account/read", Map.of("refreshToken", false)));
                JsonNode schema;
                try (var stream = getClass().getResourceAsStream(schemaResource)) {
                    if (stream == null) throw new IOException("Missing schema");
                    schema = mapper.readTree(stream);
                }
                var started = session.rpc("turn/start", Map.of("threadId", threadId,
                        "input", List.of(Map.of("type", "text", "text", input)), "effort", "low",
                        "environments", List.of(), "outputSchema", schema,
                        "sandboxPolicy", Map.of("type", "readOnly", "networkAccess", false)));
                String turnId = started.path("turn").path("id").asText();
                if (turnId.isBlank()) throw new AssistantException("CODEX_INVALID_RESPONSE");
                String answer = null;
                while (true) {
                    JsonNode event = session.next();
                    session.rejectServerRequest(event);
                    JsonNode params = event.path("params");
                    if (!threadId.equals(params.path("threadId").asText())) continue;
                    String method = event.path("method").asText();
                    if (method.equals("item/completed") && turnId.equals(params.path("turnId").asText())) {
                        JsonNode item = params.path("item");
                        String type = item.path("type").asText();
                        if (type.equals("agentMessage")) answer = item.path("text").asText();
                        else if (!Set.of("reasoning", "userMessage", "plan").contains(type))
                            throw new AssistantException("CODEX_FAILED");
                    }
                    if (method.equals("turn/completed") && turnId.equals(params.path("turn").path("id").asText())) {
                        if (!params.path("turn").path("status").asText().equals("completed")) {
                            String errorKind = params.path("turn").path("error").path("codexErrorInfo").asText();
                            System.getLogger(CodexClient.class.getName()).log(System.Logger.Level.WARNING,
                                    "Codex turn failed: {0}", errorKind.matches("[a-zA-Z]{1,40}") ? errorKind : "unknown");
                            if (errorKind.equals("usageLimitExceeded")) throw new AssistantException("CODEX_LIMIT_REACHED");
                            throw new AssistantException("CODEX_FAILED");
                        }
                        if (answer == null || answer.isBlank() || answer.length() > 64000)
                            throw new AssistantException("CODEX_INVALID_RESPONSE");
                        return answer;
                    }
                }
            }
        } catch (IOException e) { throw new AssistantException("CODEX_NOT_AVAILABLE"); }
        finally {
            // Only remove the empty directory created here. Never recursively delete process output.
            if (directory != null) try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            active.release();
        }
    }

    private static void requireSubscription(JsonNode account) {
        String type = account.path("account").path("type").asText();
        if (type.isBlank()) throw new AssistantException("CODEX_LOGIN_REQUIRED");
        if (!type.equals("chatgpt")) throw new AssistantException("CODEX_API_KEY_NOT_ALLOWED");
    }
}
