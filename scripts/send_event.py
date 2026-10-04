#!/usr/bin/env python3
"""Send a ReplayDock-signed event with Python's standard library."""
import argparse
import hashlib
import hmac
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--endpoint', required=True)
parser.add_argument('--event-id', default=None)
parser.add_argument('--body', default='{"type":"demo.created"}')
args = parser.parse_args()
secret = os.environ.get('REPLAYDOCK_SIGNING_SECRET')
if not secret:
    parser.error('Set REPLAYDOCK_SIGNING_SECRET to the endpoint secret')
timestamp = str(int(time.time()))
body = args.body.encode('utf-8')
event_id = args.event_id or str(uuid.uuid4())
signature = hmac.new(secret.encode(), timestamp.encode() + b'.' + event_id.encode() + b'.' + body, hashlib.sha256).hexdigest()
request = urllib.request.Request(args.base_url.rstrip('/') + '/hooks/' + args.endpoint,
    data=body, method='POST', headers={
        'Content-Type': 'application/json; charset=utf-8',
        'X-ReplayDock-Event-Id': event_id,
        'X-ReplayDock-Timestamp': timestamp,
        'X-ReplayDock-Signature': 'sha256=' + signature,
    })
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        print(f'HTTP {response.status}: {response.read().decode()}')
except urllib.error.HTTPError as error:
    print(f'HTTP {error.code}: {error.read().decode()}', file=sys.stderr)
    sys.exit(1)
