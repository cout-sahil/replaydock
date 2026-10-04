# API guide

All `/api/**` endpoints require admin authentication. Browser sessions use CSRF protection. For a script, use HTTP Basic to `GET /api/session`, retain its session cookie, and send the returned CSRF header/token on mutations along with authentication.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/session` | Get CSRF header and token |
| GET | `/api/endpoints` | List endpoints without signing secrets |
| POST | `/api/endpoints` | Create endpoint; returns secret once in the response |
| GET | `/api/events` | Latest 200 events, newest first |
| GET | `/api/events/{id}` | Event details and payload |
| GET | `/api/events/{id}/attempts` | Full recorded attempt history |
| POST | `/api/events/{id}/replay` | Requeue a `DELIVERED` or `DEAD` event |
| POST | `/api/endpoints/{id}/demo` | Authenticated lab event submission |
| GET | `/api/endpoints/{id}/mock` | Receiver controls and unique processed count |
| PUT | `/api/endpoints/{id}/mock` | Update simulated failures/delay |
| POST | `/hooks/{id}` | Signed source ingress; no admin session required |
| POST | `/mock/receiver/{id}` | Signed built-in receiver |

## Create endpoint

```json
{"name":"Payments sandbox","targetUrl":null,"maxAttempts":5,"baseDelayMs":1000}
```

Name: 1–80 characters; attempt budget: 1–10; initial delay: 100–60000 ms. Missing destination selects the built-in mock. External destinations require an operator-configured HTTPS origin allowlist.

## Submit lab event

```json
{"sourceId":"payment-001","payload":"{\"type\":\"payment.succeeded\"}"}
```

`payload` is a string containing the exact body forwarded downstream. Signed ingress instead uses the raw UTF-8 body directly. Both enforce a 64 KiB payload limit. IDs must match `[A-Za-z0-9._:-]{1,128}`. Bodies are opaque and need not be valid JSON, though outbound content type is `application/json`.

## Configure mock receiver

```json
{"failuresLeft":2,"failureStatus":503,"delayMs":0}
```

Failures: 0–20; status: 400–599; delay: 0–10000 ms. A processed duplicate is acknowledged without consuming a configured failure. Controls are shared by all events at that endpoint. Delay happens before deduplication; a timed-out request may still finish processing.

## HMAC protocol

Headers:

```text
X-ReplayDock-Event-Id: payment-001
X-ReplayDock-Timestamp: <Unix time in seconds>
X-ReplayDock-Signature: sha256=<64 lowercase hex characters>
```

The signature is:

```text
HMAC_SHA256(key = endpoint secret as UTF-8,
            message = timestamp + "." + event ID + "." + exact UTF-8 request body)
```

The secret is a random 64-character string. Use its UTF-8 characters as the key, not the decoded hex bytes. Timestamp tolerance is ±300 seconds; signature comparison uses a constant-time comparison. UTF-8 must be well formed. Every outbound attempt uses a fresh timestamp/signature and the original source ID/body.

The event ID is authenticated along with the body, then validated and deduplicated. Endpoint-specific keys bind signatures to the endpoint. Use TLS outside the built-in loopback lab.

## Acknowledgment and failures

`202` response:

```json
{"eventId":"internal-uuid","duplicate":false,"status":"PENDING"}
```

`duplicate: true` means the existing identical event was acknowledged; it does not start a new delivery. Its status may already be `DELIVERED` or `DEAD`.

- `400`: invalid input, destination, event ID or UTF-8.
- `401`: invalid, missing, or expired HMAC signature.
- `403`: missing/invalid CSRF token for an admin mutation.
- `404`: endpoint/event not found.
- `409`: reused source ID with a changed body, or replay of an active event.
- `413`: raw webhook payload over 64 KiB.
- `5xx`: acceptance failed; no successful acknowledgment is issued.

For outbound delivery, any `2xx` completes delivery. Connection failures, timeouts, `408`, `429`, and `5xx` retry. Other responses, including redirects, are terminal. Delay after attempt `n` is `min(300000, baseDelayMs × 2^(n−1))`. Attempt count includes the initial request, and resets for each manual replay; history retains all runs.

Retry-After, jitter, automatic provider-native verification, and rate limiting are not implemented.
