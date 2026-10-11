# Instructions for coding agents and models

## Artifact budget and cleanup — mandatory

- Arena's cumulative end-of-turn patchset is best-effort capped at about **128 MB combined** (and 10,000 files). Treat 128 MB as a hard budget: do not check in or leave generated binaries, downloaded SDKs, toolchains, build caches, APKs, JARs, datasets, or other large artifacts as part of a task unless the user explicitly asked for those artifacts.
- When a task needs large tools or inputs, make or use a small, reproducible bootstrap/build script that fetches them on demand, pins versions and verifies hashes/signatures where available, and places downloads/build tools in a temporary directory or an external cache — never in the source tree by default. The script must make it clear how to rerun the build and how to clean its cache.
- Build the requested outputs, run the relevant tests, inspect the outputs, and then remove the temporary build tools, downloaded inputs, intermediate files, and test outputs created only for the task. Keep only requested deliverables and small source/build scripts. Do not delete pre-existing user files, caches, tools, or outputs; only clean up artifacts created by this task.
- Before finishing, check `git status`, generated/build directories, and temporary caches. Confirm no large temporary files were accidentally staged or left in the workspace. If a required deliverable is itself large, explain the limit and provide a reproducible script or external link instead of silently leaving it behind.
