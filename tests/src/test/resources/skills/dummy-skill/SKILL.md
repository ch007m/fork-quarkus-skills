---
name: dummy-skill
description: Dummy skill for testing argument substitution
arguments:
  - tool
  - format
---

# Instructions

Analyze the code using `$tool` and output as `$format`.

**IF** `$tool` is "mtool":
  - Run: mtool scan --output $format
**ELSE**:
  - Read the source code manually

All arguments: $ARGUMENTS
First arg: $0
Second arg: $1
