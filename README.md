# Message487

Message487 is an open-source Android app being developed to connect selected notifications and
SMS to your n8n workflows. A custom webhook will also be supported for other integrations.

**Status:** development preview. The client saves a webhook connection and sends synthetic test
events. Notification/SMS capture, persistent delivery queues, automatic retries and webhook
authentication are not implemented yet.

The intended setup starts with n8n: connect a workflow, select event sources, grant the required
permissions, and send a test event. You choose which events leave your phone and where they go.
Telegram forwarding is one possible workflow; the Android app does not depend on Telegram.

## Local development

Start the [development server](DevServer/README.md), which provisions n8n and published test workflows:

```sh
docker compose -f DevServer/compose.yaml up -d --wait
python3 DevServer/tests/smoke.py
```

Build with JDK 21, Ruby/Bundler and the Android SDK. Use `ANDROID_HOME` or an untracked
`local.properties` file to point Gradle to your SDK. SDK and library versions are in the Gradle
build files; Ruby dependencies are pinned by `Gemfile.lock`.

```sh
bundle install
bundle exec fastlane android checks
scripts/emulator.sh
```

Once the emulator has booted, use another terminal:

```sh
bundle exec fastlane android install
adb shell am start -n life.andre.message487/.MainActivity
```

The debug app is preconfigured for the local n8n receive endpoint. Press **Save and send test event**,
then find the displayed event ID in n8n **Executions**. Strict confirmation requires JSON with
`status: "accepted"` and the matching `event_id`; this is our fixture contract, not a built-in n8n
response. Turn off **n8n confirmation** for a generic webhook that acknowledges with HTTP 2xx.

Fastlane's `debug_artifact` lane builds only the debug APK. `checks` runs JVM tests, debug/release
lint, and builds debug and unsigned release APKs under `app/build/outputs/apk/`. PR CI has no
release signing credentials and does not require an emulator.

UI strings are supplied in English and Russian. Connection settings survive app restarts;
recent test results remain in memory only.

`device_id` identifies the installation; `device_code` is an editable label sent alongside it.
Changing the device code preserves the installation ID. A timeout does not prove the server
failed to receive the event. See [PRIVACY.md](PRIVACY.md) for the current data handling.

`source` contains the source package name and `source_name` its display name from Android's
PackageManager. Test events use Message487 itself. If a label cannot be resolved, the package
name is used as the display name. Labels may change with the app version or device language;
use `source` for matching rules.

The manifest uses `<queries>` for apps with launcher activities, in addition to packages Android
makes visible automatically. Sources outside this visibility scope fall back to their package
name. No application inventory is collected or sent, and `QUERY_ALL_PACKAGES` is not requested.
See [Android package visibility](https://developer.android.com/training/package-visibility/declaring).

See the [project context](docs/project-context.md) for the product direction and remaining decisions.

This project succeeds [sms487](https://github.com/andre487/sms487).
[AndroidMegaProxy](https://github.com/andre487/AndroidMegaProxy) is the reference for project
conventions, UI, documentation, build tooling, and CI.

Licensed under the [MIT License](LICENSE).
