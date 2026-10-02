# Google Play SMS review server

Dedicated synthetic-data n8n instance for reproducing Android → computer SMS transfer.
The deployed site is configured in [Caddyfile](Caddyfile); the VM directory is
`/opt/message487-review`. Keep it running throughout review. It is separate from the
MegaProxy SSH review account on the same VM.

The n8n account owns only this disposable review instance. It has no personal workflows,
requires no invitation/OTP/payment, and has no scheduled expiry. Ordinary app users still
provide their own receiver. Never use this shared review account for private messages.

## Configuration and startup

Use Docker Compose and Caddy. On the small review VM, swap supplements RAM; this is a
low-volume test receiver. Resource limits and execution retention live in [compose.yaml](compose.yaml).
The Docker network has no external route. Caddy connects directly to the fixed container
address in Compose; n8n has no published host port. Outbound integrations are intentionally
unavailable on this instance.

Create a private `.env` containing `REVIEW_HOST`, `REVIEW_EMAIL` and
`REVIEW_PASSWORD_HASH`. Use a unique reviewer password and a bcrypt hash; single-quote the
hash in `.env` so Compose does not interpolate its dollar signs. Create `credentials.json`
using the structure of [the local fixture](../DevServer/credentials/header-auth.json),
keeping credential ID `message487-header-auth` but replacing its value with `Bearer ` plus
a newly generated secret token. Do not deploy the public development credentials.
Keep the server directory accessible only to the administrator; the mounted JSON files must
be readable by the container's `node` user. Neither secret file belongs in Git.

On a fresh instance, from its directory:

```sh
sudo docker compose run --rm --no-deps n8n import:credentials --input=/bootstrap/credentials.json
sudo docker compose run --rm --no-deps n8n import:workflow --input=/bootstrap/workflow.json
sudo docker compose run --rm --no-deps n8n publish:workflow --id=message487-review
sudo docker compose up -d --wait
```

Imports replace the matching IDs. Stop this instance before intentionally reimporting;
ordinary restarts need only `docker compose up -d --wait`. Preserve its volume and generated
encryption key. Back up the existing Caddy configuration and add the site block without
removing other routes; validate before reloading Caddy. Ports 80/443 must be reachable for
certificate issuance and HTTPS. Do not change the host's existing SSH restrictions.

## Reviewer procedure

Private credentials and copyable instructions are stored locally in
`~/.my-tokens/message487-play-review-158.160.132.57.{json,txt}`. The two existing Play Console
App access instructions were updated to this instance on 2 October 2026. Recheck them before
resubmitting; the old n8n Cloud video is not a demonstration of this instance.

1. Sign in on the computer and open **Message487 — Read SMS on computer**.
   The workflow is already published; no setup or manual execution is required.
2. In Android **Connection**, save the HTTPS webhook URL and token without the `Bearer `
   prefix. Keep **n8n confirmation** on.
3. In **Sources**, leave Notifications off, enable Incoming SMS and grant SMS permission.
4. Receive a new synthetic SMS. For the dedicated emulator:
   `adb -s emulator-5560 emu sms send +15551234567 'A fresh review message'`.
5. On the computer, open **Executions → newest run → Logs → Read SMS on computer → Output**
   and choose **Table** or **Schema**. Expand the Logs panel if the output is hidden.
6. Match Sender and Message with the phone's SMS app, and Event ID with
   **Message487 Journal → newest SMS**. A connection-test event alone does not test SMS access.

Execution history contains webhook data, including authentication headers, and is accessible
to the dedicated reviewer account. Retention is bounded by the settings in Compose;
pruning is asynchronous. Remove test history through n8n when no longer needed. The receiver
acknowledges valid events but does not deduplicate retries or provide a general messaging inbox.

## Verification

```sh
python3 ReviewServer/check.py ~/.my-tokens/message487-play-review-158.160.132.57.json
```

This checks HTTPS, missing/wrong tokens, invalid payloads and a valid synthetic ACK. It does
not replace the Android SMS procedure. If a local HTTP proxy is incompatible with Python,
set `NO_PROXY` to the review hostname for that command.

For a fresh video, keep the phone and computer output readable together and show the
permission, incoming SMS, matching message and event ID, and stopping capture. Use the
release being reviewed; do not expose passwords, tokens or webhook request headers.
