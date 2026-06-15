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
- `api/` — `ForgejoApiClient` (REST client over `java.net.http` + Gson): `getUser` (validates the token, resolves login + avatar), `getCommitStatus` (combined commit state + run/job link), `listRunsPage` (paginated run list) and `listTasksPage` (paginated jobs; the tab groups them per run and maps via the `ForgejoTaskRaw.toJob()` extension). Plus `ForgejoCommitState` / `ForgejoActionStatus` enums (`fromState()` / `fromString()`). All client calls block and must run off the EDT.
- `vcs/` — the commit-status column, built on the platform's **external-status framework** (the same one the bundled GitHub plugin uses, so rendering/caching/loading/repaint are native and look identical):
  - `ForgejoCommitStatusProvider` extends `VcsCommitExternalStatusProvider.WithColumn`, registered via the `com.intellij.vcsLogCommitStatusProvider` EP. `isColumnAvailable` gates on at least one configured account; the loader picks the account whose host matches each repo's Git remote (preferring the project default).
  - `ForgejoCommitStatusColumnService` (app `@Service` with injected `CoroutineScope`) extends `VcsLogExternalStatusColumnService` — the base handles per-visible-row scheduling, caching, and repaint.
  - `ForgejoCommitStatusLoader` (`VcsCommitsDataLoader`) batch-loads visible commits off the EDT and reports back via the callback.
  - `ForgejoCommitStatusPresentation` maps a state to a `CIBuildStatusIcons` icon (icon-only, like GitHub); its `onClick` opens the **Forgejo CI** tool window focused on that commit's run (via `ForgejoActionsCoordinator`), **not** the browser.
  - `ForgejoCommitStatus` is the sealed `VcsCommitExternalStatus` (`NotLoaded` / `Loaded(state, url, commitSha, context)` — the sha + repo context let the click reveal the run).
  - `ForgejoRepoResolver` maps a VCS root's Git remote to a Forgejo `owner/repo` by matching the configured host.

Do NOT reintroduce a hand-rolled `VcsLogCustomColumn` + custom cache/repaint — the framework does all of that and matches the GitHub plugin. When overriding the framework's Kotlin members, note `id`/`columnName`/`isColumnEnabledByDefault`/`scope`/`icon`/`text` are `val`s, while `getExternalStatusColumnService()`/`getStubStatus()`/`getDataLoader()`/`getPresentation()` are `fun`s.

### Gotcha: bundled modules/plugins on the compile classpath

The external-status framework (`VcsCommitExternalStatusProvider`, `VcsLogExternalStatusColumnService`, `GraphTableModel`, …) lives in `intellij.platform.vcs.log.impl`, the CI icons in `intellij.platform.collaborationTools`, and Git-remote resolution needs Git4Idea — none are on the compile classpath by default. `build.gradle.kts` adds `bundledModule("intellij.platform.vcs.log.impl")`, `bundledModule("intellij.platform.collaborationTools")`, and `bundledPlugin("Git4Idea")`; `plugin.xml` `<depends>` on `com.intellij.modules.vcs` and `Git4Idea`. (The collaboration **auth** classes are in `collaborationTools.jar` — no separate auth bundled-module needed.)

### CI tool window + multi-remote (`actions/` package)

The **"Forgejo CI" tool window** (`ForgejoActionsToolWindowFactory` + `ForgejoActionsPanel`) is a runs→jobs tree with a repo selector, auto-refresh + live-ticking elapsed time while runs are in progress. `ForgejoRepoResolver.allContexts`/`contextsFor` resolve **all** matching Forgejo remotes (default account first); the VCS-log column tries each and shows whichever has a status.

- **Run list** is paginated **by run** via `/actions/runs` (`listRunsPage`), growing as you scroll (infinite scroll + viewport auto-fill). Do NOT paginate the run list off `/actions/tasks` — for matrix builds one 50-task page can be a single run (see Forgejo API below).
- **Jobs are lazy-loaded on expand**: there's no per-run jobs endpoint and `/actions/tasks` isn't filterable by run, so expanding a run pages the bulk task feed down to that run number and caches it (guarded by a lock). Recent runs are instant; an old run pages deeper once, then is cached. Re-run attempts collapse to the latest task (max `id`) with an attempt-count hint.
- `ForgejoActionsCoordinator` (project `@Service`) bridges the VCS-log column → tool window: a column click stores a pending target, activates the tool window, and the panel reveals (paging to it if needed) that commit's run.

Gotchas:
- **Invisible-root trees** (`isRootVisible=false`): a model reset (`treeModel.nodeStructureChanged(root)` / `reload()`) **collapses the root and hides every child**, so the tree looks empty though the model is full. Re-expand the root (`tree.expandPath(TreePath(root))`) after populating. (This bit us: the tab showed "No workflow runs" until a VCS-log click's `scrollPathToVisible` happened to expand the root.)
- **Startup race**: the tab is created during startup restore *before* Git4Idea registers repos, so `ForgejoRepoResolver` finds nothing → subscribe to `VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED` (a `dvcs` API, verified Compatible/not internal) and re-resolve (also covers remotes added/removed later); otherwise it's stuck on "no account matches".
- **Guard stale loads by value, not identity**: match an in-flight load to the selected repo by `owner`/`repo`/`server` strings, NOT `ForgejoRepoContext`/`ForgejoAccount` object equality — the mapping events rebuild the combo with fresh account instances, so an identity check silently drops the result (→ empty tab).
- A **failed** run-list fetch retries (a transient error at startup) instead of silently showing "No workflow runs".
- `AnimatedIcon` does NOT animate in the VCS-log column (the log table doesn't repaint continuously) — use a static icon there (`CIBuildStatusIcons.inProgress`). Animated spinners only work where you drive repaint, e.g. the tool-window tree + a repaint `Alarm`.
- Tree nodes keep a **stable `toString()`** (id-based: `run:<index>` / `job:<id>`) so the tab updates run rows / appends pages **in place** (no full rebuild), preserving expansion and loaded jobs.
- `VcsCommitExternalStatusPresentation.Clickable.onClick(e: InputEvent?)` takes a **nullable** `InputEvent`.

### Template scaffold (to be removed)

`services/MyProjectService`, `startup/MyProjectActivity`, `toolWindow/MyToolWindowFactory`, `MyBundle`, and `MyPluginTest` are leftover sample code from the template, intentionally kept as reference. Remove them (and their `plugin.xml` registrations) as real features replace them. Plugin-owned strings go in `ForgejoBundle` (`messages/ForgejoBundle.properties`), kept separate from the sample `MyBundle` so it survives that cleanup.

## Forgejo API

REST API reference (Swagger): **https://git.octr.is/api/swagger**

Combined commit status (what the VCS column needs):
`GET {server}/api/v1/repos/{owner}/{repo}/commits/{sha}/status` with header `Authorization: token <personal-access-token>`. The `state` field (`success` | `failure` | `pending` | `error`) maps via `ForgejoCommitState.fromState()`. The status `target_url` is relative — resolve against the server.

**Actions API (verified on the instance):** both `/actions/runs` and `/actions/tasks` take `?limit=&page=` and return `{ total_count, workflow_runs[] }`.
- **Run list:** `/actions/runs` — one object per run (~12–46 KB: it embeds the event payload + repository). Fields the tab uses: `index_in_repo` (the **run number** — NOT `id`), `status` (`success`/`failure`/`cancelled`, plus `running`/`waiting` while in progress), `prettyref` (branch), `commit_sha`, `title`, `workflow_id`, `started`/`stopped`, and `duration` (**nanoseconds** — divide by 1e6 for ms). `total_count` is the run count → paginate the run list here.
- **Tasks (jobs):** `/actions/tasks` — one object per job (~3 KB, newest-first, has `run_number`/`name`/`status`/timing). It is **not filterable by run** (`?run=` is ignored) and there is **no `/runs/{id}/jobs` endpoint** (404). Lighter *per task*, but for matrix builds (~30 jobs/run) a 50-task page is ~1 run — so it's wrong for listing runs; use it only to lazily resolve a known run's jobs (page until `run_number` < target, then filter).
- **Re-run attempts:** a re-run is just another task with the same `name` (no attempt field) — collapse to the latest (max `id`) and surface an attempt count.
- No steps/logs/artifacts/rerun/cancel API yet (logs API expected ~mid-2026); only `workflows/{file}/dispatches` (trigger) is writable.

## Git remotes & CI

**Never `git add -A` blindly** — review the staged file list before committing (a stray `.env`/secret can slip in). `.env*` is gitignored.

Two remotes: `origin` → GitHub (`github.com:octris/...`, lowercase) and `forgejo` → `ssh://git@git.octr.is:42042/Octris/...` (note the capital `Octris`). Use `fj` (Forgejo CLI, `--host git.octr.is`) for Forgejo PRs/issues/releases; standard `gh`/git for GitHub.

CI: `.github/workflows/build.yml` builds, tests, verifies, and drafts releases. It triggers on **push to `main`** and on pull requests. **GitHub Actions is the source of truth for CI.** Forgejo Actions also picks up `.github/workflows/`, but its run fails fast (GitHub-specific actions / runner labels) and **we do not care whether the Forgejo Actions run succeeds for now** — don't spend effort fixing it unless asked.
