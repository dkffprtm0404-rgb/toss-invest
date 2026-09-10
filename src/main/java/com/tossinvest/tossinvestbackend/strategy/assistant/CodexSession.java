package com.tossinvest.tossinvestbackend.strategy.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;

/** One bounded stdio RPC session. The caller owns its process and always closes it. */
final class CodexSession implements AutoCloseable {
    private final Process process;
    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(256);
    private final long deadline;
    private final Thread reader;
    private volatile boolean ended;
    private int sequence;
    private final Deque<JsonNode> notifications = new ArrayDeque<>();

    CodexSession(Process process, ObjectMapper mapper, long timeoutMillis) {
        this.process = process;
        this.mapper = mapper;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        reader = new Thread(this::read, "codex-strategy-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        try (Reader stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int character;
            long total = 0;
            while ((character = stream.read()) != -1) {
                if (++total > 8_000_000 || line.length() > 1_000_000) break;
                if (character == '\n') {
                    if (!line.isEmpty()) lines.put(line.toString());
                    line.setLength(0);
                } else line.append((char) character);
            }
        } catch (IOException ignored) {
            // The HTTP boundary returns only a sanitized code.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally { ended = true; }
    }

    JsonNode rpc(String method, Object params) {
        int id = ++sequence;
        send(Map.of("id", id, "method", method, "params", params));
        while (true) {
            JsonNode event = readEvent();
            rejectServerRequest(event);
            if (event.path("id").asInt(-1) != id) {
                if (notifications.size() >= 256) throw new AssistantException("CODEX_INVALID_RESPONSE");
                notifications.addLast(event);
                continue;
            }
            if (event.has("error")) throw new AssistantException("CODEX_FAILED");
            if (!event.has("result")) throw new AssistantException("CODEX_INVALID_RESPONSE");
            return event.get("result");
        }
    }

    void notify(String method) { send(Map.of("method", method, "params", Map.of())); }

    private void send(Object message) {
        try { writer.write(mapper.writeValueAsString(message)); writer.newLine(); writer.flush(); }
        catch (IOException e) { throw new AssistantException("CODEX_FAILED"); }
    }

    JsonNode next() {
        return notifications.isEmpty() ? readEvent() : notifications.removeFirst();
    }

    private JsonNode readEvent() {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssistantException("CODEX_TIMEOUT");
            try {
                String line = lines.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (line != null) return mapper.readTree(line);
                if (ended) throw new AssistantException("CODEX_FAILED");
            } catch (IOException e) { throw new AssistantException("CODEX_INVALID_RESPONSE"); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssistantException("CODEX_FAILED");
            }
        }
    }

    void rejectServerRequest(JsonNode event) {
        // This integration never approves tools, login switches, purchases, or permission escalation.
        if (event.has("id") && event.has("method")) throw new AssistantException("CODEX_FAILED");
        String method = event.path("method").asText();
        if (method.equals("account/updated") && !event.path("params").path("authMode").asText().equals("chatgpt"))
            throw new AssistantException("CODEX_API_KEY_NOT_ALLOWED");
    }

    @Override public void close() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        reader.interrupt();
    }
}
