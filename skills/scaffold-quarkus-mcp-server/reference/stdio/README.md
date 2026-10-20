# {{artifactId}} — Quarkus MCP Server (stdio)

A Quarkus-based MCP (Model Context Protocol) server exposing `greetHello` and `greetBye` tools via the
[quarkus-mcp-server](https://github.com/quarkiverse/quarkus-mcp-server) extension, using the **stdio** transport.

The server communicates over standard input/output (JSON-RPC over stdin/stdout) and is packaged as an uber-jar.

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Java (JDK) | 21 |
| Maven | 3.9+ |

---

## Build

```bash
mvn package -DskipTests
```

The uber-jar is created at:

```
target/{{artifactId}}-{{version}}-runner.jar
```

---

## Run

```bash
java -jar target/{{artifactId}}-{{version}}-runner.jar
```

The server reads JSON-RPC requests from stdin and writes responses to stdout.

---

## Test

The `CheckToolsIT` class is a Maven Failsafe integration test. It launches the uber-jar as a subprocess
and calls tools over the MCP stdio protocol.

```bash
# Build the jar first (required for the IT to find the runner)
mvn package -DskipTests

# Then run the integration tests
mvn verify
```

`CheckToolsIT` will:
1. Start `target/{{artifactId}}-{{version}}-runner.jar` as a subprocess.
2. Call `greetHello` and `greetBye` via the MCP stdio protocol.
3. Assert the response text starts with the expected prefix.

---

## Register with an MCP client

**Note**: Replace `/path/to` with the absolute path to your project.

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "{{artifactId}}": {
      "command": "java",
      "args": ["-jar", "/path/to/target/{{artifactId}}-{{version}}-runner.jar"]
    }
  }
}
```

### IBM Bob (workspace `.bob/mcp.json`)

```json
{
  "mcpServers": {
    "{{artifactId}}": {
      "command": "java",
      "args": ["-jar", "/path/to/target/{{artifactId}}-{{version}}-runner.jar"]
    }
  }
}
```

### SmallRye ACP agent

The [SmallRye ACP Client](https://github.com/smallrye/smallrye-acp-client) is a Java CLI implementing the
[Agent Client Protocol](https://github.com/agentclientprotocol/agent-client-protocol) (ACP).
It can launch this MCP server as a stdio subprocess and expose its tools to any ACP-compatible coding agent.

Install the ACP CLI via JBang:

```bash
jbang app install --name acp io.smallrye.ai:acp-java-client:0.1.0:runner
```

Run with inline MCP server config:

```bash
acp run -a claude-acp \
  --mcp-server-config '{"type":"stdio","name":"{{artifactId}}","command":"java","args":["-jar","/path/to/target/{{artifactId}}-{{version}}-runner.jar"]}' \
  -p "Greet the user World"
```

Or with a JSON config file (`mcp-servers.json`):

```json
[
  {
    "type": "stdio",
    "name": "{{artifactId}}",
    "command": "java",
    "args": ["-jar", "/path/to/target/{{artifactId}}-{{version}}-runner.jar"]
  }
]
```

```bash
acp run -a claude-acp \
  --mcp-server-config mcp-servers.json \
  -p "Greet the user World"
```

---

## Available tools

| Tool name | Description | Parameter |
|-----------|-------------|-----------|
| `greetHello` | Greet a user with Hello | `name` (string, default: `"Quarkus"`) |
| `greetBye` | Greet a user with Bye | `name` (string, default: `"Quarkus"`) |

---

## Project structure

```
src/
  main/java/<groupId-as-path>/
    GreetingTools.java      <- @Tool-annotated methods
  test/java/<groupId-as-path>/
    CheckToolsIT.java       <- Integration test (launches uber-jar via stdio)
pom.xml
```