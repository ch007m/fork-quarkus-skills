package io.quarkus.ai.harness.runner.acp;

import io.quarkus.ai.harness.runner.AbstractRunner;
import io.quarkus.ai.harness.runner.AgentRunner;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;
import io.smallrye.agentclientprotocol.sdk.registry.model.InstalledAgent;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ContentChunk;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.PromptRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.SessionConfigOption;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.SetSessionConfigOptionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.TextContent;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ToolCall;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.UsageUpdate;
import tools.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SmallryeAcpRunner extends AbstractRunner implements AgentRunner {

    private final AcpRegistryManager registryManager = new AcpRegistryManager();
    // TODO: Parameters defined part of Smallrye ACP project and to be aligned with ai.xxx
    private final String ACP_REQUEST_TIMEOUT = "30"; // Timeout Seconds
    private final String ACP_PROMPT_TIMEOUT = "0";
    private final String PERMISSION_ALLOW_ALWAYS = "allow_always";

    private boolean streamingText;

    private record ModelOption(String value, String name) {
    }

    private final List<ModelOption> sessionModelOptions = new ArrayList<>();

    public SmallryeAcpRunner(String aiAgent, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt, String skillArgs) {
        super(aiAgent, model, skillPath, strategy, timeoutSeconds, prompt, skillArgs);
    }

    @Override
    protected void addModelArgs(List<String> cmd) {

    }

    @Override
    public UsageStats extractUsage(List<String> sessionFiles) {
        if (sessionFiles == null || sessionFiles.isEmpty()) {
            return new UsageStats(0, 0.0, 0, model != null ? model : "unknown");
        }

        long inputTokens = 0;
        long outputTokens = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        long totalTokens = 0;
        double totalCost = 0.0;
        int toolCalls = 0;

        for (String sessionFile : sessionFiles) {
            if (sessionFile == null) continue;
            try {
                List<String> lines = Files.readAllLines(Path.of(sessionFile));
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = JSON.readTree(line);

                        JsonNode result = node.path("result");
                        if (result.has("usage")) {
                            JsonNode usage = result.path("usage");
                            inputTokens += usage.path("inputTokens").asLong(0);
                            outputTokens += usage.path("outputTokens").asLong(0);
                            cacheRead += usage.path("cachedReadTokens").asLong(0);
                            cacheWrite += usage.path("cachedWriteTokens").asLong(0);
                            totalTokens += usage.path("totalTokens").asLong(0);
                        }

                        JsonNode update = node.path("params").path("update");
                        String sessionUpdate = update.path("sessionUpdate").asText("");

                        if ("usage_update".equals(sessionUpdate)) {
                            JsonNode cost = update.path("cost");
                            if (cost.has("amount")) {
                                totalCost = cost.path("amount").asDouble(0.0);
                            }
                        }

                        if ("tool_call".equals(sessionUpdate)
                                && "pending".equals(update.path("status").asText(""))) {
                            toolCalls++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException e) {
                System.err.println("Warning: failed to parse ACP session file: " + sessionFile);
            }
        }

        if (totalTokens == 0 && (inputTokens > 0 || outputTokens > 0)) {
            totalTokens = inputTokens + outputTokens + cacheRead + cacheWrite;
        }

        return new UsageStats(totalTokens, totalCost, 0, toolCalls,
                model != null ? model : "unknown",
                inputTokens, outputTokens, 0,
                cacheRead, cacheWrite, List.of());
    }

    @Override
    protected void printEvent(JsonNode event, BufferedWriter prettyWriter) {

    }

    @Override
    public RunOutput run(Path projectDir, Path outputDir, String runName) throws IOException, InterruptedException {
        // Check if the ACP agent id/name matches an installed agent under: ~/.acp/agents
        InstalledAgent acpAgentMetadata = registryManager.getInstalledAgent(aiAgent);
        if (acpAgentMetadata == null) {
            throw new IllegalStateException(
                    String.format("ACP agent '%s' not found under ~/.acp/agents. Please install it first.", aiAgent));
        }

        // Define the command to be executed: binary path + args
        var paramBuilder = AgentParameters.builder(acpAgentMetadata.cmd());
        if (!acpAgentMetadata.args().isEmpty()) {
            for (String a : acpAgentMetadata.args()) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) {
                    paramBuilder.arg(trimmed);
                }
            }
        }
        var params = paramBuilder.build();

        // 2. Create the stdio transport
        var requestTimeout = Duration.ofSeconds(Long.parseLong(ACP_REQUEST_TIMEOUT));
        var promptTimeout = Duration.ofSeconds(Long.parseLong(ACP_PROMPT_TIMEOUT));
        var transport = new StdioAcpClientTransport(params);

        Files.createDirectories(outputDir);
        Path jsonLogFile = outputDir.resolve(runName + ".json");
        Path prettyFile = outputDir.resolve(runName + ".pretty.md");

        Instant start = Instant.now();
        int exitCode = 0;
        streamingText = false;

        System.out.println("─".repeat(60));

        try (BufferedWriter logWriter = Files.newBufferedWriter(jsonLogFile);
                BufferedWriter prettyWriter = Files.newBufferedWriter(prettyFile)) {

            transport.setRawInboundListener(msg -> {
                try {
                    logWriter.write(msg);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            transport.setRawOutboundListener(msg -> {
                try {
                    logWriter.write(msg);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            AcpClient.SyncBuilder clientBuilder = AcpClient.sync(transport)
                    .withRequestTimeout(requestTimeout)
                    .withPromptRequestTimeout(promptTimeout)
                    .withNotifications(n -> n
                            .onAgentMessage(chunk -> handleAgentMessageChunk(chunk, prettyWriter))
                            .onToolCall(tc -> handleToolCall(tc, prettyWriter))
                            .onUsage(usage -> handleUsage(usage, prettyWriter))
                    )
                    .withPermissionMode(PERMISSION_ALLOW_ALWAYS);

            try (AcpSyncClient client = clientBuilder.build()) {
                // 1. Initialize
                InitializeResponse initResponse = client.initialize();
                logInitialized(initResponse);

                // 2. Create session
                var sessionResponse = client.newSession(new NewSessionRequest(projectDir.toString(), List.of()));
                String sessionId = sessionResponse.sessionId();
                logSessionCreated(sessionResponse, projectDir.toString());

                // 3. Extract model options from session config
                if (sessionResponse.configOptions() != null) {
                    for (SessionConfigOption cfg : sessionResponse.configOptions()) {
                        if (("model".equalsIgnoreCase(String.valueOf(cfg.category()))
                                || "model".equalsIgnoreCase(cfg.id())) && cfg.options() != null) {
                            for (var opt : cfg.options()) {
                                sessionModelOptions.add(new ModelOption(opt.value(), opt.name()));
                            }
                        }
                    }
                }

                // 4. Resolve model AFTER session response (options are now captured)
                String resolvedModel = resolveAcpModel();
                if (!resolvedModel.isEmpty()) {
                    System.out.println("  Model resolved: " + resolvedModel);
                    try {
                        client.setConfigOption(new SetSessionConfigOptionRequest("model", sessionId, resolvedModel));
                    } catch (RuntimeException e) {
                        System.out.printf("  Warning: agent %s does not support to execute: session/set_config_option, skipping model override\n",aiAgent);
                    }
                }

                // 4. Send prompt with skill
                String effectivePrompt = prompt.isEmpty() ? generateMigrationPrompt() : prompt;
                if (skillPath != null) {
                    effectivePrompt += "\n\nPlease read the skill: " + skillPath + " and follow its instructions.";
                }
                client.prompt(new PromptRequest(List.of(new TextContent(effectivePrompt)), sessionId));

                // 5. Close session
                if (sessionId != null) {
                    try {
                        client.closeSession(new CloseSessionRequest(sessionId));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to close session " + sessionId + ": " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                exitCode = -1;
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            endTextStream(prettyWriter);

            String summary = "\n" + "─".repeat(60) + "\n" +
                    "  acp exit: " + exitCode + "  duration: " + Duration.between(start, Instant.now()).toSeconds() + "s";
            printBoth(summary, prettyWriter);
        }

        Duration duration = Duration.between(start, Instant.now());
        return new RunOutput(exitCode, duration, Collections.singletonList(jsonLogFile.toString()), jsonLogFile.toString());
    }

    @Override
    public ReviewOutput review(String sessionFile, Path projectDir, Path outputDir, String runName, Path skillPath,
            Map<String, Boolean> checkResults) throws IOException, InterruptedException {
        return null;
    }

    private void handleAgentMessageChunk(ContentChunk chunk, BufferedWriter prettyWriter) {
        String text = extractText(chunk.content());
        if (text.isEmpty())
            return;

        if (!streamingText) {
            System.out.print("  │ ");
            try {
                prettyWriter.write("  │ ");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            streamingText = true;
        }
        System.out.print(text);
        System.out.flush();
        try {
            prettyWriter.write(text);
            prettyWriter.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void handleToolCall(ToolCall tc, BufferedWriter prettyWriter) {
        endTextStream(prettyWriter);
        String title = tc.title() != null ? tc.title() : "unknown";
        printBoth("  │ 🔧 " + title, prettyWriter);
    }

    private void handleUsage(UsageUpdate usage, BufferedWriter prettyWriter) {
        endTextStream(prettyWriter);
        String info = String.format("  └── usage  [tokens: %s/%s]",
                usage.used() != null ? usage.used() : "?",
                usage.size() != null ? usage.size() : "?");
        printBoth(info, prettyWriter);
    }

    private void endTextStream(BufferedWriter prettyWriter) {
        if (streamingText) {
            System.out.println();
            try {
                prettyWriter.newLine();
                prettyWriter.flush();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            streamingText = false;
        }
    }

    /**
     * Resolves the model value to send via ACP {@code config/set} by matching the user's {@code -Dai.model}
     * against the session's actual model options. The model value may include a provider prefix
     * (e.g. {@code anthropic/claude-opus-4-6}) or be a short alias (e.g. {@code opus}).
     *
     * <p>If the agent exposes no model options (e.g. IBM Bob), returns empty — the agent uses its own default.
     * If the agent exposes options but none match, throws with a hint to run {@code acp model list}.
     */
    private String resolveAcpModel() {
        if (model == null || model.isBlank())
            return "";

        if (sessionModelOptions.isEmpty())
            return "";

        // 1. Exact match on option value
        for (ModelOption opt : sessionModelOptions) {
            if (opt.value().equalsIgnoreCase(model))
                return opt.value();
        }

        // 2. Fuzzy: find options whose value or name contains the model string
        String modelLower = model.toLowerCase();
        List<ModelOption> candidates = sessionModelOptions.stream()
                .filter(opt -> opt.value().toLowerCase().contains(modelLower)
                        || opt.name().toLowerCase().contains(modelLower))
                .toList();

        if (candidates.size() == 1)
            return candidates.get(0).value();

        if (candidates.size() > 1)
            return candidates.get(0).value();

        // No match found
        throw new IllegalStateException(String.format(
                "Model '%s' is not supported by the acp agent '%s'. Run: \"acp model list -a %s\" to see available models.",
                model, aiAgent, aiAgent));
    }

    /**
     * Logs agent metadata after a successful ACP initialization handshake: agent name, version, title, protocol version,
     * capabilities, and auth methods.
     */
    private static void logInitialized(InitializeResponse init) {
        var agentInfo = init.agentInfo();
        String title = agentInfo.title();
        String connectedMsg = (title != null && !title.isEmpty())
                ? String.format("Connected to the ACP agent: %s - v%s - %s",
                agentInfo.name(), agentInfo.version(), title)
                : String.format("Connected to the ACP agent: %s - v%s",
                        agentInfo.name(), agentInfo.version());
        // TODO: To be reviewed
        //logger.debugf(connectedMsg);
        //logger.debugf("Protocol version: %s", init.protocolVersion());
        //logger.debugf("Capabilities: %s", init.agentCapabilities());
        //logger.debugf("Auth methods: %s", init.authMethods());
    }

    /**
     * Logs session creation details: session ID, working directory, and the active model (if reported in the session config
     * options).
     */
    private static void logSessionCreated(NewSessionResponse session, String cwd) {
        // TODO: To be reviewed
        //logger.debugf("Session created: %s with CWD: %s", session.sessionId(), cwd);
        if (session.configOptions() != null) {
            session.configOptions().stream()
                    .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                    .findFirst()
                    .ifPresent(opt -> System.out.println(
                            "Agent model: " + opt.currentValue())); //logger.debugf("Agent model: %s", opt.currentValue()));
        }
    }

    /**
     * Extracts text from a content object. Handles both {@link Map}-based content (with a {@code "text"} key) and plain objects
     * by calling {@code toString()}.
     */
    private static String extractText(Object content) {
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : content.toString();
        }
        return content != null ? content.toString() : "";
    }
}
