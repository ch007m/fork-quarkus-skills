# Module: Prerequisite — Pre-flight Environment & Toolchain Validation

Validate that the local environment meets all hard requirements before any migration work begins.
This module **ALWAYS** runs as the first step, before `planning`, `build`, and all transformation modules.
If any **hard** check fails, the migration is aborted immediately.
This module runs before planning — `migration-spec.yaml` does not exist yet — so the required version is resolved from available inputs using the priority order below.

## Preconditions

None — this module has no preconditions. It must run before all other modules.

## Checks

Run all checks below in order. Classify each result as:
- **PASS** — requirement met; continue.
- **WARN** — requirement met at a reduced level; note it and continue.
- **FAIL (hard)** — requirement not met; log the error, stop the migration, and do not proceed.

After all checks, print the summary table and apply the gate rule.

---

### 1. JDK Version

#### Step 1: Resolve the required JDK version

Use the first source that provides a value (highest priority first):

| Priority | Source | How to read it |
|---|---|---|
| 1 | Skill argument | `java_version` passed directly when invoking the skill |
| 2 | `.quarkus-migration.yml` | `java_version` field in `<source>/.quarkus-migration.yml` (if the file exists) |
| 3 | Safe default | **17** — the absolute minimum required by any supported Quarkus version (Quarkus 3.x requires JDK 17; Quarkus 4.x requires JDK 21) |

Call the resolved value `<required_jdk>`.

#### Step 2: Check the installed JDK

- [ ] Run `java -version` and capture the installed version.
- [ ] If the installed version is **>= `<required_jdk>`**, record **PASS**, capture the exact version string for the summary table, and proceed to the next check.
- [ ] If the installed version is **< `<required_jdk>`** or `java` is not found:
    - **Warn the user**: "JDK `<required_jdk>` or later is required for this migration (resolved from: `<source>`). Currently, installed: `<detected version or 'none'>`. Please install JDK `<required_jdk>` and ensure it is on your PATH before retrying."
    - Record as **FAIL (hard)**.

---

### 2. Build Tool

- [ ] Detect the build tool by checking which files exist at `<source>`:

| File present in `<source>` | Detected build tool |
|---|---|
| `pom.xml` | Maven |
| `build.gradle` or `build.gradle.kts` | Gradle |
| Both | Maven (prefer Maven; note the overlap) |
| Neither | Record **FAIL (hard)** — no recognised build descriptor in `<source>`; migration cannot proceed |

- [ ] **If Maven detected** — run `./mvnw --version` if `mvnw` is present in `<source>`, otherwise `mvn --version`:
    - Found: record **PASS** and capture the version string.
    - Not found: record **FAIL (hard)** — Maven is not installed or not on `PATH`.
    - Version < 3.6: record **WARN** — the migrated Quarkus build file may require features not present in very old Maven releases; upgrade to 3.9+ is recommended.

- [ ] **If Gradle detected** — run `./gradlew --version` if `gradlew` is present in `<source>`, otherwise `gradle --version`:
    - Found: record **PASS** and capture the version string.
    - Not found: record **FAIL (hard)** — Gradle is not installed or not on `PATH`.
    - Version < 7.0: record **WARN** — the migrated Quarkus build file may require features not present in very old Gradle releases; upgrade to 8.x is recommended.

Capture the exact version string and whether the wrapper or system binary was used, for the summary table.

---

### 3. Container Runtime

- [ ] Try `docker version`, then `podman version` as a fallback.
- Found and daemon reachable: record **PASS**.
- Found but daemon unreachable: record **WARN** — container runtime not running; container image builds will not be available.
- Neither found: record **WARN** — no container runtime detected; container image builds will not be available.

Container absence is never a hard failure. Log the result and continue.

---

## Summary Table

After completing all checks, print:

```
=== Prerequisite Check Results ===

Check                  Result   Detail
─────────────────────────────────────────────────────────────────
JDK version            PASS     OpenJDK 21.0.3 (2024-04-16)
Build tool             PASS     Maven 3.9.6 (via ./mvnw)
Container runtime      WARN     Docker found but daemon not running

Gate: PASS — all hard checks passed; 1 warning recorded
```

Use `FAIL` in the Gate line if any hard check failed, and list every failing check.

---

## Gate Rule

- **All hard checks PASS** → log `Gate: PASS`, mark this module complete, and allow the migration to proceed.
- **Any hard check FAIL** → log `Gate: FAIL — <check name>: <reason>`, print the remediation guidance below, and **stop the migration**. Do not load or execute any subsequent module.

Warnings do not block the gate.

---

## Remediation Guidance

Print the relevant guidance for each hard failure:

| Check | Remediation |
|---|---|
| JDK not found or < `<required_jdk>` | Install JDK `<required_jdk>` from https://adoptium.net and ensure `java` is on your `PATH` |
| Maven not found | Install Maven from https://maven.apache.org/download.cgi and ensure `mvn` is on your `PATH`, or add an `mvnw` wrapper to the project |
| Gradle not found | Install Gradle from https://gradle.org/install or add a `gradlew` wrapper to the project |
| No build descriptor | Ensure `<source>` points to a Maven or Gradle project root containing `pom.xml` or `build.gradle(.kts)` |
