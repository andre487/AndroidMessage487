#!/bin/sh
set -eu

n8n import:credentials --input=/bootstrap/credentials/header-auth.json
n8n import:workflow --separate --input=/bootstrap/workflows
for workflow in receive error slow invalid-ack; do
    n8n publish:workflow --id="message487-$workflow"
done

# Adapt the two portable webhook examples to this disposable local server only.
node <<'JS'
const fs = require('node:fs');
fs.mkdirSync('/tmp/message487-examples', { recursive: true });
for (const slug of ['01-webhook-text', '02-webhook-images']) {
    const workflow = JSON.parse(fs.readFileSync(`/examples/n8n/workflows/${slug}.json`));
    workflow.id = `message487-example-${slug}`;
    workflow.nodes.find(node => node.name === 'Webhook').credentials = {
        httpHeaderAuth: { id: 'message487-header-auth', name: 'Message487 local webhook' },
    };
    fs.writeFileSync(`/tmp/message487-examples/${slug}.json`, JSON.stringify(workflow));
}
JS
n8n import:workflow --separate --input=/tmp/message487-examples
for workflow in 01-webhook-text 02-webhook-images; do
    n8n publish:workflow --id="message487-example-$workflow"
done
