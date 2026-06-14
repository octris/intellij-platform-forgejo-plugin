# intellij-platform-forgejo-plugin

![Build](https://github.com/octris/intellij-platform-forgejo-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
**Forgejo Integration** brings [Forgejo](https://forgejo.org) into JetBrains IDEs, surfacing
information from your Forgejo instance directly inside the IDE so you can stay in your editor
instead of switching to the browser.

Planned and in-progress capabilities:

- Visualizing **Forgejo Actions** (CI) status and progress per commit in the VCS log.
- Managing multiple Forgejo accounts (server + token) with a per-project default.

This plugin is under active development.
<!-- Plugin description end -->

## Development

This project is built on the [IntelliJ Platform Plugin Template][template] and uses the
[IntelliJ Platform Gradle Plugin][gradle-plugin]. JDK 21 is required.

Common Gradle tasks (run via the wrapper, e.g. `./gradlew <task>`):

| Task                | Description                                                        |
|---------------------|--------------------------------------------------------------------|
| `runIde`            | Launches a sandbox IDE with the plugin installed.                  |
| `test`              | Runs the test suite.                                               |
| `buildPlugin`       | Builds the distributable plugin ZIP into `build/distributions/`.   |
| `verifyPlugin`      | Runs the IntelliJ Plugin Verifier against the configured IDEs.     |

Run configurations for **Run Plugin**, **Run Tests**, and **Run Verifications** are also
available in the IDE (see the `.run/` directory).

### Project layout

- Sources live under `src/main/kotlin/octris/forgejo/` (group / plugin id: `octris.forgejo`).
- The plugin descriptor is `src/main/resources/META-INF/plugin.xml`.
- Feature code:
  - `settings/` — Forgejo **account management** on the platform collaboration auth framework
    (`AccountManagerBase`, `PersistentDefaultAccountHolder`, `AccountsPanelFactory` — the same one
    the GitHub/GitLab plugins use). Accounts persist via `ForgejoAccountsRepository` (non-secret)
    and the IDE password safe (tokens); the panel is at **Settings | Version Control | Forgejo
    Integration**.
  - `api/` — `ForgejoApiClient` (REST client: token validation / user resolution + commit status)
    and the `ForgejoCommitState` model.
  - `vcs/` — the "Forgejo CI" VCS-log column, built on the platform's external-status framework
    (`VcsCommitExternalStatusProvider`, the same one the bundled GitHub plugin uses) so it renders
    natively. `ForgejoCommitStatusProvider` + `ForgejoCommitStatusColumnService` +
    `ForgejoCommitStatusLoader` + `ForgejoCommitStatusPresentation`, with `ForgejoRepoResolver`
    mapping a Git remote to a Forgejo owner/repo.
- The classes under `services/`, `startup/`, and `toolWindow/` are the template's sample
  scaffold, kept for now as reference. Remove them (and their `plugin.xml` registrations)
  as the real features land.

## Setup checklist

Completed:
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [x] Adjust the [group](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml), [name](./src/main/resources/META-INF/plugin.xml), and [sources package](./src/main/kotlin).
- [x] Adjust the plugin [description](./src/main/resources/META-INF/plugin.xml) (see [Tips][docs:plugin-description]) and this README to describe what the plugin does.

Still to do (require external accounts / repository secrets):
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "intellij-platform-forgejo-plugin"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/octris/intellij-platform-forgejo-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[gradle-plugin]: https://github.com/JetBrains/intellij-platform-gradle-plugin
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
