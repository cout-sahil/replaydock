# Architecture and security

## Components

`ApiController` reads a bounded raw UTF-8 body and validates endpoint HMAC before acceptance. `DockService` orchestrates JPA entities and Spring Data repositories inside service-level `@Transactional` boundaries. API records are built inside those transactions; entities and lazy proxies do not reach controllers. `DeliveryWorker` claims one due event, sends outside the database transaction, then commits an attempt and the next state. The dashboard calls the protected admin API. The built-in receiver validates the same signature protocol and atomically records the first business action for a source ID.

## Persistence and restart behavior

The inbox has a unique `(endpoint_id, source_id)` constraint. Identical submissions return the existing event; a changed body conflicts. Acceptance completes its transaction before returning `202`.

Claims change `PENDING` to `DELIVERING` in a transaction. Outcome recording inserts an attempt and updates the event in one transaction. On application readiness, interrupted `DELIVERING` events return to `PENDING`. If a process crashes after a receiver processed a request but before outcome recording, another delivery can occur. A crash-interrupted attempt has no outcome row; the system does not invent a receiver result.

The mock deduplicates under a database primary key. Failure-count changes and successful business-action records commit atomically. Endpoint row locks serialize acceptance of duplicate source IDs. Mock-control row locks serialize deduplication checks and business actions. Event completion and replay lock the event row; a conditional JPQL update changes only a still-pending due event to `DELIVERING`, preventing competing claims. Bulk claim/recovery queries clear the persistence context so DTOs reflect committed state. The application still targets one JVM: startup recovery has no cross-instance leases or fencing tokens, so do not run multiple instances against the same database.

## Authentication and secrets

- Dashboard/admin API: Spring Security form login or HTTP Basic; the admin password is BCrypt hashed in memory. Set it via `REPLAYDOCK_ADMIN_PASSWORD`; absent a configured value, a temporary local password is generated and logged. Session cookies are HttpOnly and SameSite=Strict. CSRF protection applies to admin mutations, including Basic-authenticated API calls.
- Webhook ingress and mock receiver: admin login is bypassed, but HMAC verification runs in the controller. Those routes are exempt from CSRF because they are signed machine requests.
- Per-endpoint signing secret: generated from 32 random bytes, returned on endpoint creation, omitted from normal read APIs, stored as plaintext in H2 to support validation/signing. v0.1 uses the same key inbound and outbound; independent keys and rotation are future work.
- Secrets, database files, build outputs, and environment files are not committed. Response bodies from destinations are discarded; only status and bounded diagnostic summaries are recorded.

## Destination controls

The only automatically allowed HTTP destination is the built-in loopback receiver whose path is assembled by the server. User-supplied destinations require an exact configured HTTPS origin, with no embedded credentials or URL fragment. Java's HTTP client never follows redirects. An allowlist reduces accidental arbitrary network access but does not pin DNS resolution or block private addresses reached through an approved hostname. Only allow operator-controlled origins. Production use needs resolved-address validation, egress isolation, and DNS-rebinding defenses.

## Limits

This local demo intentionally has no tenant isolation, encryption at rest, payload redaction, retention/deletion workflow, rate limiting, metrics export, or provider-native signatures. The raw webhook body read is bounded, but authenticated admin JSON request-envelope size is not enforced globally. The inbox UI shows only the newest 200 events. A slow receiver blocks the sole dispatcher until timeout. Maximum automatic backoff is five minutes; no jitter or Retry-After support. Single-admin credentials and endpoint secrets are not rotatable through the UI.

Binding defaults to loopback. Before any hosted deployment, design TLS termination, secure session cookies, secret encryption/rotation, distributed claims, migrations, retention, access controls, abuse limits, and operational monitoring. v0.1 is a lab for exploring these failure modes, not a production delivery service.

## Entity mapping

Five `@Entity` classes preserve the original tables and column names. Events have a lazy many-to-one endpoint association; attempts have a lazy many-to-one event association. Mock controls share their endpoint primary key using `@OneToOne` and `@MapsId`. Processed receipts have an `@EmbeddedId` containing endpoint and source IDs. Unique constraints and foreign keys remain database-enforced. No delete cascades are enabled.

Hibernate validates the existing schema (`ddl-auto=validate`); it does not auto-update or recreate it. `Instant` fields explicitly map to the existing SQL `TIMESTAMP` type and payloads remain CLOBs. Open Session in View is disabled, and entity changes inside write transactions persist through dirty checking. Routine CRUD uses repositories; only conditional claims and restart recovery require explicit JPQL updates.
