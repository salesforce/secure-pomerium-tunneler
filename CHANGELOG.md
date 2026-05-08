<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Secure Gateway Tunneler Plugin Changelog

## [Unreleased]

## [0.0.4]
### Changed
- Upgraded IntelliJ Platform Gradle Plugin to 2.16.0

### Fixed
- Removed workaround for product-info.json productModuleV2 null classPath (fixed in intellij-plugin-structure 3.325, bundled in plugin 2.16.0)

## [0.0.3]
### Changed
- Upgraded IntelliJ Platform to 2026.1 and updated dependencies

### Fixed
- Added compatibility fixes for Gateway 2026.1

## [0.0.2]
### Fixed
- Fixed file descriptor leak
- Improved resource cleanup and memory management

## [0.0.1]
### Added
- Initial release of Secure Gateway Tunneler plugin
- Integration with Pomerium for secure tunneling
- Gateway connection provider for IntelliJ
- Support for Pomerium authentication flows
