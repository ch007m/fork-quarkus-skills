# create-quarkus-mcp-server

A skill that scaffolds a complete Quarkus MCP server project from scratch.

## What it does

When activated, the skill:

1. Asks for `groupId`, `artifactId`, `version`, target directory, and **transport** (`stdio` or `http`, default: `stdio`)
2. Bootstraps a Quarkus **3.40.1** (latest LTS) project via the `code.quarkus.io` REST API
3. Patches `pom.xml` — adds the MCP test dependency, and for stdio: `uber-jar` packaging + `skipITs=false`
4. Creates `GreetingTools.java` with `@Tool`-annotated `greetHello` and `greetBye` methods
5. Creates the appropriate test class:
   - **stdio**: `CheckToolsIT.java` — a Failsafe integration test that launches the über-jar via stdio
   - **http**: `CheckToolsTest.java` — a `@QuarkusTest` that tests via `McpAssured.newConnectedSseClient()`
6. Creates `README.md` in the project root with build, test, and MCP client registration instructions
7. Builds the project and runs tests (`mvn verify` for stdio, `mvn test` for http)
8. Writes the MCP client config snippet (command-based for stdio, URL-based for http)

## Trigger phrases

- "create a Quarkus MCP server"
- "scaffold quarkus mcp"
- "quarkus mcp server"
- "quarkiverse mcp"
- "generate a quarkus project with MCP tools"

## Fixed versions

| Component | Version |
|-----------|---------|
| Quarkus platform (LTS) | `3.40.1` |
| Java minimum | 21 |

## File layout

```
skills/scaffold-quarkus-mcp-server/
├── SKILL.md                        ← procedural instructions the agent follows
├── README.md                       ← this file (skill documentation)
└── reference/
    ├── GreetingTools.java          ← @Tool template (shared, both transports)
    ├── stdio/
    │   ├── README.md               ← project README template (stdio transport)
    │   └── CheckToolsIT.java       ← Failsafe IT template (launches uber-jar via stdio)
    └── http/
        ├── README.md               ← project README template (HTTP transport)
        └── CheckToolsTest.java     ← @QuarkusTest template (Streamable HTTP)
```

## Usage

Start a new conversation and say:

> "Create a Quarkus MCP server called my-greeter in groupId org.acme"

The agent will ask for any missing coordinates (including transport preference) and scaffold the full project.
