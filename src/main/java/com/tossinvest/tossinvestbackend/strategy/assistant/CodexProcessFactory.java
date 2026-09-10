package com.tossinvest.tossinvestbackend.strategy.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class CodexProcessFactory {
    private final String executable;
    public CodexProcessFactory(@Value("${strategy.codex.executable:codex}") String executable) {
        this.executable = executable;
    }

    public Process start(Path directory) throws IOException {
        // No shell: user text never becomes a command-line argument or executable path.
        List<String> command = new ArrayList<>(List.of(executable, "app-server", "--stdio",
                "-c", "model_provider=\"openai\"", "-c", "forced_login_method=\"chatgpt\"",
                "-c", "web_search=\"disabled\""));
        for (String feature : List.of("shell_tool", "unified_exec", "apps", "plugins", "remote_plugin",
                "hooks", "memories", "multi_agent", "browser_use", "computer_use", "image_generation",
                "in_app_browser", "in_app_local_automation", "workspace_dependencies", "skill_search",
                "skill_mcp_dependency_install", "view_image", "goals", "code_mode", "code_mode_host")) {
            command.add("-c");
            command.add("features." + feature + "=false");
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(k -> k.equalsIgnoreCase("OPENAI_API_KEY")
                || k.equalsIgnoreCase("CODEX_API_KEY") || k.equalsIgnoreCase("OPENAI_BASE_URL")
                || k.equalsIgnoreCase("CODEX_THREAD_ID"));
        return builder.start();
    }
}
