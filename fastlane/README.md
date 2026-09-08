fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android checks

```sh
[bundle exec] fastlane android checks
```

Run JVM tests, Android lint, and build debug and unsigned release APKs

### android python_checks

```sh
[bundle exec] fastlane android python_checks
```

Run Python tests and formatting checks for the development server

### android server_tests

```sh
[bundle exec] fastlane android server_tests
```

Exercise the running development n8n server

### android release_artifacts

```sh
[bundle exec] fastlane android release_artifacts
```

Build, sign and verify the release APK and checksums

### android debug_artifact

```sh
[bundle exec] fastlane android debug_artifact
```

Build a debug APK

### android install

```sh
[bundle exec] fastlane android install
```

Install the debug APK on the connected emulator or device

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
