#!/bin/sh
set -eu

marker=/home/node/.n8n/message487-bootstrap-v1
if [ ! -f "$marker" ]; then
    /bin/sh /bootstrap/import.sh
    touch "$marker"
fi

exec n8n start
