# WorkTrack REST API

Base URL: `http://localhost:8080`. Requests and responses are JSON (`Content-Type: application/json`). Dates are ISO `YYYY-MM-DD`; timestamps are ISO-8601 UTC instants (`2026-03-04T09:00:00Z`).

## Authentication, sessions and CSRF

WorkTrack uses a **server-side session**. `POST /api/auth/login` sets an `HttpOnly`, `SameSite=Lax` cookie named `WORKTRACK_SESSION`; send it back on every request (browsers and Postman do this automatically). The cookies (session and `XSRF-TOKEN`) are **`Secure` by default**, meaning a browser sends them only over HTTPS; for plain-HTTP local development set `SESSION_COOKIE_SECURE=false` (see the README). Sessions last 30 minutes of inactivity. Changing or resetting a password ends the person's other sessions (their next request gets `401`).

**CSRF protection is enabled.** Every `POST`, `PUT`, `PATCH` and `DELETE` (including login and logout) must carry the token:

1. Make any `GET` request (for example `GET /api/auth/csrf`). The response sets a cookie `XSRF-TOKEN`.
2. Copy its value into the request header `X-XSRF-TOKEN`.

A missing or wrong token gives `403`. The included Postman collection does this for you.

## Error format

Every error uses the same shape:

```json
{
  "timestamp": "2026-03-04T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Please correct the highlighted fields",
  "path": "/api/employees",
  "fieldErrors": { "email": "Enter a valid email address" }
}
```

`fieldErrors` is present only for body validation failures.

| Status | Meaning |
|---|---|
| 400 | Invalid body, parameter or business rule (for example end date before start date) |
| 401 | Not signed in, session expired, account deactivated, or bad login (always "Invalid email or password") |
| 403 | Signed in but not allowed, or missing/invalid CSRF token |
| 404 | The record does not exist |
| 409 | Conflict with current state: duplicate email, overlapping leave, request not pending, deleting a work entry that is not a draft, concurrent change, or the record is briefly locked by another change (safe to retry) |
| 429 | Too many failed sign-in attempts (or wrong current passwords) from this address. Carries a `Retry-After` header in seconds. |
| 500 | Unexpected error (details are logged on the server, never returned) |

## Pagination, sorting and filters

List endpoints accept `page` (from 0, default 0), `size` (1 to 100, default 10), `sortBy` (allow-listed fields only, otherwise 400) and `direction` (`asc` or `desc`). They return:

```json
{ "content": [ ... ], "page": 0, "size": 10, "totalElements": 42, "totalPages": 5, "first": true, "last": false }
```

---

## Authentication

### `POST /api/auth/login` (public)
```json
{ "email": "ada@example.com", "password": "your-password" }
```
`200` returns the signed-in person (an *Employee* object, see below). `401` for any failure (unknown email, wrong password, inactive account all give the identical `Invalid email or password`). Email is case-insensitive.

`429` after repeated failures. Failures are counted per IP address and typed email: 5 failures lock that pair for 1 minute, then 2, 4, 8... up to 15 minutes; 30 failures from one IP (any emails) lock that IP. The `429` body is the same whether or not the email belongs to an account, and a successful sign-in resets the counter:
```json
{ "status": 429, "error": "Too Many Requests",
  "message": "Too many sign-in attempts. Please wait a few minutes and try again", "path": "/api/auth/login" }
```
with header `Retry-After: 60`.

### `POST /api/auth/logout` (signed in)
`204`. Ends the session on the server and removes the cookie.

### `GET /api/auth/me` (signed in)
`200` current person. `401` when not signed in, or when the account was deactivated, its role changed or its password changed after this session began.

### `GET /api/auth/csrf` (public)
`204`. Only purpose: make sure the `XSRF-TOKEN` cookie exists.

### `POST /api/auth/change-password` (signed in)
```json
{ "currentPassword": "old-password-1", "newPassword": "new-password-2" }
```
`204`. `400` if the current password is wrong, the new one equals the old one, or it breaks the policy (8 to 72 characters **and at most 72 bytes**, at least one letter and one digit). Wrong current passwords count towards the same throttle as sign-in (`429`). You stay signed in (your session gets a new id); every other session of yours is ended.

---

## Employees

**Employee object** (never contains a password or hash):
```json
{
  "id": 7,
  "employeeCode": "EMP-0007",
  "fullName": "Priya Nair",
  "email": "priya@example.com",
  "department": "Engineering",
  "jobTitle": "Java Developer",
  "joiningDate": "2025-06-16",
  "status": "ACTIVE",
  "role": "EMPLOYEE",
  "createdAt": "2026-01-10T08:30:00Z",
  "updatedAt": "2026-01-10T08:30:00Z"
}
```

### `GET /api/employees` (admin)
Query: `q` (name or email contains), `department` (exact, case-insensitive), `status` (`ACTIVE` | `INACTIVE`), `page`, `size`, `sortBy` (`id`, `fullName`, `email`, `department`, `jobTitle`, `joiningDate`, `status`, `createdAt`; default `fullName`), `direction`.
`200` page of Employee objects.

### `GET /api/employees/departments` (admin)
`200` `["Design", "Engineering", "Sales"]`

### `GET /api/employees/{id}` (admin, or the person themselves)
`200` Employee. `403` when an employee asks for someone else. `404` unknown id.

### `POST /api/employees` (admin)
```json
{
  "fullName": "Carla Newhire",
  "email": "carla@example.com",
  "password": "Welcome-123",
  "department": "Finance",
  "jobTitle": "Analyst",
  "joiningDate": "2026-02-02",
  "role": "EMPLOYEE",
  "status": "ACTIVE"
}
```
`role` defaults to `EMPLOYEE` and `status` to `ACTIVE`. `201` with a `Location` header and the new Employee. `400` validation, `409` duplicate email.

### `POST /api/employees/{id}/invitation` (admin)

Queues a fresh invitation e-mail. Response: the employee plus `invitation`: `QUEUED`, `ALREADY_QUEUED`, `SKIPPED_INACTIVE_ACCOUNT` or `SKIPPED_INVALID_EMAIL`. `POST /api/employees` returns the same `invitation` field. `QUEUED` means waiting to be sent, not delivered. Needs the CSRF header. Audited as `INVITATION_RESENT`.

### `POST /api/auth/setup-password` (public, CSRF header required, rate limited)

Body `{ "token": "<secret from the invitation link>", "password": "<new password>" }`. Single use; a newer invitation invalidates older links. Any invalid, used or expired token gets the same 400 message so tokens cannot be probed. Audited as `PASSWORD_SETUP_COMPLETED`.

### `PUT /api/employees/{id}` (admin)
Same fields as create, `status` and `role` required, `password` optional (send it only to reset the password).
`200` updated Employee. Setting a `password` ends that person's open sessions. `409` when the email is taken, or when an administrator tries to deactivate themselves or remove their own admin role.
Changing `status` to `INACTIVE` cancels the person's pending leave requests.

### `DELETE /api/employees/{id}` (admin)
**Deactivates** the employee (soft delete): record and history stay, sign-in stops, pending requests are cancelled. `204`. `409` if it is your own account.

---

## Leave requests

**Leave object:**
```json
{
  "id": 12,
  "employeeId": 7,
  "employeeName": "Priya Nair",
  "employeeEmail": "priya@example.com",
  "department": "Engineering",
  "leaveType": "EARNED",
  "startDate": "2026-03-09",
  "endDate": "2026-03-16",
  "days": 6,
  "reason": "Family wedding",
  "status": "APPROVED",
  "reviewedById": 1,
  "reviewedByName": "Ada Admin",
  "reviewedAt": "2026-03-05T10:15:00Z",
  "rejectionReason": null,
  "createdAt": "2026-03-04T09:00:00Z"
}
```
`days` counts Monday to Friday only (weekends are not counted).

### `POST /api/leaves` (signed in)
```json
{ "leaveType": "EARNED", "startDate": "2026-03-09", "endDate": "2026-03-16", "reason": "Family wedding" }
```
`leaveType`: `CASUAL`, `SICK`, `EARNED`. `reason`: 5 to 500 characters. Response `201` with the request in status `PENDING`.
`400` for: end before start; casual/earned starting in the past; sick leave backdated more than 7 days; only weekend days; more than 60 calendar days; starting more than 365 days ahead; missing fields.
`409` when the dates overlap the employee's own pending or approved request.

### `GET /api/leaves/my` (signed in)
The caller's own requests. Query: `status`, `from`, `to` (requests that overlap that date range), `page`, `size`, `sortBy` (`id`, `startDate`, `endDate`, `days`, `status`, `leaveType`, `createdAt`; default `createdAt`), `direction` (default `desc`). There is no employee parameter: the scope is always the caller.

### `GET /api/leaves` (admin)
All requests. Same query as above plus `employeeId`.

### `GET /api/leaves/{id}` (owner or admin)
`200` Leave. `403` for another employee's request. `404` unknown id.

### `PATCH /api/leaves/{id}/cancel` (owner only)
Body: none (or `{}`). `200` Leave with status `CANCELLED`. `403` if it is not yours (administrators included). `409` unless the request is `PENDING`.

### `PATCH /api/leaves/{id}/approve` (admin)
Body: none (or `{}`). `200` Leave with status `APPROVED`, `reviewedByName` and `reviewedAt` set.
`403` for non-admins and for your own request. `409` when the request is not pending, would overlap already approved leave for that employee, or was changed concurrently.

### `PATCH /api/leaves/{id}/reject` (admin)
```json
{ "reason": "Release week, please pick other dates." }
```
`reason`: 3 to 500 characters. `200` Leave with status `REJECTED` and `rejectionReason`. Same `403` and `409` rules as approve.

---

## Dashboards

### `GET /api/dashboard/admin` (admin)
```json
{
  "totalEmployees": 13,
  "activeEmployees": 12,
  "pendingLeaves": 5,
  "approvedLeaves": 4,
  "rejectedLeaves": 1,
  "cancelledLeaves": 1,
  "onLeaveToday": 2,
  "departments": [ { "department": "Engineering", "count": 5 } ],
  "pendingQueue": [ /* up to 5 newest pending Leave objects */ ],
  "outToday": [ /* approved Leave objects covering today, ACTIVE employees only */ ]
}
```
Every number comes from a database query. `outToday` and `onLeaveToday` list only employees who are currently `ACTIVE`; a deactivated employee's approved leave is kept as history and still counts in `approvedLeaves`.

### `GET /api/dashboard/employee` (signed in)
`approvedDaysThisYear` counts only the working days that fall inside the current calendar year: a request from 29 Dec to 2 Jan contributes its January days to the new year and its December days to the old one, and weekends are never counted.

```json
{
  "profile": { /* the caller's Employee object */ },
  "totalRequests": 2,
  "pendingRequests": 1,
  "approvedRequests": 1,
  "rejectedRequests": 0,
  "cancelledRequests": 0,
  "approvedDaysThisYear": 5,   // approved Monday-Friday days inside this calendar year
  "recentRequests": [ /* up to 5 of the caller's newest Leave objects */ ]
}
```

---

## Daily work reports

An entry is one task of one person's day. There is no employee id anywhere in a create request: the author is the signed-in user. Status values: `DRAFT`, `IN_PROGRESS`, `COMPLETED`, `BLOCKED`. Drafts are private to their author (not listed for, and not readable by, administrators) and are not counted in any hours.

**Work-log object:**
```json
{
  "id": 31,
  "employeeId": 7,
  "employeeName": "Priya Nair",
  "employeeEmail": "priya@example.com",
  "department": "Engineering",
  "workDate": "2026-03-04",
  "taskTitle": "Fix login bug",
  "description": "Found the cause in the session filter.",
  "hoursSpent": 3.50,
  "status": "COMPLETED",
  "createdAt": "2026-03-04T09:00:00Z",
  "updatedAt": "2026-03-04T09:00:00Z"
}
```

### `POST /api/work-logs` (signed in)
```json
{ "workDate": "2026-03-04", "taskTitle": "Fix login bug", "description": "Found the cause in the session filter.",
  "hoursSpent": 3.5, "status": "COMPLETED" }
```
`201` with a `Location` header and the entry. `400` for: missing fields (`fieldErrors`), title not 3-150 or description not 5-2000 characters, hours outside 0.25-24 or with more than 2 decimals, unknown status, a future date, a date more than 30 days back, or a day that would exceed **24 hours across all of that person's entries**. Extra fields such as `employeeId` are ignored.

### `GET /api/work-logs/my` (signed in)
The caller's own entries, drafts included. Query: `status`, `from`, `to` (work date, inclusive), `q` (task title contains, case-insensitive), `page`, `size`, `sortBy` (`id`, `workDate`, `hoursSpent`, `status`, `taskTitle`, `createdAt`; default `workDate`), `direction` (default `desc`). Paged like the other lists.

### `GET /api/work-logs/my/summary` (signed in)
Query: `from`, `to` (default: the last 7 days ending today; at most 366 days).
```json
{
  "from": "2026-02-26", "to": "2026-03-04",
  "totalHours": 17.50, "entryCount": 5, "daysReported": 3, "draftCount": 1,
  "statusCounts": { "DRAFT": 1, "IN_PROGRESS": 1, "COMPLETED": 3, "BLOCKED": 0 },
  "days": [ { "date": "2026-03-03", "hours": 8.00, "entries": 2 }, { "date": "2026-03-04", "hours": 9.50, "entries": 3 } ]
}
```
Hours, entries and days count submitted entries only (not drafts); `statusCounts` includes drafts.

### `GET /api/work-logs/{id}` (owner, or an administrator for a submitted entry)
`200`. `403` for another employee's entry and for an administrator reading someone else's draft. `404` unknown id.

### `PUT /api/work-logs/{id}` (owner only)
Same body as create. `200`. `403` for everybody else, administrators included. The date rules apply only when the date is changed (an old entry can be edited in place). `404` unknown id.

### `DELETE /api/work-logs/{id}` (owner only, drafts only)
`204`. `409` when the entry is not a draft ("change the status instead, or edit the entry"). `403` for everybody else. `404` unknown id.

### `GET /api/work-logs` (admin)
All **submitted** entries (never drafts). Query: `employeeId`, `status`, `from`, `to`, `q` (task title **or employee name**), `page`, `size`, `sortBy`, `direction` as above.

### `GET /api/work-logs/summary` (admin)
Query: `from`, `to` (default last 7 days), `employeeId`.
```json
{
  "from": "2026-02-26", "to": "2026-03-04", "totalHours": 129.50, "entryCount": 31,
  "employeesReporting": 6, "activeEmployees": 8,
  "statusCounts": { "IN_PROGRESS": 4, "COMPLETED": 25, "BLOCKED": 2 },
  "employees": [ { "employeeId": 7, "employeeName": "Priya Nair", "department": "Engineering", "hours": 38.00, "entries": 9 } ]
}
```
Drafts are excluded from every figure. `activeEmployees` counts active people with the EMPLOYEE role.

### `GET /api/work-logs/missing` (admin)
Query: `from`, `to` (default: the 7 days ending yesterday; at most 62 days), `employeeId`.
```json
[ { "employeeId": 9, "employeeName": "Kenji Watanabe", "employeeEmail": "kenji@example.com", "department": "Design",
    "missingCount": 2, "missingDates": ["2026-03-02", "2026-03-03"] } ]
```
Only active employees with the EMPLOYEE role are checked. A day counts as missing when it is a weekday on or after the person's joining date, up to **yesterday** (today and the future are never listed), has no submitted (non-draft) entry, and is not covered by **approved** leave. Public holidays are not modelled.

---

## Quick test with curl

```bash
# 1. get the CSRF cookie, 2. log in, 3. call an API with the session
curl -c jar.txt -s http://localhost:8080/api/auth/csrf
TOKEN=$(grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -b jar.txt -c jar.txt -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"email":"you@example.com","password":"your-admin-password"}'
curl -b jar.txt -s "http://localhost:8080/api/employees?size=5"
```

---

## Notifications (administrators only)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/notifications?status=&type=&page=&size=` | Delivery queue. Never returns subject or body. Fields: `id, type, recipientEmail, leaveRequestId, status (PENDING/PROCESSING/SENT/FAILED), attempts, createdAt, lastAttemptAt, sentAt, nextAttemptAt, lastError, channel` |
| GET | `/api/notifications/summary` | Counts per status |
| GET | `/api/notifications/status` | Admin. Mail mode and health: `mode, realDelivery, host, port, usernameSet, passwordSet, from, baseUrl, workerEnabled, warnings[]`. Never contains credentials. |
| POST | `/api/notifications/{id}/retry` | Re-queues a `FAILED` message (fresh attempt budget). 409 if it is not `FAILED`, 404 unknown id, 403 for employees. Needs the CSRF header. Audited. |

## Audit log (administrators only, read-only)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/audit?from=&to=&actorId=&action=&entityType=&entityId=&outcome=&page=&size=` | Newest first. `to` is inclusive. 400 for a reversed range or unknown action. Responses carry an `X-Request-Id` header that matches `correlationId` in the rows. |
| GET | `/api/audit/actions` | The allowed `action` values |

There are no POST / PUT / PATCH / DELETE endpoints for audit data.

## Dashboard analytics

| Method | Path | Notes |
|---|---|---|
| GET | `/api/dashboard/employee/analytics?from=&to=` | Signed-in user only. `reportedHours, entryCount, daysReported, completedTasks, inProgressTasks, tasksByStatus, hoursByDay, recentEntries`. Drafts excluded from totals. |
| GET | `/api/dashboard/admin/analytics?from=&to=&day=` | Admin only. Totals, `tasksByStatus` (no DRAFT), zero-filled daily `trend` (`hours, entries, employeesReporting, coveragePercent, weekend`), `selectedDay`, `averageCoveragePercent`, recent reports and leave activity. |

Period rules: default last 30 days ending today, `to` not before `from`, at most 366 days (otherwise 400).

## Export

| Method | Path | Notes |
|---|---|---|
| GET | `/api/work-logs/export?format=csv\|xlsx&employeeId=&department=&status=&from=&to=&q=` | Admin only. Submitted reports only. Attachment `worktrack-work-reports-<UTC timestamp>.csv/.xlsx`. 400 for an unknown format/status or bad range, or when over `EXPORT_MAX_ROWS`. |
| GET | `/api/work-logs/my/export?format=&status=&from=&to=&q=` | The caller's own reports (including their drafts). |
