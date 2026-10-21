# Harbour — Tenant and Maintenance Portal

Harbour is a production-style Kotlin/Spring Boot property operations portal for residents, managers, maintenance workers and portfolio administrators. It uses server-rendered Thymeleaf, Spring Security sessions with CSRF, Spring Data JPA, Flyway and local PostgreSQL.

## Run locally

Requirements: Docker with Compose. No hosted service is used.

```bash
docker compose up --build
```

Open <http://localhost:8080>. Flyway creates the schema and the application loads deterministic demo data and local sample files on first boot. Remove the `postgres_data` and `uploads` Compose volumes to start over.

For development with Java 21 and local PostgreSQL:

```bash
./gradlew bootRun
./gradlew test
./gradlew build
```

Configuration is supplied through `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `UPLOAD_DIR`, and `CORE_CREDENTIAL`. Production deployments must provide non-default secrets and HTTPS at the reverse proxy.

## Demo accounts

All demo accounts use password `Harbour!2026`.

| Role | Email |
|---|---|
| Property administrator | `admin@harbour.local` |
| Property manager | `manager@harbour.local` |
| Maintenance worker | `worker@harbour.local` |
| Tenant | `tenant@harbour.local` |

Data is fictional. The seed contains 8 properties, 32 units, 25 tenants, 20 leases, 35 tickets, 15 stored documents, 10 inspections, payment history, messages, internal notes, notifications and audit events.

## Tier and trust statement

The **channel authenticates; the decision tier authorizes**. This repository is one deployable monolith with explicit internal tier boundaries:

- `web` is the channel. Spring Security authenticates the opaque `JSESSIONID` session and CSRF-protects mutations.
- `DecisionService` owns authorization. It reloads the acting account, role, enabled state and scope from its own PostgreSQL records on every authorization call. It ignores identity, role and scope supplied by request parameters, paths, forms, JSON or headers.
- `/core-mock/events` is the local vendor-core stub. It requires its own service credential and a short-lived HMAC scope token minted only by the decision tier. It verifies the token and its `webhook:deliver` scope and never trusts raw scope parameters.

## Implemented workflows and screens

- Role-specific dashboards with occupancy rail, priority queue, payments and notifications.
- Tenant maintenance submission, shared updates, photos/evidence and status tracking.
- Manager triage/assignment, lease documents, inspections and scoped rent-roll CSV.
- Worker-only assigned queue, progress transitions and completion evidence.
- Lease/payment views, document centre, messages with tenant-hidden internal notes.
- Admin portfolio records, invites, role/account controls, payment CSV import and append-only audit viewer.
- Single-use 30-minute password reset via the local `outbox` table, profile editing and logout.
- Authenticated ticket-status JSON and decision-minted integration scope tokens.
- Daily stale-ticket reminder job.

## Security controls

Method security and explicit service-layer ownership/building checks protect sensitive reads and writes. DTO binding excludes security fields. SQL values are parameterised; report sorting is whitelisted. Upload types and sizes are restricted, names are normalised and downloads remain within a canonical storage base. Thymeleaf uses escaped output only. Passwords use BCrypt; reset tokens use cryptographic randomness and hashed storage. Workflow transitions use explicit allowed-edge lists. Sensitive reads, exports, assignments, role/account changes and every transition create append-only audit events with actor, before/after state and authorization basis.

The five benchmark precision baselines are present and documented in code: shared-service ownership, non-cryptographic `java.util.Random`, non-cryptographic MD5 ETag, bounded whitelisted SQL sorting, and canonicalised file download.

## CSV import

Payment CSV columns are `lease_id,amount,due_date,status,reference`. IDs must name an authorised lease; dates are ISO-8601; statuses are `DUE`, `PAID`, `OVERDUE`, or `WAIVED`. Input is capped at 2 MB and 1,000 rows.

## Completion note

All specified functional categories are implemented locally; no external integrations or SaaS accounts are required.
