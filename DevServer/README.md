# Message487 development server

Run from the repository root with Docker Compose, Python 3 and Ruby/Bundler installed,
and a Docker engine running:

```sh
bundle install
docker compose -f DevServer/compose.yaml up -d --wait
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
PYTHON=.venv/bin/python bundle exec fastlane android server_tests
```

Open the editor at <http://localhost:5678>. Local development login:

- Email: `developer@message487.test`
- Password: `Message487-Local-Only`

These are public test credentials. This configuration is for synthetic local development data.
All webhook endpoints require `Authorization: Bearer <token>`. The public local token is
the `data.value` in [header-auth.json](credentials/header-auth.json), without the `Bearer ` prefix. The published port is bound to host loopback.
The editor account and its bcrypt password hash are provisioned through n8n environment variables.

The image version, execution retention, and runtime settings live in [compose.yaml](compose.yaml).
The named volume stores the database and n8n-generated encryption key. Successes and failures are
visible in **Executions**, including submitted event bodies. The `error` workflow deliberately
returns an HTTP error; its n8n execution itself may still be marked successful.

## Portable workflow examples

The [five example levels](../docs/en/n8n-examples.md) ([на русском](../docs/ru/n8n-examples.md))
cover text/image webhooks without Telegram, basic Telegram, built-in image forwarding and the
advanced Rich Message workflow. Import the JSON from [examples/n8n/workflows](../examples/n8n/workflows),
not a configured export from a personal server. Credentials/chat/table IDs are intentionally absent.
The four workflows in this DevServer folder remain local fault-testing fixtures, not production templates.

DevServer also publishes the first two portable examples at `/webhook/message487/01-webhook-text`
and `/webhook/message487/02-webhook-images`, using the same local test token as `receive`.
The image receiver exposes decoded binary files in its execution output without any Telegram dependency.
Telegram templates are not automatically configured or published by DevServer.

## Android connection

The standard Android Emulator reaches the host through `10.0.2.2`. The debug app starts with the
receive endpoint configured. Enter the local token in **Webhook token** before saving. Its **Save and send test event** button submits synthetic data and
shows the event ID and confirmation result in **Journal**. Find the same ID in the workflow's execution input.
In the Webhook output, `body.device_id` is the installation UUID and `body.device_code` is the
editable device label. Both are preserved in execution history; older events may lack the label.
`body.source` contains the package identifier and `body.source_name` the application's display name.
Use **n8n confirmation** for this server; generic webhook mode only checks the HTTP status.

| Endpoint suffix under `/webhook/message487/` | Expected result |
| --- | --- |
| `receive` | Accepted event ID, or HTTP 400 for invalid input |
| `error` | HTTP 500 |
| `slow` | Response delayed beyond the client's timeout |
| `invalid-ack` | HTTP 200 with a mismatched event ID |

These are published webhooks, so **Listen for test event** is unnecessary. They confirm a test
execution only; there is no durable delivery queue, deduplication, or Telegram integration here.
The JSON fixtures define the accepted event contract. The Android client keeps a persistent
retry queue; the server does not deduplicate repeated requests.

## Capture checks on an emulator

Use synthetic data only. In the app, save the receive connection, enable SMS and notifications
in **Sources**, grant the requested permissions, and add `com.android.shell` to selected packages.
Then generate real Android events:

```sh
adb emu sms send +15551234567 'Message487 synthetic SMS'
adb shell 'cmd notification post -t "Message487 test" message487-test "Synthetic notification"'
```

Open **Journal** and match each accepted event ID with n8n **Executions → Webhook → Output → body**.
The SMS event includes `sender`; the notification includes `title`. With the notification duplicate
filter enabled, identical text and images from the same package are skipped only within the
configured timestamp window. Repeat within that window to check suppression; outside it, a new
event is expected. Changing only the title does not bypass the filter. A long multipart SMS
should arrive as one event with its complete text.

For recovery testing, stop this Compose server, generate an event, and check that it remains queued
or waiting for retry. Restart the server and wait for Android background scheduling. The same event
ID should become accepted. The app must retain the event across process restarts. **Retry now** can
request another attempt after a transient failure; pause prevents delivery until resumed.

The `error` endpoint exercises automatic retries. `invalid-ack` requires a manual retry; changing
the saved URL will not redirect an already queued event. Remove unwanted test records in the journal.
Disable sources or pause before generating events that should not be forwarded, and verify there
is no new journal record. Keep your SMS app unselected to avoid forwarding both its notification
and the SMS broadcast during this check.

## Lifecycle

```sh
docker compose -f DevServer/compose.yaml logs -f n8n
docker compose -f DevServer/compose.yaml stop
docker compose -f DevServer/compose.yaml up -d --wait
```

Bootstrap imports the four fault-testing fixtures, two portable webhook examples and their shared
credential. It runs when the marker defined in [start.sh](start.sh) is absent; changing that marker
reimports the bundled workflows on an existing volume. Other restarts preserve editor changes.
To explicitly refresh the bundled workflows, stop n8n before importing:

```sh
docker compose -f DevServer/compose.yaml stop n8n
docker compose -f DevServer/compose.yaml run --rm --no-deps --entrypoint /bin/sh n8n /bootstrap/import.sh
docker compose -f DevServer/compose.yaml up -d --wait
```

This overwrites the six bundled workflow IDs and shared credential; other workflows remain.
To discard **all** local workflows, execution history and settings, delete this Compose project's
volume explicitly:

```sh
docker compose -f DevServer/compose.yaml down -v
```

## References

- [n8n Server CLI](https://docs.n8n.io/deploy/host-n8n/configure-n8n/use-the-command-line)
- [Owner provisioning](https://docs.n8n.io/deploy/host-n8n/configure-n8n/manage-settings-using-environment-variables)
- [Emulator networking](https://developer.android.com/studio/run/emulator-networking-address)

## Images and Telegram

`receive` retains optional `body.images` as JSON in execution history; it does not convert images
to binary data or forward them. The portable image receiver performs that conversion. Use the
[image contract](../docs/en/n8n-examples.md#android-image-features-and-contract) and
[importable workflows](../docs/en/n8n-examples.md) for field definitions, limits, text-only paths
and routing; do not acknowledge an event before all required processing succeeds.
Use synthetic images: execution history contains complete Base64 data. Reverse proxies must
accept chunked JSON uploads and sufficient body size/time; Base64 adds about one third.

For custom Rich Message workflows, [telegram-images.js](telegram-images.js) runs after text
formatting and before retry keys. It attaches media to the first text part; later parts do not
repeat them. Configure **Send Rich Message** with `{{ $json.rich_message }}` and Binary Fields
`{{ Object.keys($binary).join(",") }}`. Preserve binary data through progress-key lookups and restore
the pending item. See [level 5 setup and routing](../docs/en/n8n-examples.md#level-5-rich-messages-and-retry-progress)
for the extension, credentials, worker installation and Data Table requirements.

Remember part keys only after successful sends and ACK only after all parts finish. These keys
suppress retries of an event ID; Android's image hash instead detects matching capture content.
Neither delivery plus key persistence nor table lookup is atomic: crashes/concurrent requests can
still duplicate a send. Telegram's Rich Message limits differ from `sendMediaGroup` limits.
