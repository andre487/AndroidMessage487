# Message487 project conventions

- Maintain English and Russian user guides for installation, receiver setup, everyday use and troubleshooting. Write build, CI, release and implementation documentation in English only. Link to the English reference from user guides when needed.
- Keep release notes and store listings bilingual. Distribution tools read the listing from `fastlane/metadata/android`.
- Use JDK 21 and the Gradle wrapper. Keep SDK and JDK paths independent of user, OS and host architecture.
- Run project tasks with `uv run manage.py` locally and `uv run --locked manage.py` in CI. Update `pyproject.toml` and `uv.lock` together when dependencies change.
- Keep signing credentials and VirusTotal submissions in the release workflow. PR checks build debug and unsigned release APKs.
- Exercise deterministic behavior in JVM tests. Run instrumentation only on the explicitly selected disposable test AVD because the tests clear app data. Use synthetic messages and credentials for delivery tests.
