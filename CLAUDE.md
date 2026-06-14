# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JetBrains IDE plugin ("Forgejo Integration") that surfaces [Forgejo](https://forgejo.org) data inside IntelliJ-based IDEs. Built from the IntelliJ Platform Plugin Template (Kotlin + IntelliJ Platform Gradle Plugin 2.x), targeting IntelliJ IDEA Ultimate **2026.1.x**. **JDK 21 is required.** The IntelliJ platform version (`build.gradle.kts` `intellijIdea(...)`) and the Kotlin Gradle plugin version (`settings.gradle.kts`) are coupled: a newer IDE is compiled with a newer Kotlin and won't read older Kotlin metadata, so bump `org.jetbrains.kotlin.jvm` together with the platform (2026.1 needs Kotlin 2.3.x).

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

## Running & debugging the sandbox

`runIde` writes to `.intellijPlatform/sandbox/intellij-platform-forgejo-plugin/<IDE>/log/idea.log`.

**When the user is interactively testing the running IDE, keep a live monitor on that `idea.log` for the whole session** (e.g. `tail -F <log> | grep -E ' ERROR |octris\.forgejo'`). Don't make the user copy/paste logs — watch them yourself so you can diagnose a hang or failure the moment it happens. Two startup stack traces (`WorkspaceModelCacheSerializer` metadata, `jcef_cache/SingletonCookie`) are benign sandbox noise; ignore them.

EDT/modality gotcha (already bit us once): the Settings dialog is **modal**. Background work that updates settings UI must post back with the dialog's modality state — `invokeLater({ ... }, ModalityState.stateForComponent(component))`, not a bare `invokeLater { ... }` — or the update is deferred until the dialog closes (symptom: a "Checking..." label that never resolves).

## Platform API status — VERIFY before claiming an API is off-limits

Never state that a platform API is "internal", forbidden, or unusable without checking its annotation first. **"Undocumented" / "first-party" / "what GitHub/GitLab use internally" does NOT mean `@ApiStatus.Internal`.** Conflating them once nearly derailed this project.

Check the actual annotation on the class:

```
javap -v -classpath <platform-jar> <fully.qualified.ClassName> | grep -i 'ApiStatus\|IntellijInternalApi'
```

- `@ApiStatus.Internal` / `@IntellijInternalApi` → **off-limits** for third-party plugins (Plugin Verifier failure + Marketplace rejection). Don't use.
- `@ApiStatus.Experimental` → **allowed**, may change between releases; Verifier passes (reports "N usages of experimental API"). We already ship these (`VcsCommitExternalStatusProvider`).
- No `@ApiStatus` annotation → **usable** (e.g. `com.intellij.collaboration.auth.*`). Undocumented, so no stability guarantee across IDE versions — pin the platform version and branch per major version if needed — but not forbidden.

When unsure, run the `javap` check and report what it actually says; don't guess.

## Architecture

**Naming is uniform: `octris.forgejo`** is the Gradle `group`, the `plugin.xml` `<id>`, and the Kotlin source package root (`src/main/kotlin/octris/forgejo/`). Do not introduce a reverse-domain variant — `is.octr.forgejo` was rejected because `is` is a Kotlin keyword. The plugin descriptor is `src/main/resources/META-INF/plugin.xml`.

Feature code is layered:
- `settings/` — Forgejo **account management** on the platform **collaboration auth framework** (`com.intellij.collaboration.auth.*`, the same one GitHub/GitLab use; verified not `@ApiStatus.Internal`). `ForgejoAccountManager` (app `@Service`) extends `AccountManagerBase`; `ForgejoAccountsRepository` (`AccountsRepository`, persisted) holds non-secret account data and a `PasswordSafeCredentialsRepository` holds tokens; `ForgejoDefaultAccountHolder` (project `@Service`) extends `PersistentDefaultAccountHolder` for the per-project default. The UI (`ForgejoSettingsConfigurable`, a **project** `BoundConfigurable` at **Settings | Version Control | Forgejo Integration**) is built with `AccountsPanelFactory` + `ForgejoAccountsListModel` / `ForgejoAccountsDetailsProvider` (loads login + avatar) / `ForgejoAccountsPanelActionsController` (the add-account dialog). **Gotcha:** a concrete `PersistentDefaultAccountHolder` subclass needs its own `@State(name=…, storages=[Storage(StoragePathMacros.WORKSPACE_FILE)])`, or the component store throws `configurationSchemaKey must be specified`.
- `api/` — `ForgejoApiClient` (REST client over `java.net.http` + Gson): `getCurrentUser` (validates the token, resolves the login) and `getCombinedCommitState`. Plus `ForgejoCommitState` (enum, `fromState()` maps Forgejo's `state`). All client calls block and must run off the EDT.
- `vcs/` — the commit-status column, built on the platform's **external-status framework** (the same one the bundled GitHub plugin uses, so rendering/caching/loading/repaint are native and look identical):
  - `ForgejoCommitStatusProvider` extends `VcsCommitExternalStatusProvider.WithColumn`, registered via the `com.intellij.vcsLogCommitStatusProvider` EP. `isColumnAvailable` gates on at least one configured account; the loader picks the account whose host matches each repo's Git remote (preferring the project default).
  - `ForgejoCommitStatusColumnService` (app `@Service` with injected `CoroutineScope`) extends `VcsLogExternalStatusColumnService` — the base handles per-visible-row scheduling, caching, and repaint.
  - `ForgejoCommitStatusLoader` (`VcsCommitsDataLoader`) batch-loads visible commits off the EDT and reports back via the callback.
  - `ForgejoCommitStatusPresentation` maps a state to a `CIBuildStatusIcons` icon (icon-only, like GitHub).
  - `ForgejoCommitStatus` is the sealed `VcsCommitExternalStatus` (`NotLoaded` / `Loaded(state)`).
  - `ForgejoRepoResolver` maps a VCS root's Git remote to a Forgejo `owner/repo` by matching the configured host.

Do NOT reintroduce a hand-rolled `VcsLogCustomColumn` + custom cache/repaint — the framework does all of that and matches the GitHub plugin. When overriding the framework's Kotlin members, note `id`/`columnName`/`isColumnEnabledByDefault`/`scope`/`icon`/`text` are `val`s, while `getExternalStatusColumnService()`/`getStubStatus()`/`getDataLoader()`/`getPresentation()` are `fun`s.

### Gotcha: bundled modules/plugins on the compile classpath

The external-status framework (`VcsCommitExternalStatusProvider`, `VcsLogExternalStatusColumnService`, `GraphTableModel`, …) lives in `intellij.platform.vcs.log.impl`, the CI icons in `intellij.platform.collaborationTools`, and Git-remote resolution needs Git4Idea — none are on the compile classpath by default. `build.gradle.kts` adds `bundledModule("intellij.platform.vcs.log.impl")`, `bundledModule("intellij.platform.collaborationTools")`, and `bundledPlugin("Git4Idea")`; `plugin.xml` `<depends>` on `com.intellij.modules.vcs` and `Git4Idea`.

### Template scaffold (to be removed)

`services/MyProjectService`, `startup/MyProjectActivity`, `toolWindow/MyToolWindowFactory`, `MyBundle`, and `MyPluginTest` are leftover sample code from the template, intentionally kept as reference. Remove them (and their `plugin.xml` registrations) as real features replace them. Plugin-owned strings go in `ForgejoBundle` (`messages/ForgejoBundle.properties`), kept separate from the sample `MyBundle` so it survives that cleanup.

## Forgejo API

REST API reference (Swagger): **https://git.octr.is/api/swagger**

Combined commit status (what the VCS column needs):
`GET {server}/api/v1/repos/{owner}/{repo}/commits/{sha}/status` with header `Authorization: token <personal-access-token>`. The `state` field (`success` | `failure` | `pending` | `error`) maps via `ForgejoCommitState.fromState()`. Per-workflow run progress is under `/repos/{owner}/{repo}/actions/...`.

## Git remotes & CI

Two remotes: `origin` → GitHub (`github.com:octris/...`, lowercase) and `forgejo` → `ssh://git@git.octr.is:42042/Octris/...` (note the capital `Octris`). Use `fj` (Forgejo CLI, `--host git.octr.is`) for Forgejo PRs/issues/releases; standard `gh`/git for GitHub.

CI: `.github/workflows/build.yml` builds, tests, verifies, and drafts releases. It triggers on **push to `main`** and on pull requests. **GitHub Actions is the source of truth for CI.** Forgejo Actions also picks up `.github/workflows/`, but its run fails fast (GitHub-specific actions / runner labels) and **we do not care whether the Forgejo Actions run succeeds for now** — don't spend effort fixing it unless asked.
