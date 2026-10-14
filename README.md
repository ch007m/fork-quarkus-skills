# Quarkus Skills

Agent skills for developing and maintaining Quarkus applications

**NOTE**: This is a work in progress. Use at your own risk.

This repository contains a collection of skills for developing and maintaining Quarkus applications.

## Installation

### Using npx skills add

Install all skills from this repository:

```bash
npx skills add quarkusio/skills
```

List available skills:

```bash
npx skills add quarkusio/skills --list
```

Install specific skills only:

```bash
npx skills add quarkusio/skills --skill quarkus-update
```

Install for specific agents:

```bash
npx skills add quarkusio/skills -a claude-code
```

### Using Claude Code Plugin System

Within Claude Code, you can also install via the plugin marketplace:

```
/plugin marketplace add quarkusio/skills
/plugin install quarkus-appdev@quarkus-skills
```

## Available Skills

| Skill | Category | Triggers |
|-------|----------|----------|
| [quarkus-update](#quarkus-update) | Quarkus | "check project", "update quarkus", "quarkus upgrade" |
| [migrate-spring-to-quarkus](#migrate-spring-to-quarkus) | Migration | "spring to quarkus", "quarkus migration", "replace spring" |
| [scaffold-quarkus-mcp-server](#scaffold-quarkus-mcp-server) | Scaffolding | "create mcp server", "scaffold mcp", "quarkus mcp" |

<details>
<summary><strong>quarkus-update</strong></summary>

Check if a Quarkus project's build files are up-to-date by comparing against reference generated projects. Use this skill when you want to:

- Check if your Quarkus project is up-to-date
- Compare your build configuration against the latest Quarkus version
- Upgrade your Quarkus version with guidance on what changes to make

</details>

<details>
<summary><strong>migrate-spring-to-quarkus</strong></summary>

Migrate Spring Boot applications to Quarkus using a modular, gate-driven approach. Supports both Spring compatibility extensions and full Quarkus migration paths. Use this skill when you want to:

- Migrate a Spring Boot application to Quarkus
- Convert Spring annotations (DI, REST, Data, Security) to Quarkus equivalents
- Migrate Spring build files, configuration, frontend (Thymeleaf/JSP), and tests

</details>

<details>
<summary><strong>scaffold-quarkus-mcp-server</strong></summary>

Scaffold a complete Quarkus MCP server project using the latest LTS Quarkus release and the `quarkus-mcp-server` Quarkiverse extension. Use this skill when you want to:

- Create a new Quarkus MCP server project with tools, resources, and prompts
- Generate a project skeleton bootstrapped via code.quarkus.io
- Get integration tests and README included out of the box

</details>

## Learn More

- [npx skills documentation](https://github.com/vercel-labs/skills)
- [Claude Code skills documentation](https://code.claude.com/docs/en/plugin-marketplaces)
