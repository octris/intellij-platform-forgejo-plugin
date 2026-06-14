<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-platform-forgejo-plugin Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Settings page (**Tools | Forgejo Integration**) for the Forgejo server URL and a personal access token (stored in the IDE password safe).
- A "Forgejo CI" custom column in the VCS log that renders a per-commit status icon, backed by a non-blocking cached status service and a stubbed REST client.

### Changed
- Renamed plugin to "Forgejo Integration" with id/group `octris.forgejo` and source package `octris.forgejo`.
