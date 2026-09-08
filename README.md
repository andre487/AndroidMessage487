# Message487

Message487 connects selected Android notifications and incoming SMS to your n8n workflows.
A custom webhook is also supported. Telegram forwarding is one possible workflow; the Android
app does not depend on Telegram.

**Status:** development preview with notification/SMS capture, a persistent encrypted outbox,
background delivery, automatic retries and a delivery journal. Webhook authentication and
production release signing are not implemented yet.

## Getting started

1. Save the full published webhook URL and a device code in **Connection**. Send a test event.
2. Check **Journal** and find the same event ID in n8n **Executions**.
3. In **Sources**, enable notification forwarding, grant notification access in Android settings,
   and select applications. Add a package manually if it has no launcher icon.
   **Select all applications** selects the entire available list, regardless of the search filter;
   manually added selections are preserved. Newly installed apps must be selected separately.
4. Enable SMS forwarding separately and grant SMS permission. Only new incoming SMS are read;
   existing history is not imported. Multipart SMS are combined into one event.

Both capture sources are off by default, and no applications are selected. Message487 excludes
its own notifications, ongoing notifications and group summaries. An unchanged update of the
same notification is suppressed; changed content creates another event. Existing notifications
are not replayed when access is enabled. Avoid selecting applications that receive the forwarded
messages, or the workflow can create a feedback loop. Selecting your SMS app as a notification
source may forward the same message as both SMS and a notification.

**Pause forwarding** stops capture and queued delivery. A request already running may finish.
Events arriving while capture is disabled or paused are not saved for later. Background delivery
uses Android WorkManager: Doze, battery restrictions and network availability can delay it.
After a force-stop, open the app again. Android may hide sensitive notification content; Message487
forwards only the content the system exposes.

## Delivery and data

The n8n confirmation mode requires JSON with `status: "accepted"` and the matching `event_id`.
This is our fixture contract, not a built-in n8n response. Turn off **n8n confirmation** for a
custom webhook that acknowledges with HTTP 2xx. Neither response proves delivery to a downstream
service such as Telegram.

Events are saved locally before transmission. Retries preserve the event ID and body. Timeouts,
connection failures and transient HTTP errors retry with backoff; other HTTP failures and invalid
confirmations need a manual retry from **Journal** after fixing the server. A lost response can
cause duplicate delivery: downstream workflows should deduplicate using `event_id`.

Queued events retain the URL and confirmation mode from capture time. Changing the connection
does not reroute them. Undelivered events remain until confirmed or explicitly deleted. The
journal persists across restarts and hides message contents. Tap an event for its ID, HTTP result,
retry and deletion controls. Confirmed payloads are removed;
only a bounded recent history of delivery metadata remains. See [PRIVACY.md](PRIVACY.md).

The JSON event carries `schema_version`, `event_id`, `device_id`, `device_code`, `message_type`,
`occurred_at`, `source`, `source_name` and `text`. Notifications also carry `title`; SMS carry
`sender`. `device_id` is a random installation identity; `device_code` is your editable label.
`source` is the originating package, with SMS using `android` for the system SMS broadcast.
`source_name` is its PackageManager display label, falling back to the package when unavailable.
Labels can change with language or app version; use `source` for matching rules.

The manifest declares launcher-app visibility rather than `QUERY_ALL_PACKAGES`. The app requests
notification listener access and `RECEIVE_SMS` only for the features you enable. It does not request
SMS history access or become the default SMS app.

## Local development

Start the [development server](DevServer/README.md), which provisions n8n and published test workflows:

```sh
docker compose -f DevServer/compose.yaml up -d --wait
python3 DevServer/tests/smoke.py
```

Build with JDK 21, Ruby/Bundler and the Android SDK; the emulator launcher also uses Python 3.
Use `ANDROID_HOME` or an untracked
`local.properties` file to point Gradle to your SDK. SDK and library versions are in the Gradle
build files; Ruby dependencies are pinned by `Gemfile.lock`.

```sh
bundle install
bundle exec fastlane android checks
scripts/run-without-debugging.sh
```

The run script starts the development emulator if necessary, waits for Android, builds and installs
the debug APK, then launches the app without waiting for a debugger. Existing app data is preserved.
In VS Code, select **Run Message487 on Emulator** and **Run Without Debugging**, or run the task
**Android: Run on emulator**. To start only the emulator, use `scripts/emulator.sh`.

The debug app starts with the local n8n receive endpoint configured. Follow the capture checks in
[DevServer/README.md](DevServer/README.md) using synthetic data only. Release builds require HTTPS.
UI strings are supplied in English and Russian. The interface supports light/dark themes,
bottom navigation on phones and rail navigation on wider windows. See the [design notes](docs/design.md)
for the visual conventions and references.

Fastlane's `debug_artifact` lane builds only the debug APK. `checks` runs JVM/Robolectric tests,
debug/release lint, and builds debug and unsigned release APKs under `app/build/outputs/apk/`.
PR CI has no release signing credentials and does not require an emulator.

See the [project context](docs/project-context.md) for remaining product decisions.
This project succeeds [sms487](https://github.com/andre487/sms487).
[AndroidMegaProxy](https://github.com/andre487/AndroidMegaProxy) is the reference for project conventions.

Licensed under the [MIT License](LICENSE).
