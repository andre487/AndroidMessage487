# Message487 development server

Run from the repository root with Docker Compose installed and a Docker engine running:

```sh
docker compose -f DevServer/compose.yaml up -d --wait
python3 DevServer/tests/smoke.py
```

Open the editor at <http://localhost:5678>. Local development login:

- Email: `developer@message487.test`
- Password: `Message487-Local-Only`

These are public test credentials. This configuration is for synthetic local development data.
The webhook endpoints do not require authentication. The published port is bound to host loopback.
The editor account and its bcrypt password hash are provisioned through n8n environment variables.

The image version, execution retention, and runtime settings live in [compose.yaml](compose.yaml).
The named volume stores the database and n8n-generated encryption key. Successes and failures are
visible in **Executions**, including submitted event bodies. The `error` workflow deliberately
returns an HTTP error; its n8n execution itself may still be marked successful.

## Android connection

The standard Android Emulator reaches the host through `10.0.2.2`. The debug app starts with the
receive endpoint configured. Its **Save and send test event** button submits synthetic data and
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
The SMS event includes `sender`; the notification includes `title`. Repeat the notification command
with identical content: it should create no new event. Change its text: a new event should appear.
A long SMS exceeding one segment should arrive as one event with its complete text.

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

Bootstrap runs once per data volume. Restarts preserve editor changes. To explicitly replace the
bundled workflows with the checked-in versions, stop n8n before importing:

```sh
docker compose -f DevServer/compose.yaml stop n8n
docker compose -f DevServer/compose.yaml run --rm --no-deps --entrypoint /bin/sh n8n /bootstrap/import.sh
docker compose -f DevServer/compose.yaml up -d --wait
```

This overwrites the four fixture workflow IDs; other workflows remain. To discard **all** local
workflows, execution history and settings, delete this Compose project's volume explicitly:

```sh
docker compose -f DevServer/compose.yaml down -v
```

## References

- [n8n Server CLI](https://docs.n8n.io/deploy/host-n8n/configure-n8n/use-the-command-line)
- [Owner provisioning](https://docs.n8n.io/deploy/host-n8n/configure-n8n/manage-settings-using-environment-variables)
- [Emulator networking](https://developer.android.com/studio/run/emulator-networking-address)
