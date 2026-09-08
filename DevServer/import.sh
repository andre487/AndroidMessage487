#!/bin/sh
set -eu

n8n import:workflow --separate --input=/bootstrap/workflows
for workflow in receive error slow invalid-ack; do
    n8n publish:workflow --id="message487-$workflow"
done
