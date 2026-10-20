package org.acme;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpSseTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class CheckToolsTest {

    @Test
    public void testToolsCall() {
        McpSseTestClient client = McpAssured.newConnectedSseClient();

        client.when()
                .toolsCall("greetHello", Map.of("value", "Quarkus"), r -> {
                    assertFalse(r.isError());
                    assertTrue(r.firstContent().asText().text().startsWith("Hello from the MCP server"));
                })
                .thenAssertResults();

        client.when()
                .toolsCall("greetBye", Map.of("value", "Quarkus"), r -> {
                    assertFalse(r.isError());
                    assertTrue(r.firstContent().asText().text().startsWith("Bye from the MCP server"));
                })
                .thenAssertResults();

        client.disconnect();
    }
}
