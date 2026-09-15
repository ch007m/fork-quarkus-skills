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
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ContentChunk;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.ToolCall;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.UsageUpdate;
import tools.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SmallryeAcpRunner extends AbstractRunner implements AgentRunner {

    private final AcpRegistryManager registryManager = new AcpRegistryManager();
    // TODO: Parameters defined part of Smallrye ACP project and to be aligned with ai.xxx
    private final String ACP_REQUEST_TIMEOUT = "30"; // Timeout Seconds
    private final String ACP_PROMPT_TIMEOUT = "0";

    private boolean streamingText;

    public SmallryeAcpRunner(String aiCmd, String provider, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt, String skillArgs, boolean sanitize) {
        super(aiCmd, provider, model, skillPath, strategy, timeoutSeconds, prompt, skillArgs, sanitize);
    }

    @Override
    protected void addModelArgs(List<String> cmd) {

    }

    @Override
    public UsageStats extractUsage(List<String> sessionFiles) {
        return new UsageStats(0, 0.0, 0, model != null ? model : "unknown");
    }

    @Override
    protected void printEvent(JsonNode event, BufferedWriter prettyWriter) {

    }

    @Override
    public RunOutput run(Path projectDir, Path outputDir, String runName) throws IOException, InterruptedException {
        // TODO: Code to be reviewed and refactored
        // Implement a mechanism to match for ACP the agent to be used
        // using as convention acp/claude or simply claude as the ACP registry can help us to resolve it ...
        InstalledAgent acpAgentMetadata = registryManager.getInstalledAgent(aiCmd.substring(aiCmd.indexOf('/') + 1));
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
                    .withPermissionMode("allow_always");

            try (AcpSyncClient client = clientBuilder.build()) {
                client.workflow()
                        .initialize()
                        .onInitialized(SmallryeAcpRunner::logInitialized)
                        .newSession(projectDir.toString())
                        .onSessionCreated(session -> logSessionCreated(session, projectDir.toString()))
                        .model(model)
                        .skill(skillPath.toString())
                        .prompt(prompt)
                        .runSession();

            } catch (Exception e) {
                exitCode = -1;
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
        if (text.isEmpty()) return;

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
     * Logs agent metadata after a successful ACP initialization handshake:
     * agent name, version, title, protocol version, capabilities, and auth methods.
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
     * Logs session creation details: session ID, working directory,
     * and the active model (if reported in the session config options).
     */
    private static void logSessionCreated(NewSessionResponse session, String cwd) {
        // TODO: To be reviewed
        //logger.debugf("Session created: %s with CWD: %s", session.sessionId(), cwd);
        if (session.configOptions() != null) {
            session.configOptions().stream()
                    .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                    .findFirst()
                    .ifPresent(opt -> System.out.println("Agent model: " + opt.currentValue())); //logger.debugf("Agent model: %s", opt.currentValue()));
        }
    }

    /**
     * Extracts text from a content object. Handles both {@link Map}-based content
     * (with a {@code "text"} key) and plain objects by calling {@code toString()}.
     */
    private static String extractText(Object content) {
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : content.toString();
        }
        return content != null ? content.toString() : "";
    }
}
