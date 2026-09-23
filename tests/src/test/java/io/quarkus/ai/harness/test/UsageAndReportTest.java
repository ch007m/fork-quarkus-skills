package io.quarkus.ai.harness.test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.quarkus.ai.harness.result.MigrationResult;
import io.quarkus.ai.harness.result.ResultsTracker;
import io.quarkus.ai.harness.skill.SkillReference;
import io.quarkus.ai.harness.runner.AgentRunner;
import io.quarkus.ai.harness.runner.acp.SmallryeAcpRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates token extraction from ACP JSON-RPC session files and
 * individual run report (.report.md) generation.
 *
 * <p>Uses a session fixture in ACP JSON-RPC format.
 */
class UsageAndReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SESSION_FIXTURE = "/sessions/dummy_acp_session.jsonl";

    // Expected values from the ACP fixture's result.usage block
    private static final long EXPECTED_INPUT_TOKENS = 819;
    private static final long EXPECTED_OUTPUT_TOKENS = 515;
    private static final long EXPECTED_CACHE_READ = 48_661;
    private static final long EXPECTED_CACHE_WRITE = 25_246;
    private static final long EXPECTED_TOTAL_TOKENS = EXPECTED_INPUT_TOKENS + EXPECTED_OUTPUT_TOKENS
            + EXPECTED_CACHE_READ + EXPECTED_CACHE_WRITE;
    private static final double EXPECTED_COST = 0.195412;    // from usage_update cost.amount
    private static final int EXPECTED_TOOL_CALLS = 2;

    @TempDir
    Path tempDir;

    private Path fixtureFile;

    @BeforeEach
    void setUp() throws IOException {
        try (var in = getClass().getResourceAsStream(SESSION_FIXTURE)) {
            assertNotNull(in, "Session fixture not found on classpath: " + SESSION_FIXTURE);
            fixtureFile = tempDir.resolve("session.jsonl");
            Files.copy(in, fixtureFile);
        }
    }

    // ─── Token extraction from JSONL ──────────────────────────────────

    @Test
    @DisplayName("extractUsage sums usage from ACP result event")
    void extractUsageSumsUsage() {
        SmallryeAcpRunner runner = new SmallryeAcpRunner(
                "claude-acp", "claude-opus-4-6", Path.of("/tmp/skill"),
                "full", 300, "");

        AgentRunner.UsageStats stats = runner.extractUsage(
                Collections.singletonList(fixtureFile.toString()));

        assertEquals(EXPECTED_INPUT_TOKENS, stats.inputTokens(),
                "input tokens from result.usage");
        assertEquals(EXPECTED_OUTPUT_TOKENS, stats.outputTokens(),
                "output tokens from result.usage");
        assertEquals(EXPECTED_CACHE_READ, stats.cacheRead(),
                "cache read tokens from result.usage");
        assertEquals(EXPECTED_CACHE_WRITE, stats.cacheWrite(),
                "cache write tokens from result.usage");
        assertEquals(EXPECTED_TOTAL_TOKENS, stats.totalTokens(),
                "total = input + output + cacheRead + cacheWrite");
        assertEquals(EXPECTED_COST, stats.totalCost(), 0.0001,
                "cost from usage_update cost.amount");
        assertEquals(EXPECTED_TOOL_CALLS, stats.toolCalls(),
                "tool calls = number of tool_call entries with status=pending");
    }

    @Test
    @DisplayName("totalTokens formula: input + output + cacheRead + cacheWrite")
    void totalTokensFormula() {
        assertEquals(75_241, EXPECTED_TOTAL_TOKENS,
                "819 + 515 + 48661 + 25246 = 75241");
    }

    @Test
    @DisplayName("extractUsage handles null session files gracefully")
    void extractUsageNullFiles() {
        SmallryeAcpRunner runner = new SmallryeAcpRunner(
                "claude-acp", null, Path.of("/tmp/skill"),
                "full", 300, "");

        AgentRunner.UsageStats stats = runner.extractUsage(null);
        assertEquals(0, stats.totalTokens());
        assertEquals(0.0, stats.totalCost());
    }

    @Test
    @DisplayName("extractUsage with empty file list returns zero stats")
    void extractUsageEmptyFiles() {
        SmallryeAcpRunner runner = new SmallryeAcpRunner(
                "claude-acp", null, Path.of("/tmp/skill"),
                "full", 300, "");

        AgentRunner.UsageStats stats = runner.extractUsage(Collections.emptyList());
        assertEquals(0, stats.totalTokens());
        assertEquals(0.0, stats.totalCost());
    }

    // ─── Result event parsing ─────────────────────────────────────────

    @Test
    @DisplayName("ACP result event contains usage with token counts")
    void resultEventStructure() throws IOException {
        List<String> lines = Files.readAllLines(fixtureFile);
        String lastLine = lines.getLast().trim();
        if (lastLine.isEmpty()) {
            lastLine = lines.get(lines.size() - 2).trim();
        }

        JsonNode entry = JSON.readTree(lastLine);
        JsonNode result = entry.path("result");
        assertTrue(result.has("usage"), "result should contain usage block");

        JsonNode usage = result.path("usage");
        assertTrue(usage.has("inputTokens"));
        assertTrue(usage.has("outputTokens"));
        assertTrue(usage.has("cachedReadTokens"));
        assertTrue(usage.has("cachedWriteTokens"));
        assertTrue(usage.has("totalTokens"));

        assertEquals(819, usage.path("inputTokens").asLong());
        assertEquals(515, usage.path("outputTokens").asLong());
        assertEquals(48_661, usage.path("cachedReadTokens").asLong());
        assertEquals(25_246, usage.path("cachedWriteTokens").asLong());
        assertEquals(75_241, usage.path("totalTokens").asLong());
    }

    // ─── Report generation (writeMarkdownReport) ──────────────────────

    @Test
    @DisplayName("writeMarkdownReport without modelUsages uses single-model fallback table")
    void writeMarkdownReportFallback() throws IOException {
        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);

        MigrationResult result = buildResult("dummy", "claude-opus-4-6", "test-run");
        tracker.record(result);

        String report = Files.readString(tempDir.resolve("test-run.report.md"));

        assertContains(report, "# Migration Run Report");
        assertContains(report, "| Tool calls | 2 |");
        assertContains(report, "| Input tokens | 819 |");
        assertContains(report, "| Output tokens | 515 |");
        assertContains(report, "| Cache read | 48,661 |");
        assertContains(report, "| Cache write | 25,246 |");
        assertContains(report, "| Total tokens | 75,241 |");
        assertContains(report, "| Cost | $0.20 |");
        assertContains(report, "| Model | `claude-opus-4-6` |");
    }

    @Test
    @DisplayName("writeMarkdownReport with modelUsages shows per-model breakdown table")
    void writeMarkdownReportPerModel() throws IOException {
        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);

        MigrationResult result = buildResult("dummy", "claude-opus-4-6", "multi-model");
        result.setModelUsages(List.of(
                new AgentRunner.ModelUsage("claude-haiku-4-5@20251001", 814, 21, 0, 0, 0.000919),
                new AgentRunner.ModelUsage("claude-opus-4-6", 5, 494, 48_661, 25_246, 0.194493)));
        tracker.record(result);

        String report = Files.readString(tempDir.resolve("multi-model.report.md"));

        // Per-model rows
        assertContains(report, "| Model | Input | Output | Cache Read | Cache Write | Cost |");
        assertContains(report, "| claude-haiku-4-5@20251001 | 814 | 21 | 0 | 0 | $0.0009 |");
        assertContains(report, "| claude-opus-4-6 | 5 | 494 | 48,661 | 25,246 | $0.1945 |");

        // Total row (from modelUsage sums) — no $/MTokens on total
        assertContains(report, "| **Total** | **819** | **515** | **48,661** | **25,246** | **$0.20** | |");
        assertContains(report, "**Grand total: 819 + 515 + 48,661 + 25,246 = 75,241 tokens**");

        // Cost formula
        assertContains(report, "**Cost formula:**");

        // Token Usage section should NOT have old key-value rows
        String tokenSection = report.substring(report.indexOf("## Token Usage"));
        assertFalse(tokenSection.contains("| Metric | Value |"),
                "per-model report should not use the old key-value format in Token Usage");
    }

    @Test
    @DisplayName("report cost is rounded to 2 decimal places")
    void reportCostRounding() throws IOException {
        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);

        MigrationResult result = buildResult("dummy", "claude-opus-4-6", "cost-test");
        result.setTotalCost(0.195412);
        tracker.record(result);

        String report = Files.readString(tempDir.resolve("cost-test.report.md"));
        assertContains(report, "| Cost | $0.20 |");
    }

    // ─── Token formatting ─────────────────────────────────────────────

    @Test
    @DisplayName("formatTokens: values >= 1M use M suffix, >= 1000 use comma separator, rest plain")
    void tokenFormatting() throws IOException {
        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);

        MigrationResult rLarge = buildResult("project", "model", "fmt-large");
        rLarge.setTotalTokens(1_500_000);
        rLarge.setInputTokens(1_200_000);
        rLarge.setOutputTokens(300_000);
        rLarge.setCacheRead(0);
        rLarge.setCacheWrite(0);
        tracker.record(rLarge);

        String report = Files.readString(tempDir.resolve("fmt-large.report.md"));
        assertContains(report, "| Input tokens | 1.2M |");
        assertContains(report, "| Total tokens | 1.5M |");

        MigrationResult rSmall = buildResult("project", "model", "fmt-small");
        rSmall.setTotalTokens(500);
        rSmall.setInputTokens(200);
        rSmall.setOutputTokens(300);
        rSmall.setCacheRead(0);
        rSmall.setCacheWrite(0);
        tracker.record(rSmall);

        String report2 = Files.readString(tempDir.resolve("fmt-small.report.md"));
        assertContains(report2, "| Input tokens | 200 |");
        assertContains(report2, "| Total tokens | 500 |");
    }

    // ─── Integration: full pipeline from JSONL to report ──────────────

    @Test
    @DisplayName("end-to-end: extract usage from ACP fixture JSONL and generate report")
    void endToEndFixtureToReport() throws IOException {
        SmallryeAcpRunner runner = new SmallryeAcpRunner(
                "claude-acp", "claude-opus-4-6", Path.of("/tmp/skill"),
                "full", 300, "Say Hello.");

        AgentRunner.UsageStats stats = runner.extractUsage(
                Collections.singletonList(fixtureFile.toString()));

        SkillReference skillRef = new SkillReference("dummy", null, "/tmp/skill");
        MigrationResult result = new MigrationResult("claude-acp", "dummy",
                "claude-opus-4-6", "full", skillRef);
        result.setRunName("e2e-test");
        result.setDuration(Duration.ofSeconds(28));
        result.setPrompt("Say Hello.");
        result.setTotalTokens(stats.totalTokens());
        result.setTotalCost(stats.totalCost());
        result.setApiCalls(stats.apiCalls());
        result.setToolCalls(stats.toolCalls());
        result.setInputTokens(stats.inputTokens());
        result.setOutputTokens(stats.outputTokens());
        result.setThinkingTokens(stats.thinkingTokens());
        result.setCacheRead(stats.cacheRead());
        result.setCacheWrite(stats.cacheWrite());
        result.setModelUsages(stats.modelUsages());

        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);
        tracker.record(result);

        String report = Files.readString(tempDir.resolve("e2e-test.report.md"));

        // Single-model fallback table (ACP doesn't provide per-model breakdown)
        assertContains(report, "| Input tokens | 819 |");
        assertContains(report, "| Output tokens | 515 |");
        assertContains(report, "| Cache read | 48,661 |");
        assertContains(report, "| Cache write | 25,246 |");
        assertContains(report, "| Total tokens | 75,241 |");
        assertContains(report, "| Cost | $0.20 |");

        // Run info
        assertContains(report, "| Duration | 0m 28s (28s) |");
        assertContains(report, "| Tool calls | 2 |");
        assertContains(report, "-Dai.prompt=\"Say Hello.\"");
        assertContains(report, "-Dai.agent=claude-acp");
    }

    // ─── History JSONL recording ──────────────────────────────────────

    @Test
    @DisplayName("record appends valid JSON lines to history.jsonl")
    void recordWritesJsonl() throws IOException {
        Path historyFile = tempDir.resolve("history.jsonl");
        ResultsTracker tracker = new ResultsTracker(historyFile);

        tracker.record(buildResult("p1", "model-a", "run-a"));
        tracker.record(buildResult("p2", "model-b", "run-b"));

        List<String> lines = Files.readAllLines(historyFile);
        assertEquals(2, lines.size());

        for (String line : lines) {
            JsonNode node = JSON.readTree(line);
            assertTrue(node.has("project"));
            assertTrue(node.has("usage"));
            assertTrue(node.path("usage").has("total_tokens"));
            assertTrue(node.path("usage").has("total_cost"));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private MigrationResult buildResult(String project, String model, String runName) {
        SkillReference skillRef = new SkillReference("test-skill", null, "/tmp/skill");
        MigrationResult result = new MigrationResult("claude-acp", project, model, "full", skillRef);
        result.setRunName(runName);
        result.setDuration(Duration.ofSeconds(28));
        result.setPrompt("Say Hello.");
        result.setTotalTokens(EXPECTED_TOTAL_TOKENS);
        result.setTotalCost(EXPECTED_COST);
        result.setApiCalls(0);
        result.setToolCalls(EXPECTED_TOOL_CALLS);
        result.setInputTokens(EXPECTED_INPUT_TOKENS);
        result.setOutputTokens(EXPECTED_OUTPUT_TOKENS);
        result.setThinkingTokens(0);
        result.setCacheRead(EXPECTED_CACHE_READ);
        result.setCacheWrite(EXPECTED_CACHE_WRITE);
        return result;
    }

    private static void assertContains(String content, String expected) {
        assertTrue(content.contains(expected),
                "Expected to find:\n  " + expected + "\nin:\n" + content);
    }
}
