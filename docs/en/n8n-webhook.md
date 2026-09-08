# Connect Message487 to n8n

[English](../en/n8n-webhook.md) | [Русский](../ru/n8n-webhook.md)

This guide targets Message487 0.0.1. You need the n8n editor and an HTTPS endpoint
reachable from your phone. For a local Android emulator, use the debug build and
[DevServer](../../DevServer/README.md). Release APKs reject HTTP endpoints.

## Where to run n8n

- [Official n8n documentation](https://docs.n8n.io/) covers nodes, expressions and workflows.
- [Get started with n8n Cloud](https://docs.n8n.io/deploy/use-n8n-cloud/start-your-free-trial) to use a managed instance.
- [Cloud or self-hosting](https://docs.n8n.io/choose-how-to-use-n8n) compares deployment options.
- [Self-hosted installation options](https://docs.n8n.io/deploy/host-n8n/install-options).
- Official [Docker Compose installation guide](https://docs.n8n.io/deploy/host-n8n/install-options/install-using-docker-compose).

For Cloud, use your workspace's HTTPS Production URL. For self-hosting, configure
HTTPS, persistent storage and backups. DevServer is for local development; do not
expose it publicly with its bundled test credentials.

## 1. Create a receiving workflow

Import [receive.json](../../DevServer/workflows/receive.json) using **Import from File**
in the editor menu. It validates basic fields and acknowledges the incoming `event_id`.
Import a separate workflow rather than replacing an existing production workflow.

```mermaid
flowchart LR
    W[Webhook: POST] --> R[Respond to Webhook: JSON ACK]
```

To configure the same two nodes manually, set **Webhook** as follows:

| Field | Value |
| --- | --- |
| HTTP Method | `POST` |
| Path | A unique path such as `message487/receive-<random-string>` |
| Authentication | `None` for the current client version |
| Respond | `Using 'Respond to Webhook' Node` |

Generate a random suffix with `openssl rand -hex 16`; replace the entire placeholder,
including angle brackets. Message487 currently sends no authentication headers,
Basic Auth or JWT. The n8n editor login does not automatically protect webhooks.
Keep the full URL private; a random path is not a substitute for authentication.
For personal messages, restrict endpoint access where possible, for example using
a private network reachable from the phone. Enabling Header/Basic/JWT authentication
without client support will reject delivery.

Set **Respond to Webhook → Respond With → JSON**, **Response Code → 200**, and use
this **Expression** in **Response Body**:

```javascript
{{ { status: 'accepted', event_id: $('Webhook').first().json.body.event_id } }}
```

`Webhook` is the trigger node's name; adjust the expression if you rename it.
This minimal manual example does not validate requests. The imported `receive.json`
also validates `schema_version`, `event_id`, `message_type` and `text`, returning
HTTP 400 for invalid input.

The client expects an object, not an array, JSON-encoded string or HTML:

```json
{"status":"accepted","event_id":"the-same-event_id-as-the-request"}
```

The status must be exactly `accepted` and the ID must match. The default n8n response
“Workflow got started” is not an ACK for this client. See the official
[Webhook](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/) and
[Respond to Webhook](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.respondtowebhook/) documentation.

## 2. Publish and copy the URL

Select **Publish** (or enable **Active** in older n8n versions), then copy the
Webhook node's **Production URL**, for example:

```text
https://n8n.example.org/webhook/message487/receive-<random-string>
```

The `/webhook-test/` **Test URL** is for temporary listening with **Listen for test event**.
Production requests use a published workflow and appear under **Executions**.
Publish again after editing the workflow.

If a reverse proxy causes n8n to display an internal URL, configure `WEBHOOK_URL`,
`N8N_PROXY_HOPS` and forwarded headers using the official
[reverse-proxy guide](https://docs.n8n.io/deploy/host-n8n/configure-n8n/basic-configuration/configuration-examples/configure-webhook-urls-with-reverse-proxy).
Use the final HTTPS URL with a trusted certificate: Message487 does not follow
HTTP redirects or disable TLS verification.

## 3. Configure the app

1. Open **Connection** and paste the complete Production URL.
2. Set a recognizable **Device code**, such as `personal-phone`.
3. Keep **n8n confirmation** enabled.
4. Save and send a test event.
5. Open the event in **Journal** and check its acknowledgement and HTTP 200.

After the test succeeds, enable your desired **Sources**. Notifications require
Android notification access and selected apps; SMS requires receive-SMS permission.
Enabling SMS and selecting your SMS app can produce two events for one message.

The bundled emulator endpoint is `http://10.0.2.2:5678/webhook/message487/receive`,
which requires a debug build. On a physical phone, `localhost` means the phone
itself, and `10.0.2.2` is not your computer's address.

## 4. Inspect received data

Open **Executions → execution → Webhook → Output → body** in n8n. Match its
`event_id` with the app journal. Enable saving successful execution data if needed;
DevServer already does this. Execution history contains complete messages, so
configure access and retention accordingly.

Example notification body:

```json
{
  "schema_version": 1,
  "event_id": "5d8ee1a5-9360-4b0e-8412-942b2e1c99ab",
  "device_id": "43b55766-95b6-40b8-8f4f-c0240f547914",
  "device_code": "personal-phone",
  "message_type": "notification",
  "occurred_at": "2026-09-09T09:00:00Z",
  "source": "org.example.chat",
  "source_name": "Example Chat",
  "title": "Test notification",
  "text": "Connection check"
}
```

| Field | Meaning |
| --- | --- |
| `event_id` | Event ID, preserved across delivery retries |
| `device_id` | Installation ID |
| `device_code` | Editable device label |
| `message_type` | `test`, `notification` or `sms` |
| `occurred_at` | Event timestamp in ISO 8601 format |
| `source` / `source_name` | Source package / app name; SMS uses source `android` |
| `title` | Notification title; absent for SMS and tests |
| `sender` | SMS sender; absent for notifications and tests |
| `text` | Event text |

Directly after Webhook use `{{ $json.body.text }}` or `{{ $json.body.device_code }}`.
If intermediate nodes replace the data, reference the original node explicitly:
`{{ $('Webhook').first().json.body.text }}`.

## 5. Add message processing

For a complete example, see [forwarding to Telegram](n8n-telegram.md).

The bundled receiver only acknowledges requests; it does not send to Telegram,
store a separate durable queue or deduplicate. Put your actions before Respond to
Webhook, acknowledging only after the operation you consider acceptance succeeds.
For slow processing, persist the event to a durable queue/database first, ACK it,
and perform downstream work separately.

The client uses 10-second connection and read timeouts. A lost response can cause
an already processed request to be retried. Use `event_id` as a unique storage key;
already accepted duplicates must receive the same successful ACK. A separate
“check then insert” without a unique constraint does not prevent concurrent duplicates.

Acknowledging before processing removes the event body from the client's queue
once confirmed. A later n8n failure will not trigger a phone retry. Successful
n8n execution and successful client acknowledgement are different outcomes.

## Test without a phone

Use your actual Production URL and a new ID for each test. This request contains
synthetic data only:

```sh
curl --fail-with-body --max-time 10 \
  -X POST 'https://n8n.example.org/webhook/message487/YOUR-PATH' \
  -H 'Content-Type: application/json' \
  --data '{"schema_version":1,"event_id":"manual-check-001","device_id":"manual-test","device_code":"test-phone","message_type":"test","occurred_at":"2026-09-09T09:00:00Z","source":"life.andre.message487","source_name":"Message487","text":"Synthetic connection test"}'
```

Expect HTTP 200 and `{"status":"accepted","event_id":"manual-check-001"}`.
This checks the server contract; also test the Android connection button and a new
notification or SMS to exercise actual app delivery.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| HTTP 404 | Production URL, publication, POST method and path |
| HTTP 401/403 | Authentication and proxy/n8n access restrictions |
| HTTP 301/302 | Supply the final URL; redirects are not followed |
| `INVALID_ACK` with HTTP 200 | JSON object, `status: accepted`, matching ID and Webhook response mode |
| Timeout/network error | Phone connectivity, TLS, firewall and time until response |
| No execution in the editor | Executions tab and successful execution retention |
| Tests arrive, messages do not | Sources, Android permissions, selected apps and pause state |

Network failures, timeouts, HTTP 408/425/429 and 5xx retry automatically. Invalid ACKs
and other HTTP errors require intervention and manual retry from the journal.
Changing the URL only affects new events; queued events retain their old destination.
Send a new test after fixing configuration and delete old records separately if needed.
The top-bar bug icon opens [diagnostics](diagnostics.md), which records delivery
outcomes without message text.
