package com.worktrack.mail;

import com.worktrack.entity.NotificationType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Builds the three leave e-mails: a table-based HTML version (inline styles, the only thing mail clients
 * reliably support) and a readable plain-text version. Every value that came from a person is
 * HTML-escaped, so a reason such as "<script>" is shown as text. Subjects contain no user-typed text.
 */
public final class EmailTemplates {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private EmailTemplates() {
    }

    /**
     * Everything a leave e-mail may mention. reviewerName / rejectionReason are null when not applicable.
     * recipientName is null when the e-mail goes to the employee the request belongs to; when it is set the
     * recipient is somebody else (an administrator) and the wording changes to "X submitted a request".
     * employeeCode and link are optional (link = where to open the request in WorkTrack).
     */
    public record LeaveEmail(String appName, String employeeName, String leaveTypeLabel, LocalDate startDate,
                             LocalDate endDate, int days, String reason, String reviewerName, String rejectionReason,
                             String recipientName, String employeeCode, String link) {

        public LeaveEmail(String appName, String employeeName, String leaveTypeLabel, LocalDate startDate,
                          LocalDate endDate, int days, String reason, String reviewerName, String rejectionReason) {
            this(appName, employeeName, leaveTypeLabel, startDate, endDate, days, reason, reviewerName, rejectionReason,
                    null, null, null);
        }

        boolean forOtherRecipient() {
            return recipientName != null;
        }
    }

    /** One label/value line in a general e-mail. */
    public record Row(String label, String value) {
    }

    /**
     * A non-leave e-mail (invitation, employee changes, security notices). subject must be fixed text, never
     * typed by a person. actionUrl/actionLabel are optional; accent is a CSS colour.
     */
    public record GeneralEmail(String appName, String recipientName, String subject, String headline, String intro,
                               List<Row> rows, String actionLabel, String actionUrl, String closing, String accent) {
    }

    public record Rendered(String subject, String html, String text) {
    }

    public static Rendered render(NotificationType type, LeaveEmail m) {
        String range = m.startDate().equals(m.endDate())
                ? DAY.format(m.startDate()) : DAY.format(m.startDate()) + " to " + DAY.format(m.endDate());
        String dayCount = m.days() + (m.days() == 1 ? " working day" : " working days");
        String headline;
        String intro;
        String subject;
        String accent;
        switch (type) {
            case LEAVE_SUBMITTED -> {
                accent = "#0f766e";
                if (m.forOtherRecipient()) {
                    subject = "New leave request awaiting a decision: " + shortRange(m);
                    headline = "A leave request needs a decision";
                    intro = m.employeeName() + " submitted a leave request. Please approve or reject it in " + m.appName() + ".";
                } else {
                    subject = "Leave request received: " + shortRange(m);
                    headline = "We received your leave request";
                    intro = "Your request has been sent to an administrator for a decision. You will get another e-mail as soon as it has been decided.";
                }
            }
            case LEAVE_CANCELLED -> {
                subject = "Leave request cancelled: " + shortRange(m);
                headline = "A leave request was cancelled";
                intro = m.forOtherRecipient()
                        ? m.employeeName() + " cancelled a leave request. No decision is needed."
                        : "Your leave request was cancelled.";
                accent = "#92400e";
            }
            case LEAVE_APPROVED -> {
                subject = "Leave request approved: " + shortRange(m);
                headline = "Your leave request was approved";
                intro = "Good news: your leave has been approved.";
                accent = "#15803d";
            }
            case LEAVE_REJECTED -> {
                subject = "Leave request not approved: " + shortRange(m);
                headline = "Your leave request was not approved";
                intro = "Unfortunately your leave request could not be approved.";
                accent = "#b91c1c";
            }
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        }

        String greetingName = m.forOtherRecipient() ? m.recipientName() : m.employeeName();
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(oneLine(greetingName)).append(",\n\n")
                .append(oneLine(intro)).append("\n\n");
        if (m.forOtherRecipient()) {
            text.append("Employee: ").append(oneLine(m.employeeName()))
                    .append(m.employeeCode() == null ? "" : " (" + oneLine(m.employeeCode()) + ")").append('\n');
        }
        text.append("Type:    ").append(m.leaveTypeLabel()).append('\n')
                .append("Dates:   ").append(range).append('\n')
                .append("Length:  ").append(dayCount).append('\n')
                .append("Reason:  ").append(oneLine(m.reason())).append('\n');
        if (type != NotificationType.LEAVE_SUBMITTED && m.reviewerName() != null) {
            text.append("Decided by: ").append(oneLine(m.reviewerName())).append('\n');
        }
        if (type == NotificationType.LEAVE_REJECTED && m.rejectionReason() != null && !m.rejectionReason().isBlank()) {
            text.append("Reason given: ").append(oneLine(m.rejectionReason())).append('\n');
        }
        if (m.link() != null) {
            text.append("\nOpen it in ").append(m.appName()).append(": ").append(m.link()).append('\n');
        }
        text.append("\nThis is an automated message from ").append(m.appName())
                .append(". Please do not reply to it.\n");

        StringBuilder rows = new StringBuilder();
        if (m.forOtherRecipient()) {
            row(rows, "Employee", m.employeeName() + (m.employeeCode() == null ? "" : " (" + m.employeeCode() + ")"));
        }
        row(rows, "Type", m.leaveTypeLabel());
        row(rows, "Dates", range);
        row(rows, "Length", dayCount);
        row(rows, m.forOtherRecipient() ? "Reason" : "Your reason", m.reason());
        if (type != NotificationType.LEAVE_SUBMITTED && m.reviewerName() != null) {
            row(rows, "Decided by", m.reviewerName());
        }
        if (type == NotificationType.LEAVE_REJECTED && m.rejectionReason() != null && !m.rejectionReason().isBlank()) {
            row(rows, "Reason given", m.rejectionReason());
        }

        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#1f2937;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f6;padding:24px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border-radius:8px;overflow:hidden;\">"
                + "<tr><td style=\"background:" + accent + ";padding:18px 24px;color:#ffffff;font-size:18px;font-weight:bold;\">" + esc(m.appName()) + "</td></tr>"
                + "<tr><td style=\"padding:24px;\">"
                + "<h1 style=\"margin:0 0 12px;font-size:20px;color:" + accent + ";\">" + esc(headline) + "</h1>"
                + "<p style=\"margin:0 0 16px;line-height:1.5;\">Hello " + esc(greetingName) + ",</p>"
                + "<p style=\"margin:0 0 20px;line-height:1.5;\">" + esc(intro) + "</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-top:1px solid #e5e7eb;\">"
                + rows
                + "</table>"
                + button(m.link(), "View in " + m.appName(), accent)
                + "<p style=\"margin:24px 0 0;font-size:12px;color:#6b7280;line-height:1.5;\">This is an automated message from "
                + esc(m.appName()) + ". Please do not reply to it.</p>"
                + "</td></tr></table></td></tr></table></body></html>";
        return new Rendered(subject, html, text.toString());
    }

    /** Builds a non-leave e-mail. Same look, same escaping rules as the leave e-mails. */
    public static Rendered renderGeneral(GeneralEmail m) {
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(oneLine(m.recipientName())).append(",\n\n").append(oneLine(m.intro())).append("\n\n");
        StringBuilder rows = new StringBuilder();
        for (Row r : m.rows()) {
            text.append(r.label()).append(": ").append(oneLine(r.value())).append('\n');
            row(rows, r.label(), r.value());
        }
        if (m.actionUrl() != null) {
            text.append('\n').append(m.actionLabel()).append(":\n").append(m.actionUrl()).append('\n');
        }
        if (m.closing() != null) {
            text.append('\n').append(m.closing()).append('\n');
        }
        text.append("\nThis is an automated message from ").append(m.appName()).append(". Please do not reply to it.\n");

        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#1f2937;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f6;padding:24px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border-radius:8px;overflow:hidden;\">"
                + "<tr><td style=\"background:" + m.accent() + ";padding:18px 24px;color:#ffffff;font-size:18px;font-weight:bold;\">" + esc(m.appName()) + "</td></tr>"
                + "<tr><td style=\"padding:24px;\">"
                + "<h1 style=\"margin:0 0 12px;font-size:20px;color:" + m.accent() + ";\">" + esc(m.headline()) + "</h1>"
                + "<p style=\"margin:0 0 16px;line-height:1.5;\">Hello " + esc(m.recipientName()) + ",</p>"
                + "<p style=\"margin:0 0 20px;line-height:1.5;\">" + esc(m.intro()) + "</p>"
                + (m.rows().isEmpty() ? "" : "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-top:1px solid #e5e7eb;\">" + rows + "</table>")
                + button(m.actionUrl(), m.actionLabel(), m.accent())
                + (m.actionUrl() == null ? "" : "<p style=\"margin:12px 0 0;font-size:12px;color:#6b7280;line-height:1.5;word-break:break-all;\">If the button does not work, copy this address into your browser:<br>" + esc(m.actionUrl()) + "</p>")
                + (m.closing() == null ? "" : "<p style=\"margin:16px 0 0;line-height:1.5;font-size:14px;\">" + esc(m.closing()) + "</p>")
                + "<p style=\"margin:24px 0 0;font-size:12px;color:#6b7280;line-height:1.5;\">This is an automated message from "
                + esc(m.appName()) + ". Please do not reply to it.</p>"
                + "</td></tr></table></td></tr></table></body></html>";
        return new Rendered(m.subject(), html, text.toString());
    }

    private static String button(String url, String label, String accent) {
        if (url == null) {
            return "";
        }
        return "<p style=\"margin:24px 0 0;\"><a href=\"" + esc(url) + "\" style=\"display:inline-block;background:" + accent
                + ";color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-weight:bold;font-size:14px;\">"
                + esc(label) + "</a></p>";
    }

    private static String shortRange(LeaveEmail m) {
        DateTimeFormatter s = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
        return m.startDate().equals(m.endDate()) ? s.format(m.startDate())
                : s.format(m.startDate()) + " - " + s.format(m.endDate());
    }

    private static void row(StringBuilder rows, String label, String value) {
        rows.append("<tr><td style=\"padding:10px 12px 10px 0;border-bottom:1px solid #e5e7eb;color:#6b7280;width:130px;vertical-align:top;font-size:14px;\">")
                .append(esc(label))
                .append("</td><td style=\"padding:10px 0;border-bottom:1px solid #e5e7eb;font-size:14px;vertical-align:top;\">")
                .append(esc(value)).append("</td></tr>");
    }

    /** Collapses line breaks so one value cannot start a new "line" of the plain-text e-mail. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    public static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '\r' -> { /* dropped */ }
                case '\n' -> out.append("<br>");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
