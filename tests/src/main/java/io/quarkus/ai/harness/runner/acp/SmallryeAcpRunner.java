package io.quarkus.ai.harness.runner.acp;

import io.quarkus.ai.harness.runner.AbstractRunner;
import io.quarkus.ai.harness.runner.AgentRunner;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSessionResult;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;
import io.smallrye.agentclientprotocol.sdk.registry.model.InstalledAgent;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ContentChunk;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
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
    private String sessionId;
    private InstalledAgent acpAgentMetadata;

    private record ModelOption(String value, String name) {
    }

    private final List<ModelOption> sessionModelOptions = new ArrayList<>();

    public SmallryeAcpRunner(String aiAgent, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt, String skillArgs) {
        super(aiAgent, model, skillPath, strategy, timeoutSeconds, prompt, skillArgs);
    }

    @Override
    public RunOutput run(Path projectDir, Path outputDir, String runName) throws IOException, InterruptedException {
        acpAgentMetadata = registryManager.getInstalledAgent(aiAgent);
        if (acpAgentMetadata == null) {
            throw new IllegalStateException(
                    String.format("ACP agent '%s' not found under ~/.acp/agents. Please install it first.", aiAgent));
        }

        Files.createDirectories(outputDir);
        Path jsonLogFile = outputDir.resolve(runName + ".json");
        Path prettyFile = outputDir.resolve(runName + ".pretty.md");

        Instant start = Instant.now();
        int exitCode = 0;
        streamingText = false;

        System.out.println("─".repeat(60));

        try (BufferedWriter logWriter = Files.newBufferedWriter(jsonLogFile);
                BufferedWriter prettyWriter = Files.newBufferedWriter(prettyFile)) {

            try (AcpSyncClient client = buildAcpClient(logWriter, prettyWriter, null)) {
                // 1. Initialize
                InitializeResponse initResponse = client.initialize();
                AcpUtil.logInitialized(initResponse);

                // 2. Create session
                var sessionResponse = client.newSession(new NewSessionRequest(projectDir.toString(), List.of()));
                sessionId = sessionResponse.sessionId();
                AcpUtil.logSessionCreated(sessionResponse, projectDir.toString());

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
                        System.out.printf("  Warning: agent %s does not support to execute: session/set_config_option, skipping model override\n", aiAgent);
                    }
                }

                // 5. Send prompt with skill
                String effectivePrompt = prompt.isEmpty() ? generateMigrationPrompt() : prompt;
                if (skillPath != null) {
                    effectivePrompt += "\n\nPlease read the skill: " + skillPath + " and follow its instructions.";
                }
                client.prompt(new PromptRequest(List.of(new TextContent(effectivePrompt)), sessionId));

                // 6. Close session
                if (sessionId != null) {
                    try {
                        client.closeSession(new CloseSessionRequest(sessionId));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to close session " + sessionId + ": " + e.getMessage());
                    }
                }

            } catch (Exception e) {
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
    public ReviewOutput review(Path projectDir, Path outputDir, String runName, Path skillPath,
            Map<String, Boolean> checkResults) throws IOException, InterruptedException {

        if (sessionId == null || sessionId.isBlank()) {
            return new ReviewOutput("No session available for review.",
                    new UsageStats(0, 0, 0, model != null ? model : "unknown"));
        }

        Files.createDirectories(outputDir);
        Path jsonLogFile = outputDir.resolve(runName + ".review.json");
        Path reviewFile = outputDir.resolve(runName + ".review.md");

        Instant start = Instant.now();
        var reviewText = new StringBuilder();
        streamingText = false;

        System.out.println("  ── Skill Review (ACP session/load) ───────────────────────────────");
        System.out.println("  Resuming session: " + sessionId);

        try (BufferedWriter logWriter = Files.newBufferedWriter(jsonLogFile);
                BufferedWriter prettyWriter = Files.newBufferedWriter(reviewFile)) {

            try (AcpSyncClient client = buildAcpClient(logWriter, prettyWriter, reviewText)) {

                AcpSessionResult result = client.workflow()
                        .withWorkspace(projectDir.toString())
                        .resumeSession(sessionId)
                        .onSessionLoaded(loaded -> System.out.println("  Session loaded successfully"))
                        .model(resolveAcpModel())
                        .prompt(reviewPrompt())
                        //.prompt(reviewPromptWithChecks(checkResults))
                        .run();

                if (result.isResumedSession()) {
                    System.out.println("  Session resumed successfully");
                }

            } catch (Exception e) {
                e.printStackTrace();
                return new ReviewOutput("Review failed: " + e.getMessage(),
                        new UsageStats(0, 0, 0, model != null ? model : "unknown"));
            }

            endTextStream(prettyWriter);
        }

        String review = reviewText.toString().trim();
        System.out.println();
        System.out.println("  SKILL Review saved:  " + reviewFile);
        System.out.println("  Duration: " + Duration.between(start, Instant.now()).toSeconds() + "s");
        System.out.println("  ─────────────────────────────────────────────────────────");

        return new ReviewOutput(review, new UsageStats(0, 0, 0, model != null ? model : "unknown"));
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

    private AcpSyncClient buildAcpClient(BufferedWriter logWriter, BufferedWriter prettyWriter,
            StringBuilder textCapture) {

        var paramBuilder = AgentParameters.builder(acpAgentMetadata.cmd());
        if (!acpAgentMetadata.args().isEmpty()) {
            for (String a : acpAgentMetadata.args()) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) {
                    paramBuilder.arg(trimmed);
                }
            }
        }

        var transport = new StdioAcpClientTransport(paramBuilder.build());
        var requestTimeout = Duration.ofSeconds(Long.parseLong(ACP_REQUEST_TIMEOUT));
        var promptTimeout = Duration.ofSeconds(Long.parseLong(ACP_PROMPT_TIMEOUT));

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

        return AcpClient.sync(transport)
                .withRequestTimeout(requestTimeout)
                .withPromptRequestTimeout(promptTimeout)
                .withNotifications(n -> n
                        .onAgentMessage(chunk -> {
                            if (textCapture != null) {
                                String text = AcpUtil.extractText(chunk.content());
                                if (!text.isEmpty()) {
                                    textCapture.append(text);
                                }
                            }
                            handleAgentMessageChunk(chunk, prettyWriter);
                        })
                        .onToolCall(tc -> handleToolCall(tc, prettyWriter))
                        .onUsage(usage -> handleUsage(usage, prettyWriter))
                )
                .withPermissionMode(PERMISSION_ALLOW_ALWAYS)
                .build();
    }

    private void handleAgentMessageChunk(ContentChunk chunk, BufferedWriter prettyWriter) {
        String text = AcpUtil.extractText(chunk.content());
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

    private String reviewPromptWithChecks(Map<String, Boolean> checkResults) {
        var checkSummary = new StringBuilder();
        checkResults.forEach((check, passed) ->
                checkSummary.append("  ").append(passed ? "✅" : "❌").append(" ").append(check).append("\n"));

        return """
                        You just completed a migration of a Spring Boot project to Quarkus. \
                        Review the migration session above and evaluate how the skill instructions performed.

                        Check results:
                        %s
                        Based on this migration run, write a brief review covering:

                        1. **What went well** — which parts of the skill worked smoothly
                        2. **What went wrong** — any errors, retries, or failed checks and why
                        3. **Skill improvement suggestions** — concrete changes to the SKILL.md that would \
                           help future migrations (missing instructions, wrong mappings, unclear steps, etc.)
                        4. **Rating** — rate the skill 1-5 for this migration (5 = perfect, no issues)

                        Be specific and actionable. Reference actual files and errors from the migration. \
                        Read the current skill file at %s to see what instructions were given.

                        Write your review as markdown.""".formatted(checkSummary.toString(),
                skillPath.resolve("SKILL.md"));
    }

    private String reviewPrompt() {

        return """
                        You just completed a migration of a Spring Boot project to Quarkus. \
                        Review the migration session above and evaluate how the skill instructions performed.

                        Based on the migration results, write a brief review covering:

                        1. **What went well** — which parts of the skill worked smoothly
                        2. **What went wrong** — any errors, retries, or failed checks and why
                        3. **Skill improvement suggestions** — concrete changes to the SKILL.md that would \
                           help future migrations (missing instructions, wrong mappings, unclear steps, etc.)
                        4. **Rating** — rate the skill 1-5 for this migration (5 = perfect, no issues)

                        Be specific and actionable. Reference actual files and errors from the migration. \
                        Read the current skill file at %s to see what instructions were given.

                        Write your review as markdown.""".formatted(skillPath.resolve("SKILL.md"));
    }

    private String resolveAcpModel() {
        if (model == null || model.isBlank())
            return "";

        if (sessionModelOptions.isEmpty())
            return "";

        for (ModelOption opt : sessionModelOptions) {
            if (opt.value().equalsIgnoreCase(model))
                return opt.value();
        }

        String modelLower = model.toLowerCase();
        List<ModelOption> candidates = sessionModelOptions.stream()
                .filter(opt -> opt.value().toLowerCase().contains(modelLower)
                        || opt.name().toLowerCase().contains(modelLower))
                .toList();

        if (candidates.size() == 1)
            return candidates.get(0).value();

        if (candidates.size() > 1)
            return candidates.get(0).value();

        throw new IllegalStateException(String.format(
                "Model '%s' is not supported by the acp agent '%s'. Run: \"acp model list -a %s\" to see available models.",
                model, aiAgent, aiAgent));
    }
}
