# Enhancement phase: what changed

New: e-mail notifications with a transactional outbox, delivery tracking and admin retry (V4); audit log (V5); dashboard analytics with SVG charts; CSV / Excel export; MySQL Testcontainers tests; GitHub Actions CI; README and API docs.

Migrations: `V4__notifications_outbox.sql`, `V5__audit_events.sql` (also in `database/schema.sql`).

New dependencies: `spring-boot-starter-mail` (SMTP sending); test scope `org.testcontainers:junit-jupiter` and `org.testcontainers:mysql` (real MySQL in tests, skipped without Docker). No dependency was added for Excel (a small writer is included) or charts (hand-written SVG).

New configuration: see `backend/.env.example` (`WORKTRACK_MAIL_MODE`, `WORKTRACK_MAIL_FROM`, `MAIL_*`, `NOTIFICATIONS_*`, `EXPORT_MAX_ROWS`).

Status: **not compiled or run with Maven** in the authoring environment. Run `mvn clean test` and `mvn clean package` from `backend/` and report the results.
