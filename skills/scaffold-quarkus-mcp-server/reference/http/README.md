# {{artifactId}} — Quarkus MCP Server (HTTP)

A Quarkus-based MCP (Model Context Protocol) server exposing `greetHello` and `greetBye` tools via the
[quarkus-mcp-server](https://github.com/quarkiverse/quarkus-mcp-server) extension, using the **HTTP** transport.

The server exposes MCP endpoints over HTTP (Streamable HTTP at `/mcp`, SSE at `/mcp/sse`) and is packaged as a standard fast-jar.

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Java (JDK) | 21 |
| Maven | 3.9+ |

---

## Build

```bash
mvn package
```

---

## Run

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

The server starts on `http://localhost:8080` and exposes MCP endpoints:
- Streamable HTTP: `http://localhost:8080/mcp`
- SSE: `http://localhost:8080/mcp/sse`

---

## Test

The `CheckToolsTest` class is a `@QuarkusTest`. It starts the Quarkus application and calls tools
over the Streamable HTTP protocol using `McpStreamableTestClient`.

```bash
mvn test
```

`CheckToolsTest` will:
1. Start the Quarkus application in test mode.
2. Call `greetHello` and `greetBye` via the MCP Streamable HTTP endpoint.
3. Assert the response text starts with the expected prefix.

---

## Call a tool with curl (stateless)

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

## Register with an MCP client

### SSE / Streamable HTTP endpoint

Point any MCP client that supports HTTP transport to:

```
http://localhost:8080/mcp
```

Start the application first:
```bash
java -jar target/quarkus-app/quarkus-run.jar
```

### IBM Bob (workspace `.bob/mcp.json`)

```json
{
  "mcpServers": {
    "{{artifactId}}": {
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "{{artifactId}}": {
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
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
    CheckToolsTest.java     <- @QuarkusTest (Streamable HTTP via McpStreamableTestClient)
pom.xml
```