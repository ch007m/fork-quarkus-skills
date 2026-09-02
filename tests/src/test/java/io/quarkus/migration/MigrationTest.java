package io.quarkus.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.migration.runner.AgentRunner;
import io.quarkus.migration.runner.RunnerRegistry;
import static io.quarkus.migration.runner.RunnerRegistry.resolveModel;
import static io.quarkus.migration.runner.RunnerRegistry.resolveProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static io.quarkus.migration.util.AiConfig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test suite that runs migration skills against test projects and verifies the results.
 *
 * <p>Configuration via system properties:
 * <ul>
 *   <li>{@code ai.model} — model to use (default: vertex-anthropic/claude-sonnet-4-5@20250929)</li>
 *   <li>{@code ai.strategy} — migration strategy: full or compatibility (default: full)</li>
 *   <li>{@code ai.timeout} — timeout in seconds per project (default: 300)</li>
 *   <li>{@code ai.cmd} — path to AI agent binary (default: opencode)</li>
 *   <li>{@code ai.projects} — run only the project(s) (default: all)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Run all projects with defaults
 * mvn test
 *
 * # Run a specific project
 * mvn test -Dai.projects=spring-rest-api
 *
 * # Compare models
 * mvn test -Dai.model=vertex-anthropic/claude-sonnet-4-5@20250929
 * mvn test -Dai.model=google/gemini-2.5-pro
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ResultsTracker tracker = ResultsTracker.defaultTracker();
    private static final SkillResolver skillResolver = new SkillResolver(
            skillsDir(), Path.of("target", "skills").toAbsolutePath());

    /**
     * Provides parameterized test arguments by scanning the projects directory
     * for subdirectories containing a {@code project.yaml} file.
     *
     * <p>Each {@link Arguments} pair contains a {@link ProjectConfig} and its
     * corresponding directory {@link Path}. Results can be filtered by project
     * name via {@code AI_PROJECTS} or include disabled tests via {@code AI_ENABLED=all}.
     *
     * @return a stream of (ProjectConfig, Path) pairs for parameterized tests
     * @throws IOException if the projects directory cannot be listed or a
     *         {@code project.yaml} file cannot be parsed
     */
    static Stream<Arguments> projectsToTest() throws IOException {
        Path pathToProjectsDir = projectsDir();
        String projectList = aiProjects();

        Set<String> projects = new LinkedHashSet<>();
        if (!projectList.isEmpty()) {
            for (String p : projectList.split(",")) {
                String trimmed = p.trim();
                if (trimmed.isEmpty()) continue;
                if (!validProjectNamePattern().matcher(trimmed).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid project name: '" + trimmed + "' — only alphanumerics, hyphens, and underscores are allowed");
                }
                projects.add(trimmed);
            }
        }

        boolean includeDisabled = "all".equalsIgnoreCase(aiEnabled());
        boolean filterByName = !projectList.isEmpty();

        try (var dirs = Files.list(pathToProjectsDir)) {
            return dirs
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("project.yaml")))
                    .filter(p -> filterByName ? projects.contains(p.getFileName().toString()) : true)
                    .sorted()
                    .map(p -> {
                        try {
                            ProjectConfig config = YAML.readValue(
                                    p.resolve("project.yaml").toFile(),
                                    ProjectConfig.class);
                            return Arguments.of(config, p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(args -> {
                        ProjectConfig config = (ProjectConfig) args.get()[0];
                        return config.isTestEnabled() || includeDisabled || filterByName;
                    })
                    .toList()  // materialize before stream closes
                    .stream();
        }
    }

    private static final Map<String, List<MigrationResult>> skillsComparisonResults = new LinkedHashMap<>();

    // -- the actual test --

    @ParameterizedTest(name = "{0}")
    @MethodSource("projectsToTest")
    @Order(1)
    void migrate(ProjectConfig config, Path projectDir) throws Exception {
        // Resolve provider/model defaults per agent
        String provider = resolveProvider(aiCmd(), aiProvider());
        String model = resolveModel(aiCmd(), aiModel());
        String modelDisplay = aiModelDisplay(provider, model);

        boolean projectDefinesChecks = config.checks() != null && !config.checks().isEmpty();
        boolean hasChecks = aiChecks() && projectDefinesChecks;
        int totalRuns = runs();

        // Determine which skills to iterate
        List<String> skills = aiSkills();
        boolean isBenchmark = skills.size() > 1;
        if (skills.isEmpty()) {
            skills = List.of(config.skill());
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("PROJECT: " + config.name());
        System.out.println("  agent:    " + aiCmd());
        System.out.println("  provider: " + (provider.isEmpty() ? "(n/a)" : provider));
        System.out.println("  model:    " + model);
        System.out.println("  timeout:  " + aiTimeout() + "s");
        System.out.println("  checks:   " + (hasChecks ? config.checks().keySet() : !aiChecks() ? "disabled (runChecks=false)" : "disabled (none defined)"));
        System.out.println("  skills:   " + skills);
        if (totalRuns > 1) {
            System.out.println("  runs:     " + totalRuns + " (per skill)");
        }
        System.out.println("=".repeat(60));

        Path outputDir = Path.of("target", "runs").toAbsolutePath();
        int timeout = config.timeout() > 0 ? config.timeout() : aiTimeout();
        String modelShort = model.isEmpty() ? "default" : model.replaceAll("[^a-zA-Z0-9-]", "-");

        List<String> lastFailures = new ArrayList<>();
        Path lastWorkDir = null;
        String lastScore = "0/0";

        for (String skillRefStr : skills) {
            Path skillPath = skillResolver.resolve(skillRefStr);

            boolean isUrl = skillRefStr.startsWith("https://") || skillRefStr.startsWith("http://") || skillRefStr.startsWith("git@");
            SkillReference skillRef = new SkillReference(
                    isUrl ? extractSkillShortName(skillRefStr) : skillRefStr,
                    isUrl ? skillRefStr : null,
                    skillPath.toString());

            assertTrue(Files.isDirectory(skillPath),
                    "Skill directory not found: " + skillPath);

            String skillShort = extractSkillShortName(skillRefStr);
            boolean isSpringMigration = "spring-boot".equals(config.type()) && isMigrationSkill(skillRefStr);
            if (isSpringMigration) {
                System.out.println("  strategy: " + aiStrategy());
            }
            String suffix = isSpringMigration ? "_" + modelShort + "_" + aiStrategy() : "_" + modelShort;
            String baseRunName = isBenchmark
                    ? config.name() + "_" + skillShort + suffix
                    : config.name() + suffix;

            if (isBenchmark) {
                System.out.println("\n" + "=".repeat(60));
                System.out.printf("  SKILL: %s%n", skillRefStr);
                System.out.println("=".repeat(60));
            }

            List<MigrationResult> skillResultRuns = new ArrayList<>();

            for (int run = 1; run <= totalRuns; run++) {
                String runName = totalRuns > 1 ? baseRunName + "_run" + run : baseRunName;

                if (totalRuns > 1) {
                    System.out.println("\n" + "-".repeat(60));
                    System.out.printf("  RUN %d/%d%n", run, totalRuns);
                    System.out.println("-".repeat(60));
                }

                // 1. Prepare a fresh working directory
                Path workDir = prepareWorkDir(config, projectDir);
                lastWorkDir = workDir;

                System.out.println("  workdir:  " + workDir);
                System.out.println("  outputs:  " + outputDir.resolve(runName + ".*"));

                MigrationResult result = new MigrationResult(aiCmd(),
                        config.name(), modelDisplay, aiStrategy(), skillRef);
                result.setWorkDir(workDir.toString());
                result.setRunName(runName);
                result.setPrompt(aiPrompt());
                result.setUserProvider(aiProvider());
                result.setUserModel(aiModel());
                result.setProjectType(config.type());

                // 2. Run migration
                AgentRunner runner = RunnerRegistry.getRunner(aiCmd(), provider, model, skillPath, aiStrategy(), timeout, aiPrompt(), aiArgs(), aiSanitize());

                System.out.printf("  Running migration agent: %s ...%n", aiCmd());
                AgentRunner.RunOutput output = runner.run(workDir, outputDir, runName);

                result.setAiExitCode(output.exitCode());
                result.setDuration(output.duration());
                // Register the ./target/runs/*.json.log file generated during the execution of the agent process as Session file
                // This file is not 100% equivalent to the agent file created by an agent like claude, etc as it contains the data we extracted
                // using printEvent() method
                result.setSessionFiles(output.sessionFiles());

                System.out.println("  Migration completed in " + output.duration().toSeconds() + "s (exit=" + output.exitCode() + ")");

                // 3. Extract usage stats from session
                AgentRunner.UsageStats usage = runner.extractUsage(output.sessionFiles());
                result.setTotalTokens(usage.totalTokens());
                result.setTotalCost(usage.totalCost());
                result.setApiCalls(usage.apiCalls());
                result.setToolCalls(usage.toolCalls());
                result.setInputTokens(usage.inputTokens());
                result.setOutputTokens(usage.outputTokens());
                result.setThinkingTokens(usage.thinkingTokens());
                result.setCacheRead(usage.cacheRead());
                result.setCacheWrite(usage.cacheWrite());
                result.setModelUsages(usage.modelUsages());

                // 4. Run checks
                List<String> failures = new ArrayList<>();
                if (hasChecks) {
                    MigrationChecks checks = new MigrationChecks(workDir);
                    System.out.println("  Running checks...");

                    config.checks().forEach((checkName, checkConfig) -> {
                        System.out.print("    " + checkName + " ... ");
                        boolean passed = checks.runCheck(checkName, checkConfig);
                        result.addCheck(checkName, passed);
                        System.out.println(passed ? "PASS" : "FAIL");
                        if (!passed) {
                            failures.add(checkName);
                        }
                    });
                } else {
                    System.out.println("  Skipping checks" + (!aiChecks() ? " (runChecks=false)" : " (none defined)"));
                }

                // 5. Run skill review (separate ai session)
                if (aiReview() && hasChecks && !output.sessionFiles().isEmpty()) {
                    AgentRunner.ReviewOutput reviewOutput = runner.review(
                            output.sessionFiles().getFirst(), workDir, outputDir, runName, skillPath, result.getChecks());
                    result.setReview(reviewOutput.review());
                    result.setReviewTokens(reviewOutput.usage().totalTokens());
                    result.setReviewCost(reviewOutput.usage().totalCost());
                } else {
                    String reason = !aiReview() ? " (ai.review=false)"
                            : output.sessionFiles().isEmpty() ? " (no session files exported)"
                            : " (no checks defined)";
                    System.out.println("  Skipping skill review" + reason);
                }

                // 6. Record result
                tracker.record(result);
                skillResultRuns.add(result);
                System.out.println("\n" + result);

                lastFailures = failures;
                lastScore = result.score();
            }

            // 7. Write per-skill report when multiple runs have been executed
            if (totalRuns > 1) {
                tracker.writeSkillSummaryReport(skillResultRuns, baseRunName);
            }

            // Collect skillResultRuns about the Skills compared (max 2) for the benchmark report
            skillsComparisonResults.computeIfAbsent(skillRefStr, k -> new ArrayList<>()).addAll(skillResultRuns);
        }

        // 8. Assert last run's checks passed (skip in benchmark mode to collect all results)
        if (!lastFailures.isEmpty() && !isBenchmark) {
            fail("Migration checks failed: " + lastFailures + "\n" +
                    "Work dir preserved at: " + lastWorkDir + "\n" +
                    "Score: " + lastScore);
        }
    }

    @AfterAll
    static void generateBenchmarkReport() {
        boolean multipleSkills = skillsComparisonResults.size() > 1;
        boolean multipleProjects = skillsComparisonResults.values().stream()
                .flatMap(List::stream)
                .map(MigrationResult::getProject)
                .distinct()
                .count() > 1;

        if (multipleSkills || multipleProjects) {
            tracker.writeBenchmarkComparisonReport(skillsComparisonResults);
        }
    }

    private static String extractSkillShortName(String skillRef) {
        String name = skillRef;
        if (skillRef.contains("/")) {
            name = skillRef.substring(skillRef.lastIndexOf('/') + 1);
        }
        return name.replaceAll("[^a-zA-Z0-9-]", "-");
    }

    private static boolean isMigrationSkill(String skillRef) {
        String name = extractSkillShortName(skillRef).toLowerCase();
        return name.contains("migrate");
    }

    // -- helpers --
    private Path prepareWorkDir(ProjectConfig config, Path projectDir) throws IOException, InterruptedException {
        // Put work dirs under target/workdirs/ so they survive JVM exit but get cleaned on mvn clean
        Path workdirsBase = Path.of("").toAbsolutePath().resolve("target").resolve("workdirs");
        Path workDir = workdirsBase.resolve(config.name());
        // Clean any previous run
        if (Files.exists(workDir)) {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
        Files.createDirectories(workDir);

        if (config.isLocal()) {
            Path source = projectDir.resolve("source");
            assertTrue(Files.isDirectory(source),
                    "Local source directory not found: " + source);
            copyDirectory(source, workDir);
        } else {
            // Clone from git
            List<String> cmd = new ArrayList<>(List.of(
                    "git", "clone", "--depth", "1"));
            if (config.ref() != null && !config.ref().isBlank()) {
                cmd.addAll(List.of("--branch", config.ref()));
            }
            cmd.add(config.source());
            cmd.add(workDir.toString());

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(done && p.exitValue() == 0,
                    "Failed to clone " + config.source());
        }

        return workDir;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                Path dest = target.resolve(source.relativize(src));
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
