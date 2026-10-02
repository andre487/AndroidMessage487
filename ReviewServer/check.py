import argparse
import datetime
import json
import uuid
import urllib.error
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser(description='Check the live review webhook with synthetic data')
parser.add_argument('access_file', type=Path, help='Private JSON containing host and token')
args = parser.parse_args()
access = json.loads(args.access_file.read_text())
url = f'https://{access["host"]}/webhook/message487/receive'
event = {
    'schema_version': 1,
    'event_id': str(uuid.uuid4()),
    'device_id': 'review-smoke-test',
    'device_code': 'review-smoke-test',
    'message_type': 'test',
    'occurred_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'source': 'life.andre.message487',
    'source_name': 'Message487',
    'text': 'Synthetic webhook check; this is not an Android SMS',
}


def post(body, token=None):
    headers = {'Content-Type': 'application/json'}
    if token is not None:
        headers['Authorization'] = f'Bearer {token}'
    request = urllib.request.Request(url, json.dumps(body).encode(), headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None


assert post(event)[0] in (401, 403), 'Unauthenticated requests must fail'
assert post(event, 'invalid-token')[0] in (401, 403), 'Wrong tokens must fail'
assert post({}, access['token'])[0] == 400, 'Invalid payloads must fail'
status, response = post(event, access['token'])
assert status == 200 and response == {'status': 'accepted', 'event_id': event['event_id']}
print(f'Webhook checks passed; synthetic event ID: {event["event_id"]}')
