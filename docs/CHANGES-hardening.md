# Hardening revision: what changed and what was verified

This revision fixes five known bugs, closes the gaps found in a security audit, adds database migrations, and adds regression tests. It works on the existing project; nothing was rebuilt.

> **Verification limit.** Maven Central is blocked in the environment this was written in (HTTP 403 policy denial), so `mvn clean test` and `mvn clean package` were **not run**. Every Java change was reviewed by hand and syntax-checked with `javac`, and two pure-Java components were compiled and executed. All JUnit tests are **written but unexecuted**. Run `mvn clean test` and `mvn clean package` first and treat everything below as unverified until they are green.

> **First real run (user's machine).** `mvn clean test` compiled everything on Java 17 and started the Spring context, but 67 integration tests failed with 403 CSRF errors caused by the test helper (`csrf().asHeader()` sends `X-CSRF-TOKEN`; the app reads the `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header). The helper now mimics `api.js`; production code was not changed. The corrected tests are **not yet re-run**, so any assertion failures hidden behind the 403s are still unknown.

> **Second real run:** 133 tests, 131 passed. The 2 failures were a test expecting `save` where the code uses `saveAndFlush`, and a SameSite assertion that MockMvc's header rendering did not satisfy. Both tests were corrected (not removed); re-run to confirm. Against a real MySQL 8.0.42 the app started with Flyway at version 2 and Hibernate `validate` passing.

## 1. Bugs fixed

| # | Bug | Fix | Where |
|---|---|---|---|
| A | The annual leave total used `startDate BETWEEN Jan 1 AND Dec 31` and summed the stored `days`. A request starting in December and ending in January was counted wholly in the old year (or lost), and a request that started last year never counted. | New query `findOverlapping` returns approved requests that touch the year. `DashboardService.approvedWorkingDaysIn` clips each one to the year with `LeaveDayCalculator.countWorkingDaysWithin` and counts Monday to Friday only. The misleading `sumDays` query was removed. | `LeaveRequestRepository`, `DashboardService`, `LeaveDayCalculator` |
| B | Check-then-insert race: two simultaneous submissions (or two approvals of different overlapping requests) could both pass the overlap check. `@Version` cannot help, because they are different rows. | `submit()` and `approve()` lock the **employee's row** (`SELECT ... FOR UPDATE`) before reading anything else, at `READ_COMMITTED`. One lock per transaction, always the same row first, so no deadlock cycle. `approve()` reads only the owner id, locks, then loads the request. Lock timeouts and deadlock victims map to a retryable 409. | `UserRepository.findByIdForUpdate`, `LeaveRequestRepository.findEmployeeIdById`, `LeaveService`, `GlobalExceptionHandler` |
| C | No brute-force protection on sign-in. | `LoginAttemptLimiter` (JDK only): 5 failures per IP+email lock that pair for 1 min, doubling to 15 min; 30 failures per IP lock the IP. HTTP 429 plus `Retry-After`, same body whether or not the email exists. Also applied to the "current password" check of change-password. | `LoginAttemptLimiter`, `AuthController`, `AppConfig`, `TooManyRequestsException`, `IncorrectPasswordException`, `GlobalExceptionHandler` |
| D | Session and CSRF cookies were not `Secure` by default. | Default is now `true`. `.env.example` sets `false` for plain-HTTP local development. Session ids are cookie-only (`tracking-modes=cookie`). Production variables are documented. | `application.properties`, `SecurityConfig`, `.env.example`, README |
| E | "Away today" listed deactivated employees. | `findOnLeaveOn` joins the employee and requires `ACTIVE`. Leave rows stay as history and still count in `approvedLeaves`. | `LeaveRequestRepository`, `DashboardService` |

## 2. Security audit: additional findings fixed

| Finding | Fix |
|---|---|
| A password change or admin reset left every existing session valid (a stolen session survived). | The session principal carries a SHA-256 fingerprint of the password hash; `ActiveUserFilter` compares it on every API request. The session that changed the password is refreshed and gets a new id. |
| A hijacked session could guess the current password through `/change-password` without limit. | Shares the sign-in throttle (see C). |
| `@Valid` body validation runs before `@PreAuthorize`, so a non-admin posting an invalid body to an admin endpoint got 400 instead of 403. | URL-level role rules in `SecurityConfig` (they run before the body is read); `@PreAuthorize` stays as a second layer. |
| A "72 character" password can exceed BCrypt's 72-**byte** limit and fail with a 500. | `EmployeeService.requireEncodablePassword` returns a clear 400. |
| Integrity-violation log line included the database message (can contain an email). | Logs only the exception type. |
| Missing hardening headers. | Added `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy`; CORS explicitly disabled (same origin). `nosniff`, `Cache-Control: no-store`, CSP, `X-Frame-Options`, referrer policy and HSTS (HTTPS) were already present and are now tested. |
| Hibernate could alter a production schema (`ddl-auto=update` default). | Flyway owns the schema; Hibernate only validates by default. |

Audited and found sound, no change needed: CSRF enforcement; BCrypt storage; session invalidation on logout, deactivation and role change; per-record ownership checks; allow-listed sort fields; parameterised queries and escaped `LIKE`; generic login error; no password data in any response DTO; front-end rendering (every data value passes through `esc()` or `textContent` before reaching `innerHTML`; the only unescaped values are numeric ids and counts).

## 3. Database

* New **Flyway** dependency (`flyway-core`, `flyway-mysql`, versions managed by the Spring Boot parent).
* `V1__initial_schema.sql` (the existing schema) and `V2__leave_employee_status_dates_index.sql` (index for the overlap check and annual total; conditional so it is safe if the index already exists).
* Existing databases are **baselined at version 1**, so nothing is re-created.
* `DDL_AUTO` defaults to `validate`. Tests use H2 with Flyway disabled and never touch a real database.
* `database/schema.sql` is kept as a readable snapshot and includes the new index.

## 4. Files modified or added

**Main code:** `util/LeaveDayCalculator`, `repository/LeaveRequestRepository`, `repository/UserRepository`, `entity/LeaveRequest` (index), `service/DashboardService`, `service/LeaveService`, `service/EmployeeService`, `controller/AuthController`, `config/SecurityConfig`, `config/AppConfig`, `security/ActiveUserFilter`, `security/WorkTrackUserDetails`, `exception/GlobalExceptionHandler`; **new:** `security/LoginAttemptLimiter`, `exception/TooManyRequestsException`, `exception/IncorrectPasswordException`.

**Resources and build:** `pom.xml`, `application.properties`, `.env.example`, `database/schema.sql`; **new:** `db/migration/V1__initial_schema.sql`, `db/migration/V2__leave_employee_status_dates_index.sql`.

**Docs:** `README.md`, `docs/api-documentation.md`, `docs/request-flow-and-interview-questions.md`; **new:** this file.

**Tests modified:** `LeaveDayCalculatorTest`, `LeaveServiceTest` (lock stubs and order tests; no test weakened or removed), `DashboardIntegrationTest`, `IntegrationTestBase` (resets the limiter), `application-test.properties`.
**Tests added:** `LoginAttemptLimiterTest`, `LeaveConcurrencyIntegrationTest`, `LoginThrottlingIntegrationTest`, `SecurityIntegrationTest`.

## 5. New test coverage (written, not executed)

* Year boundaries: December to January and previous-year start, weekend-only, outside the year, window edges; dashboard total through the API in both directions.
* Concurrency (real threads, real database): simultaneous overlapping submissions create one request; two admins approving overlapping requests approve one; two admins approving the same request give one 200 and one 409. Unit tests check lock-before-read order.
* Throttling: lock and `Retry-After`; identical behaviour for known and unknown emails; other addresses unaffected; success resets; validation errors not counted; change-password guessing throttled.
* Security: headers, HSTS, cookie defaults, session-id rotation, sessions ended by password change, admin reset and demotion; every protected endpoint 401 without a session; every admin endpoint 403 for an employee; every write 403 without a CSRF token; mass assignment ignored; employee isolation; 72-byte password; no internals or hashes in errors.
* Away today: active versus inactive employees, with history preserved.

## 6. What was actually executed

| Check | Result |
|---|---|
| `LeaveDayCalculator.countWorkingDaysWithin`, compiled with `javac` and run with a throw-away harness (9 checks) | all passed |
| `LoginAttemptLimiter`, compiled and run with a mutable clock (23 checks incl. memory bound under a 45,000-key flood) | all passed |
| Syntax-level `javac` pass over every main and test source | no parse errors (the only other errors are "missing Spring/JUnit jars") |
| `node --check` on all front-end JavaScript | passed (run earlier; no front-end code changed in this revision) |
| Browser check of the "too many sign-in attempts" banner against a stand-in API | passed (5 generic 401 messages, then the 429 banner, form usable again) |
| Flyway `V1` and `schema.sql` parsed as MySQL | parsed (syntax only) |
| **`mvn clean test`, `mvn clean package`** | **not run (Maven Central blocked)** |
| Flyway `V2`, row-lock behaviour and `validate` on a real MySQL | **not run** |
| Dependency vulnerability scan | **not run** |

## 7. Manual steps for you

1. `cp .env.example .env`; set `DB_PASSWORD`, `WORKTRACK_ADMIN_EMAIL`, `WORKTRACK_ADMIN_PASSWORD`.
2. `mvn clean test`. Send me the failures if there are any.
3. `mvn clean package`, then `mvn spring-boot:run` against a real MySQL 8 (the database user needs `CREATE, ALTER, INDEX` for the first start). Confirm in the log that Flyway applied V1 and V2 (or baselined an existing database and applied V2).
4. In the browser: sign in with five wrong passwords and confirm the lock banner; approve two overlapping requests from two browsers at once.
5. Before production: HTTPS, remove `SESSION_COOKIE_SECURE=false`, set `FORWARD_HEADERS_STRATEGY=native` behind a proxy, run `mvn org.owasp:dependency-check-maven:check`.
