package io.quarkus.ai.harness.runner.acp;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;

import java.util.Map;

final class AcpUtil {

    private AcpUtil() {
    }

    static void logInitialized(InitializeResponse init) {
        var agentInfo = init.agentInfo();
        String title = agentInfo.title();
        String connectedMsg = (title != null && !title.isEmpty())
                ? String.format("Connected to the ACP agent: %s - v%s - %s",
                agentInfo.name(), agentInfo.version(), title)
                : String.format("Connected to the ACP agent: %s - v%s",
                        agentInfo.name(), agentInfo.version());
        System.out.println(connectedMsg);
    }

    static void logSessionCreated(NewSessionResponse session, String cwd) {
        if (session.configOptions() != null) {
            session.configOptions().stream()
                    .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                    .findFirst()
                    .ifPresent(opt -> System.out.println(
                            "Agent model: " + opt.currentValue()));
        }
    }

    static String extractText(Object content) {
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : content.toString();
        }
        return content != null ? content.toString() : "";
    }
}
