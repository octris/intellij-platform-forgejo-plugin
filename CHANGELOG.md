<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-platform-forgejo-plugin Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Forgejo **account management** (**Version Control | Forgejo Integration**) built on the platform collaboration auth framework — add/remove multiple accounts, set a per-project default, with avatars and token validation against `/user`. Non-secret account data persists via a `PersistentStateComponent`; tokens live in the IDE password safe.
- A "Forgejo CI" custom column in the VCS log that renders a per-commit status icon, backed by a non-blocking cached status service that repaints the log when results arrive.
- Forgejo REST client (`java.net.http` + Gson) for user resolution and combined commit status, plus Git remote → Forgejo owner/repo resolution (via Git4Idea).

### Changed
- Renamed plugin to "Forgejo Integration" with id/group `octris.forgejo` and source package `octris.forgejo`.
- Reworked the VCS-log CI column onto the platform's external-status framework (`VcsCommitExternalStatusProvider`, the same one the GitHub plugin uses) for native rendering and GitHub-style CI icons (`CIBuildStatusIcons`), replacing the hand-rolled column/renderer.
- Targeted IntelliJ IDEA 2026.1.x (Kotlin Gradle plugin 2.3.0).

### Fixed
- "Test connection" no longer hangs on "Checking…" (post UI updates with the modal dialog's `ModalityState`).
- Password-safe reads/writes moved off the EDT (were logging `SlowOperations` SEVERE).
