# How a request travels through WorkTrack, and interview questions

> Everything below describes this project's code. File names are real; open them while you read.
> Note: the Java code and tests in this repository were reviewed by hand but could not be compiled in the environment where it was written (see "Verification status" in the README). Run `mvn test` yourself before you present it, and be ready to say that honestly.

## 1. The request flow, step by step

Example: an administrator clicks **Approve** on a pending leave request.

```
Browser (leaves.js)
  │  fetch PATCH /api/leaves/12/approve
  │  cookie WORKTRACK_SESSION + header X-XSRF-TOKEN          (frontend/js/api.js adds both)
  ▼
Spring Security filter chain                                  (config/SecurityConfig.java)
  │  1. CSRF filter: header token must equal the XSRF-TOKEN cookie, else 403
  │  2. Session filter: cookie → server-side session → SecurityContext (who is this?)
  │  3. ActiveUserFilter: is the account still ACTIVE? else 401
  │  4. Authorization: /api/** needs a signed-in user
  ▼
LeaveController.approve(id)                                   (controller/LeaveController.java)
  │  @PreAuthorize("hasRole('ADMIN')")  → employees get 403 here
  │  takes the signed-in user from CurrentUser, never from the request body
  ▼
LeaveService.approve(id, admin)                               (service/LeaveService.java, @Transactional)
  │  load request  → 404 if missing
  │  rules: not your own request, still PENDING, no overlap with approved leave
  │  entity.approve(admin, now)  (the state change lives in the entity)
  ▼
LeaveRequestRepository                                        (Spring Data JPA → Hibernate → SQL)
  │  UPDATE leave_requests SET status=?, reviewed_by=?, version=version+1 WHERE id=? AND version=?
  ▼
MySQL
  ▲
  │  entity → LeaveResponse record (DTO) → Jackson → JSON
Browser: shows a toast and refreshes the list
```

If anything throws a typed exception (`NotFoundException`, `ConflictException`, `BadRequestException`, `ForbiddenOperationException`), `GlobalExceptionHandler` turns it into the one JSON error shape described in `docs/api-documentation.md`. Controllers contain no try/catch.

### Logging in (the other flow worth knowing)

1. The browser calls `GET /api/auth/csrf`; the server sets the `XSRF-TOKEN` cookie.
2. `POST /api/auth/login` with email and password. `AuthController` calls `AuthenticationManager.authenticate(...)`, which uses `CustomUserDetailsService` to load the user by email and `BCryptPasswordEncoder` to compare the password to the stored hash.
3. On success the security context is saved into the HTTP session (`SecurityContextRepository`). Session fixation protection changes the session id (`changeSessionId`).
4. The server answers with `Set-Cookie: WORKTRACK_SESSION=...; HttpOnly; SameSite`. JavaScript can never read that cookie, so an XSS bug cannot steal the session id directly.
5. Every later request carries the cookie; the filter chain restores the user from the session.

## 2. Why these design choices

| Choice | Reason |
|---|---|
| Session cookie instead of JWT | The app is one server and one browser front end. Sessions can be revoked on the server (logout, deactivation), and an `HttpOnly` cookie is not reachable from JavaScript. |
| CSRF kept on | Cookie sessions are sent automatically by the browser, so another site could trigger requests. The double-submit token blocks that. |
| DTO records | The API never exposes entities, so the password hash and internal fields cannot leak, and the API shape can differ from the table shape. |
| One `users` table | Account and employee profile are the same record, so they cannot disagree. A role column separates ADMIN from EMPLOYEE. |
| Soft delete (INACTIVE) | Leave history must survive, and foreign keys stay valid. |
| `Clock` bean | "Today" can be fixed in tests, so date rules are deterministic. |
| Sort allow-list | `sortBy` is user input; only known fields are accepted, so nobody can sort by `passwordHash`. |

## 3. Interview questions (with answers grounded in this project)

### Spring Boot and architecture

**1. Describe the layers of WorkTrack and what belongs in each.**
Controller: HTTP only (parse, validate with `@Valid`, return a DTO). Service: business rules and transactions. Repository: database access. Entity: persistent state and small state-transition methods. DTO: the public API contract. Security and exception packages hold cross-cutting concerns.

**2. Why constructor injection instead of `@Autowired` fields?**
Dependencies are explicit and `final`, the class cannot be built half-initialised, and unit tests can create it with plain `new` and Mockito mocks (see `LeaveServiceTest`).

**3. What does `@SpringBootApplication` do?**
It combines `@Configuration`, `@EnableAutoConfiguration` and `@ComponentScan`. Component scan starts at the package of the main class, so all packages live under `com.worktrack`.

**4. How is configuration kept out of source control?**
`application.properties` reads `${DB_PASSWORD}` and similar from environment variables; `.env` (git-ignored) is imported through `spring.config.import`. Only `.env.example` with placeholders is committed. The database password and admin password have no default value.

**5. How is the first administrator created without a hard-coded password?**
`AdminInitializer` runs at startup; if no ADMIN exists and `WORKTRACK_ADMIN_EMAIL`/`PASSWORD` are set, it creates one with a BCrypt hash. If they are not set, it does nothing.

### Security

**6. What is CSRF and how does WorkTrack defend against it?**
An attack where a malicious site makes the victim's browser send an authenticated request because cookies go along automatically. WorkTrack uses `CookieCsrfTokenRepository.withHttpOnlyFalse()`: the server sets an `XSRF-TOKEN` cookie, the JavaScript copies it into `X-XSRF-TOKEN`, and the server compares. Another origin cannot read our cookie, so it cannot forge the header.

**7. Why is the CSRF cookie readable by JavaScript when the session cookie is not?**
The script must read the token to send it. The token alone is useless without the session cookie, and the session cookie stays `HttpOnly`.

**8. What does BCrypt give you that SHA-256 does not?**
A per-password random salt and a tunable work factor, so cracking is slow and identical passwords produce different hashes. SHA-256 is fast, which helps attackers.

**9. How do you prevent user enumeration at login?**
Every failure (unknown email, wrong password, inactive account) returns the same `401 Invalid email or password`.

**10. Authentication vs authorization?**
Authentication: who you are (login, session). Authorization: what you may do (`@PreAuthorize("hasRole('ADMIN')")`, ownership checks in the service).

**11. Role checks pass, but is that enough? (IDOR)**
No. An employee could request `/api/leaves/99` for someone else's leave. `LeaveService` checks that the caller owns the request or is an admin and answers 403 otherwise. `/api/leaves/my` has no employee parameter, so the scope comes from the session, not the client.

**12. What is session fixation and what protects against it?**
An attacker plants a known session id before login. `sessionFixation().changeSessionId()` issues a new id at login.

**13. Why a strict Content-Security-Policy?**
With `script-src 'self'` and no inline scripts or styles, injected markup cannot run script. That is why the front end uses external JS files, `textContent`/an escape helper, and a small helper that sets dynamic widths through the CSSOM instead of `style=""` attributes.

**14. How do you prevent XSS in the front end?**
User text is inserted with `textContent` or escaped before being placed in HTML templates, and CSP is the second layer.

**15. How does `@PreAuthorize` work, and why does it need `@EnableMethodSecurity`?**
Spring wraps the bean in a proxy that checks the expression before the method runs. Without `@EnableMethodSecurity` the annotations are ignored. A denied call throws `AccessDeniedException`, which the exception handler maps to 403.

**16. What happens when an admin is deactivated while logged in?**
`ActiveUserFilter` checks the account status on every request and rejects it with 401, so the existing session stops working immediately.

### JPA, Hibernate and the database

**17. What is the N+1 problem and where did you avoid it?**
Loading a list of leave requests and then lazily loading each employee runs one extra query per row. `@EntityGraph(attributePaths = {"employee","reviewedBy"})` on `LeaveRequestRepository` fetches them with a join.

**18. What does `@Transactional` do and why `readOnly = true` on reads?**
It opens a transaction at method entry and commits or rolls back at exit (rollback on runtime exceptions). `readOnly` is a hint that lets Hibernate skip dirty checking and lets the driver optimise. `open-in-view=false` means lazy loading outside the service layer would fail, which forces mapping to DTOs inside the transaction.

**19. Why is `spring.jpa.open-in-view=false`?**
Open Session In View keeps a database connection open during view rendering and hides lazy-loading and query problems. Turning it off makes data access explicit.

**20. Explain optimistic locking in this project.**
`LeaveRequest` has `@Version`. Two admins open the same pending request; the first approves it (version 0→1). The second `UPDATE ... WHERE version = 0` matches no row, Hibernate throws `ObjectOptimisticLockingFailureException`, and `GlobalExceptionHandler` returns 409. No decision is silently overwritten and no database lock is held while a person thinks.

**21. Optimistic vs pessimistic locking?**
Optimistic detects a conflict at write time using a version, best when conflicts are rare. Pessimistic locks rows up front (`SELECT ... FOR UPDATE`), best when conflicts are common, at the cost of waiting and deadlock risk.

**22. How do search and filters work without writing many query methods?**
`JpaSpecificationExecutor` with `EmployeeSpecifications` and `LeaveSpecifications`: each filter returns a `Specification` and they are combined with `and`. `%` and `_` typed by the user are escaped so they match literally.

**23. How do you stop SQL injection?**
Spring Data and JPQL use bound parameters, and the sort field is checked against an allow-list. No SQL is built by string concatenation with user input.

**24. How is the overlap check written, and how do you stop two simultaneous requests passing it?**
`existsOverlap` asks for a pending or approved request of the same employee where `start <= :end AND end >= :start`. On its own that is a check-then-insert race: two requests both see "no overlap" and both insert. `@Version` cannot help because they are two different rows. `LeaveService.submit()` therefore starts with `SELECT ... FOR UPDATE` on the employee's row (`UserRepository.findByIdForUpdate`) at `READ_COMMITTED`, so concurrent requests for the same person queue up and the second one's overlap check sees the first one's committed row. `approve()` does the same (it reads only the owner id, locks the employee row, and only then loads the request). There is one lock per transaction, always on the same row first, so there is no lock cycle and no deadlock. `LeaveConcurrencyIntegrationTest` fires several threads at once and expects exactly one success.

**24b. Why `READ_COMMITTED` and not MySQL's default `REPEATABLE READ`?**
Under `REPEATABLE READ` the first plain `SELECT` fixes a snapshot; if that read happens before the lock is acquired, the later overlap check would read stale data even though the lock was won. `READ_COMMITTED` makes each statement see the latest committed rows, and the locking read is the first statement anyway.

**24c. How does the sign-in throttle work, and why is the key IP + email?**
`LoginAttemptLimiter` counts failures per (IP, hashed typed email) and per IP, with lock times that double up to 15 minutes. Keying on the email alone would let an attacker lock a real user out by typing their address; keying on the IP alone would not stop one machine spreading guesses over many accounts, so there is also a higher per-IP limit. The counter does not care whether the account exists, so the 429 reveals nothing. It is in memory: simple and testable, but reset on restart and not shared across instances.

**24d. Why did changing a password not log other sessions out, and what changed?**
A session stays valid until it expires, so a stolen session survived a password change. The session principal now carries a fingerprint (SHA-256) of the password hash it was created with, and `ActiveUserFilter` compares it with the database on every API request. A password change or admin reset therefore ends every older session; the session that made the change is refreshed and gets a new id.

**24e. Why are role rules in `SecurityConfig` as well as `@PreAuthorize`?**
`@PreAuthorize` runs when the controller method is called, which is after Spring has parsed and validated the request body. A non-admin posting an invalid body to an admin endpoint would get a 400 (revealing the API's shape) instead of 403. URL rules run first, so the answer is always 403; the annotations stay as a second layer.

**25. Why `Instant` for timestamps and `LocalDate` for leave dates?**
A timestamp is a point in time (store UTC). A leave date is a calendar date with no time zone meaning.

**26. `ddl-auto=update` vs `validate`, and why Flyway?**
`update` lets Hibernate alter tables: convenient but unpredictable and unsafe in production (it never drops or renames, so schemas drift). The project now uses Flyway versioned scripts (`V1__initial_schema.sql`, `V2__...`) as the single source of schema changes, and Hibernate only `validate`s. Existing databases are baselined at version 1. `database/schema.sql` remains a readable snapshot.

**27. Why a soft delete?**
Leave history and foreign keys must stay intact; `DELETE /api/employees/{id}` sets `INACTIVE` and cancels pending requests.

### REST and error handling

**28. What status codes does the API use and why?**
201 + `Location` on create, 204 on empty success, 400 invalid input, 401 not authenticated, 403 not allowed, 404 missing, 409 state conflict (duplicate email, overlap, already decided, concurrent edit).

**29. Why return a consistent error body?**
The front end has one code path: show `message`, and map `fieldErrors` to form inputs through `data-field`. It also guarantees stack traces never reach the client.

**30. How is input validated?**
Bean Validation annotations on request records (`@NotBlank`, `@Email`, `@Size`, `@Pattern`) with `@Valid` on the controller parameter, plus business rules in the service (dates, policy limits). The front end validates too, but the server is the authority.

**31. PUT vs PATCH?**
PUT replaces the representation (employee update sends the full set of fields); PATCH is used for the leave actions because they are state transitions, not full replacements.

**32. Why is `GET /api/auth/csrf` needed?**
The token cookie is created lazily; the front end and Postman call it once before the first state-changing request.

### Business logic and testing

**33. How are working days counted?**
`LeaveDayCalculator.countWorkingDays` counts Monday to Friday between the dates, inclusive. Holidays are not modelled (listed as future work).

**34. Name some rules enforced by `LeaveService`.**
End not before start, no past start for casual/earned, sick leave backdated at most 7 days, at most 60 days long, at most 365 days ahead, weekend-only ranges rejected, no overlap, only the owner cancels, admins cannot approve their own request, only PENDING can change state.

**35. How did you make date logic testable?**
A `Clock` bean is injected. Tests use `Clock.fixed(...)` (Wednesday 2026-03-04) so "today" never changes.

**36. Unit test vs integration test in this project?**
`LeaveServiceTest` uses Mockito, no Spring and no database, so it is fast and isolates rules. The `integration` tests use `@SpringBootTest` + MockMvc, a real login with a session, CSRF, the real security chain and an H2 database in MySQL mode, so they prove the layers work together (for example employees get 403 on admin endpoints).

**37. Why H2 for tests when production uses MySQL?**
Speed and no external setup. The cost is that H2 is not MySQL; a Testcontainers MySQL test is listed as future work.

**38. What did you not test, and what would you add?**
No browser end-to-end tests, no load tests, no real MySQL run in CI. Honest answer: add Testcontainers and Playwright.

### Front end

**39. Why vanilla JS with ES modules?**
The brief required no framework. Modules give file-level scope and imports (`api.js`, `ui.js`, `forms.js`) without a bundler.

**40. How does `api.js` handle sessions expiring?**
Any 401 (outside the login page) clears the UI state and redirects to the login page with a message.

### Scenario questions

**41. Two admins approve the same request at the same moment.**
Optimistic locking: one succeeds, the other gets 409 and the UI shows "changed by someone else, refresh".

**42. An employee changes the id in the URL to read someone else's leave.**
The service compares owner and caller and returns 403, covered by an integration test.

**43. You need to deploy to production. What changes?**
HTTPS in front (secure cookies are already the default, so remove the local `SESSION_COOKIE_SECURE=false`), `FORWARD_HEADERS_STRATEGY=native` if a proxy terminates TLS (real client IP for the throttle, HSTS), Flyway migrations with a separate privileged account and a least-privilege runtime DB user, secrets from the host's secret store, and a shared session store and shared throttle counters if you run more than one instance.

**44. How would you add "annual leave balance"?**
A `leave_balance` table (or per-type quota column) per employee and year; deduct on approve and refund on cancel in the same transaction; the overlap and balance checks together need a lock on the balance row.

**45. How would you scale sessions horizontally?**
Spring Session with Redis or JDBC, or sticky sessions at the load balancer.

## 4. Questions to ask yourself before the interview

- Can I draw the request flow from memory?
- Can I explain what happens on a failed `UPDATE ... WHERE version = ?`?
- Can I name three things this project does not do yet, and say so openly? (Limitations are in the README.)
- Did I run `mvn test` myself and see the result?
