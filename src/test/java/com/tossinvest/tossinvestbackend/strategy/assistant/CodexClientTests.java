package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodexClientTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void subscriptionProducesStructuredStrategyAndClosesProcess() throws Exception {
        var process = new ScriptedProcess(events("chatgpt", "completed"));
        assertThat(client(process).interpret("사용자 입력")).isEqualTo(StrategyAssistantTests.OUTPUT);
        var requests = process.requests();
        assertThat(requests).contains("account/read", "outputSchema", "readOnly", "\"modelProvider\":\"openai\"");
        assertThat(requests).doesNotContain("account/login/start", "apiKey");
        assertThat(process.closed).isTrue();
    }

    @Test void apiKeyAndMissingLoginNeverStartATurn() throws Exception {
        for (String type : new String[]{"apiKey", "amazonBedrock", ""}) {
            var process = new ScriptedProcess(events(type, "completed"));
            assertThatThrownBy(() -> client(process).interpret("입력")).isInstanceOf(AssistantException.class);
            assertThat(process.requests()).doesNotContain("thread/start", "turn/start");
            assertThat(process.closed).isTrue();
        }
    }

    @Test void failedTurnCannotReturnAnEarlierPartialAnswer() throws Exception {
        var process = new ScriptedProcess(events("chatgpt", "failed"));
        assertThatThrownBy(() -> client(process).interpret("입력")).isInstanceOf(AssistantException.class)
                .extracting(e -> ((AssistantException)e).code()).isEqualTo("CODEX_FAILED");
        assertThat(process.closed).isTrue();
    }

    @Test void malformedOutputAndApprovalRequestsAreRejectedWithoutExposingProviderData() throws Exception {
        for (String input : new String[]{"not json\n", "{\"id\":99,\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"secret\":\"sk-hidden\"}}\n"}) {
            var process = new ScriptedProcess(input);
            assertThatThrownBy(() -> client(process).interpret("입력")).isInstanceOf(AssistantException.class)
                    .hasMessageNotContaining("sk-hidden");
            assertThat(process.closed).isTrue();
        }
    }

    @Test void timeoutKillsProcessAndAllowsAnotherRequest() throws Exception {
        var process = new ScriptedProcess("") {
            final PipedInputStream stalled = new PipedInputStream();
            final PipedOutputStream producer = new PipedOutputStream(stalled);
            @Override public InputStream getInputStream() { return stalled; }
            @Override public void destroy() { super.destroy(); try { producer.close(); } catch (IOException ignored) {} }
        };
        var factory = mock(CodexProcessFactory.class);
        var second = new ScriptedProcess(events("chatgpt", "completed"));
        when(factory.start(any())).thenReturn(process, second);
        var client = new CodexClient(factory, mapper, "gpt-5.6-terra", 1000);
        assertThatThrownBy(() -> client.interpret("입력")).isInstanceOf(AssistantException.class)
                .extracting(e -> ((AssistantException)e).code()).isEqualTo("CODEX_TIMEOUT");
        assertThat(process.closed).isTrue();
        assertThat(client.interpret("입력")).isEqualTo(StrategyAssistantTests.OUTPUT);
    }

    @Test void completionBeforeTurnStartReplyIsNotLost() throws Exception {
        String normal = events("chatgpt", "completed");
        String reply = "{\"id\":6,\"result\":{\"turn\":{\"id\":\"t1\"}}}\n";
        var process = new ScriptedProcess(normal.replace(reply, "") + reply);
        assertThat(client(process).interpret("입력")).isEqualTo(StrategyAssistantTests.OUTPUT);
    }

    @Test void subscriptionLimitDoesNotRetryOrFallBackToApi() throws Exception {
        var process = new ScriptedProcess(events("chatgpt", "failed").replace("\"status\":\"failed\"",
                "\"status\":\"failed\",\"error\":{\"codexErrorInfo\":\"usageLimitExceeded\",\"message\":\"Provider detail\"}"));
        assertThatThrownBy(() -> client(process).interpret("입력")).isInstanceOf(AssistantException.class)
                .extracting(e -> ((AssistantException)e).code()).isEqualTo("CODEX_LIMIT_REACHED");
        assertThat(process.requests()).doesNotContain("apiKey", "account/login", "credits");
    }

    private CodexClient client(Process process) throws IOException {
        var factory = mock(CodexProcessFactory.class);
        when(factory.start(any())).thenReturn(process);
        return new CodexClient(factory, mapper, "gpt-5.6-terra", 1000);
    }

    private String events(String type, String status) throws Exception {
        return "{\"id\":1,\"result\":{}}\n"
                + "{\"id\":2,\"result\":{\"account\":{\"type\":\"" + type + "\"}}}\n"
                + "{\"id\":3,\"result\":{\"config\":{}}}\n"
                + "{\"id\":4,\"result\":{\"thread\":{\"id\":\"th1\"}}}\n"
                + "{\"id\":5,\"result\":{\"account\":{\"type\":\"chatgpt\"}}}\n"
                + "{\"id\":6,\"result\":{\"turn\":{\"id\":\"t1\"}}}\n"
                + "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"th1\",\"turnId\":\"t1\",\"item\":{\"type\":\"agentMessage\",\"text\":"
                + mapper.writeValueAsString(StrategyAssistantTests.OUTPUT) + "}}}\n"
                + "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"th1\",\"turn\":{\"id\":\"t1\",\"status\":\"" + status + "\"}}}\n";
    }

    static class ScriptedProcess extends Process {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;
        ScriptedProcess(String events) { input = new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)); }
        String requests() { return output.toString(StandardCharsets.UTF_8); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { closed = true; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
