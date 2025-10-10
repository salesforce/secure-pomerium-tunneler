<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Pomerium Tunneler Library Changelog

## [Unreleased]

## [1.0.2]
### Fixed
- Fixed file descriptor exhaustion in PomeriumTunneler with shared SelectorManager
- Fixed thread leaks with proper coroutine lifecycle management
- Fixed Netty-based FD exhaustion in PomeriumAuthProvider with timeout cleanup
- Improved resource cleanup to prevent "Too many open files" exceptions


## [1.0.1]
### Added
- Initial release of Pomerium tunneler library
- Support for Pomerium authentication and tunneling
- HTTP client integration with Ktor
- Test fixtures for mocking Pomerium services
