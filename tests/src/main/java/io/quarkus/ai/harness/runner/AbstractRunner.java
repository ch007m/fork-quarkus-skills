package io.quarkus.ai.harness.runner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public abstract class AbstractRunner {
    protected String aiAgent;
    protected String model;
    protected String strategy;
    protected int timeoutSeconds;
    protected Path skillPath;
    protected String prompt;
    protected ObjectMapper JSON;

    public AbstractRunner(String aiAgent, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt) {
        this.aiAgent = aiAgent;
        this.model = model;
        this.skillPath = skillPath;
        this.strategy = strategy;
        this.timeoutSeconds = timeoutSeconds;
        this.prompt = prompt;
        this.JSON = new ObjectMapper();
    }

    /**
     * Parse AI session JSON files to extract token usage and cost.
     */
    public abstract AgentRunner.UsageStats extractUsage(List<String> sessionFiles);

    // TODO: Do we still need this method. To be investigated
    public void copySkills(Path source, Path target) throws IOException {
        // Use try-with-resources to auto-close the stream
        try (var stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    // Resolve target path relative to source
                    Path targetPath = target.resolve(source.relativize(sourcePath));

                    if (Files.isDirectory(sourcePath)) {
                        // Create directories (including parents)
                        Files.createDirectories(targetPath);
                    } else {
                        // Copy file with attributes and overwrite existing
                        Files.copy(sourcePath, targetPath,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e); // Handle or propagate
                }
            });
        }
    }

    protected String generateMigrationPrompt(Path sourceDir, Path targetDir) {
        return """
                Migrate this Spring Boot project to Quarkus using the %s migration strategy.
                Source project (read-only): %s
                Target project (write here): %s
                Read from the source directory and write all migrated files to the target directory. \
                Do not modify the source directory. \
                Do a full migration -- convert all source files, build files, config, and tests. \
                After migration, verify the project compiles with cd %3$s && ./mvnw compile and fix any errors. \
                Then run cd %3$s && ./mvnw test and fix any test failures.
                If you need to delete code or files, explain why you are deleting them and what you are replacing them with.
                If anything could not be converted/migrated explain why - do not just delete/remove it without explaining.
                Include a summary of the migration in the end of the output.""".formatted(
                strategy, sourceDir, targetDir);
    }

    /** Print to both System.out and the pretty log file. */
    protected void printBoth(String text, BufferedWriter prettyWriter) {
        System.out.println(text);
        try {
            synchronized (prettyWriter) {
                prettyWriter.write(text);
                prettyWriter.newLine();
                prettyWriter.flush();
            }
        } catch (IOException ignored) {
        }
    }
}
