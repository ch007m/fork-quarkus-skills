# Quarkus MCP Server to greet users

A Quarkus-based MCP (Model Context Protocol) server exposing `greetHello` and `greetBye` tools via the
[quarkus-mcp-server](https://github.com/quarkiverse/quarkus-mcp-server) extension.

The project supports **two transports** — choose one via Maven profiles:

| Profile | Transport | Packaging | Dependency |
|---------|-----------|-----------|------------|
| `stdio` (default) | Standard I/O | uber-jar | `quarkus-mcp-server-stdio` |
| `http` | Streamable HTTP (`/mcp`) | thin jar | `quarkus-mcp-server-http` |

---

## Available tools

| Tool name | Description | Parameter |
|-----------|-------------|-----------|
| `greetHello` | Greet a user with Hello | `name` (string, default: `"Quarkus"`) |
| `greetBye` | Greet a user with Bye | `name` (string, default: `"Quarkus"`) |

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Java (JDK) | 21 |
| Maven | 3.9+ |

---

## stdio transport (default)

### Build

```bash
mvn package
```

This produces an uber-jar at `target/quarkus-mcp-server-1.0.0-SNAPSHOT-runner.jar`.

### Run

```bash
java -jar target/quarkus-mcp-server-1.0.0-SNAPSHOT-runner.jar
```

The server reads JSON-RPC requests from stdin and writes responses to stdout.

### Test

The `stdio` profile runs integration tests (`CheckToolsIT`) via Maven Failsafe.
The test launches the uber-jar as a subprocess and calls tools over the stdio protocol.

```bash
mvn verify
```

> Unit tests are skipped in this profile. Integration tests are enabled by default (`skipITs=false`).

### Register with an MCP client

#### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "quarkus-mcp-server": {
      "command": "java",
      "args": ["-jar", "/path/to/target/quarkus-mcp-server-1.0.0-SNAPSHOT-runner.jar"]
    }
  }
}
```

#### Use with SmallRye ACP agent (stdio)

The [SmallRye ACP Client](https://github.com/smallrye/smallrye-acp-client) is a Java CLI implementing the
[Agent Client Protocol](https://github.com/agentclientprotocol/agent-client-protocol) (ACP).
It can launch this MCP server as a stdio subprocess and expose its tools to any ACP-compatible coding agent.

- Install the ACP CLI (JBang)

```bash
jbang app install --name acp io.smallrye.ai:acp-java-client:0.1.0:runner
```

- Run with inline MCP server config

```bash
acp run \
  -a claude-acp \
  --mcp-server-config '{"type":"stdio","name":"greet-tools","command":"java","args":["-jar", "/path/to/target/quarkus-mcp-server-1.0.0-SNAPSHOT-runner.jar"]}' \
  -p "Greet the user World"
...  
Starting the AI conversation ...
The MCP greet server says: **Hello from the MCP server, World!**
```

- Run instead with a JSON config file

Create a file `mcp-servers.json`:

```json
[
  {
    "type": "stdio",
    "name": "quarkus-mcp-server",
    "command": "java",
    "args": ["-jar", "/path/to/target/quarkus-mcp-server-1.0.0-SNAPSHOT-runner.jar"]
  }
]
```

Then:

```bash
acp run -a claude-acp \
  --mcp-server-config mcp-servers.json \
  -p "Greet the user World"
```

> The `--mcp-server-config` option accepts inline JSON (string or array) or a path to a JSON file.
> Supported transport types: `stdio`, `sse`, `http`.

---

## HTTP transport

### Build

```bash
mvn package -Phttp
```

### Run

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

The server starts on `http://localhost:8080` and exposes the MCP endpoint at `/mcp`.

### Test

The `http` profile runs unit tests (`CheckToolsTest`) via Maven Surefire.
The test uses `@QuarkusTest` with `McpStreamableTestClient` to call tools over the Streamable HTTP protocol.

```bash
mvn test -Phttp
```

> Integration tests are skipped in this profile. Unit tests are enabled (`skipTests=false`).

### Call a tool with curl (stateless)

The examples below use the **stateless** protocol (`2026-07-28`). Each request is self-contained — no
`initialize` handshake or session header is needed.

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: greetHello" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientInfo": {
          "name": "curl-client",
          "version": "1.0.0"
        },
        "io.modelcontextprotocol/clientCapabilities": {}
      },
      "name": "greetHello",
      "arguments": {
        "name": "World"
      }
    }
  }'
```

Expected response:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Hello from the MCP server, World! - 09/10/2026 15:30:00"
      }
    ],
    "isError": false
  }
}
```

> **Tip (Stateful mode):** You can also use the older stateful protocol (`2025-03-26` or earlier) by sending
> an `initialize` request first, then passing the `Mcp-Session-Id` header on subsequent calls. See the
> [transport docs](https://docs.quarkiverse.io/quarkus-mcp-server/dev/concepts-transports.html) for details.

---

## Project structure

```
src/
  main/java/org/acme/
    GreetingTools.java      <- @Tool-annotated methods
  test/java/org/acme/
    CheckToolsIT.java       <- Integration test (stdio: runs uber-jar as subprocess)
    CheckToolsTest.java     <- Unit test (HTTP: @QuarkusTest with McpStreamableTestClient)
pom.xml
```