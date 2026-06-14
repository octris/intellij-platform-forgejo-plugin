# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JetBrains IDE plugin ("Forgejo Integration") that surfaces [Forgejo](https://forgejo.org) data inside IntelliJ-based IDEs. Built from the IntelliJ Platform Plugin Template (Kotlin + IntelliJ Platform Gradle Plugin 2.x), targeting IntelliJ IDEA 2025.2.x. **JDK 21 is required.**

The first feature is a per-commit Forgejo Actions (CI) status column in the VCS log.

## Commands

Use the Gradle wrapper (`./gradlew`):

- `./gradlew buildPlugin` — build the distributable plugin ZIP into `build/distributions/`. Runs `patchPluginXml` (injects version/since-build from `gradle.properties`).
- `./gradlew runIde` — launch a sandbox IDE with the plugin installed (also available as the **Run Plugin** run config). Sandbox lives under `.intellijPlatform/sandbox/`.
- `./gradlew test` — run the test suite.
- `./gradlew test --tests "octris.forgejo.MyPluginTest.testProjectService"` — run a single test. Tests are cached/`FROM-CACHE`; prepend `cleanTest` to force re-execution.
- `./gradlew check` — tests + all verifications.
- `./gradlew verifyPlugin` — run the IntelliJ Plugin Verifier.

Tests extend `BasePlatformTestCase` (in-process IDE fixture); test data lives in `src/test/testData/`.

## Architecture

**Naming is uniform: `octris.forgejo`** is the Gradle `group`, the `plugin.xml` `<id>`, and the Kotlin source package root (`src/main/kotlin/octris/forgejo/`). Do not introduce a reverse-domain variant — `is.octr.forgejo` was rejected because `is` is a Kotlin keyword. The plugin descriptor is `src/main/resources/META-INF/plugin.xml`.

Feature code is layered:
- `settings/` — `ForgejoSettings` (app-level `PersistentStateComponent`, holds the server URL) and `ForgejoCredentials` (token in the IDE **password safe**, never in plain settings). `ForgejoSettingsConfigurable` is the UI at **Settings | Tools | Forgejo Integration**.
- `api/` — `ForgejoApiClient` (REST client, currently a **stub** returning `UNKNOWN`) and `ForgejoCommitStatus` (status enum → icon/label, with `fromState()` mapping Forgejo's `state` string).
- `vcs/` — `ForgejoActionsColumn` (a `VcsLogCustomColumn` registered via the `com.intellij.vcsLogCustomColumn` EP) and `ForgejoCommitStatusService` (per-project service caching `hash → status`).

Key constraint: `VcsLogCustomColumn.getValue()` runs on the EDT during painting and **must not block**. The column reads only from `ForgejoCommitStatusService`'s in-memory cache and, on a miss, schedules a background fetch; a repaint trigger after fetch completion is still a TODO.

### Gotcha: VCS-log classes need a bundled module

`VcsLogCustomColumn`, `GraphTableModel`, and `VcsLogGraphTable` live in `intellij.platform.vcs.log.impl`, which is **not on the compile classpath by default**. `build.gradle.kts` adds `bundledModule("intellij.platform.vcs.log.impl")`; removing it breaks compilation. `plugin.xml` also `<depends>` on `com.intellij.modules.vcs`.

### Template scaffold (to be removed)

`services/MyProjectService`, `startup/MyProjectActivity`, `toolWindow/MyToolWindowFactory`, `MyBundle`, and `MyPluginTest` are leftover sample code from the template, intentionally kept as reference. Remove them (and their `plugin.xml` registrations) as real features replace them. Plugin-owned strings go in `ForgejoBundle` (`messages/ForgejoBundle.properties`), kept separate from the sample `MyBundle` so it survives that cleanup.

## Forgejo API

REST API reference (Swagger): **https://git.octr.is/api/swagger**

Combined commit status (what the VCS column needs):
`GET {server}/api/v1/repos/{owner}/{repo}/commits/{sha}/status` with header `Authorization: token <personal-access-token>`. The `state` field (`success` | `failure` | `pending` | `error`) maps via `ForgejoCommitStatus.fromState()`. Per-workflow run progress is under `/repos/{owner}/{repo}/actions/...`.

## Git remotes & CI

Two remotes: `origin` → GitHub (`github.com:octris/...`, lowercase) and `forgejo` → `ssh://git@git.octr.is:42042/Octris/...` (note the capital `Octris`). Use `fj` (Forgejo CLI, `--host git.octr.is`) for Forgejo PRs/issues/releases; standard `gh`/git for GitHub.

CI: `.github/workflows/build.yml` builds, tests, verifies, and drafts releases. It triggers on **push to `main`** and on pull requests. Forgejo Actions reads `.github/workflows/` too, so the same workflow runs on both hosts (Forgejo runs require an Actions runner registered on the instance).
