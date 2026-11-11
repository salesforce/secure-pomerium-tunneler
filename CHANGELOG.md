<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Secure Gateway Tunneler Plugin Changelog

## [Unreleased]
### Fixed
- Keep the tunnel and Gateway connection alive when the client closes for a restart (e.g. plugin update), so the client can reconnect
- Relaunch the client when it stops reconnecting after a backend restart (e.g. Xmx change) instead of leaving it on "Connecting" forever
- Log tunnel connections and Pomerium CONNECT results

### Changed
- Upgraded IntelliJ Platform to 2026.2.4 and IntelliJ Platform Gradle Plugin to 2.19.0
- Client link keeps the scheme of the connection key and drops `fp` from `tcp-direct://` links (2026.2.4+), so they stay valid across restarts; `tcp://` links are unchanged

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
