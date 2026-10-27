# Changes: invitations, notification events and delivery diagnostics

Status: code written and partly checked in the sandbox. **Maven, Spring and MySQL were not available there, so none of the backend code or tests in this change has been compiled or run.** Run `mvn clean test` from `backend/` before relying on it.

## Fixed
- Login password eye button needed several clicks: `.btn:active { transform: translateY(1px) }` out-ranked `.input-group__btn { transform: translateY(-50%) }`, so the button moved on mouse-down and the click missed. Now positioned with `top/bottom/margin-block:auto`, with `transform: none` while pressed. Checked in headless Chromium (desktop and phone width).
- Leave e-mails "not arriving": default mail mode is `log` (nothing is sent) and submissions only notified the employee. Now administrators are notified, LOG mode is announced at startup and on the Email queue page.

## Added
- Migration `V6__invitations_and_notification_events.sql`: `account_tokens`, `notifications.subject_user_id` and an index.
- Invitation e-mail on employee creation, `POST /api/employees/{id}/invitation`, `POST /api/auth/setup-password`, page `setup-password.html`.
- Events: employee updated, deactivated, reactivated, security notices, leave cancelled; admin recipients for leave submitted.
- `GET /api/notifications/status`, Email queue banner and labels.
- Gmail App Password handling, sender fallback to `MAIL_USERNAME`, `.env.example` and README (Windows steps, test plan).

## Not implemented (no such feature in the app)
Daily-work notifications, review/return workflow, missing-report reminders, leave-modified notices.
