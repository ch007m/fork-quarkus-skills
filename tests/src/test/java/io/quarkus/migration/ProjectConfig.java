package io.quarkus.migration;

import java.util.List;

/**
 * Maps to a project.yaml file defining a test project.
 */
public record ProjectConfig(
        String name,
        String description,
        String type,
        String skill,
        String source,
        String ref,
        int timeout,
        TestConfig test,
        List<String> checks
) {
    public record TestConfig(Boolean enabled) {
        public TestConfig {
            if (enabled == null) enabled = true;
        }
    }

    public boolean isTestEnabled() {
        return test == null || test.enabled();
    }

    public boolean isLocal() {
        return "local".equals(source);
    }
}
