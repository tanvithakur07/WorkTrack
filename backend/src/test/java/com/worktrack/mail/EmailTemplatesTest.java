package com.worktrack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.worktrack.entity.NotificationType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {

    private static EmailTemplates.LeaveEmail model(String reason, String reviewer, String rejection) {
        return new EmailTemplates.LeaveEmail("WorkTrack", "Priya Nair", "Casual leave", LocalDate.of(2026, 3, 9),
                LocalDate.of(2026, 3, 11), 3, reason, reviewer, rejection);
    }

    @Test
    @DisplayName("the submission e-mail names the employee, the dates and the length, in HTML and plain text")
    void submitted() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_SUBMITTED, model("Family event", null, null));
        assertThat(mail.subject()).isEqualTo("Leave request received: 9 Mar - 11 Mar");
        for (String body : new String[]{mail.html(), mail.text()}) {
            assertThat(body).contains("Priya Nair").contains("Casual leave").contains("Mon 9 Mar 2026").contains("Wed 11 Mar 2026")
                    .contains("3 working days").contains("Family event");
        }
        assertThat(mail.html()).contains("<html").contains("We received your leave request");
        assertThat(mail.text()).doesNotContain("<").contains("Hello Priya Nair,");
    }

    @Test
    @DisplayName("the approval e-mail says approved and names the reviewer")
    void approved() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_APPROVED, model("Family event", "Ada Admin", null));
        assertThat(mail.subject()).startsWith("Leave request approved");
        assertThat(mail.text()).contains("approved").contains("Decided by: Ada Admin");
        assertThat(mail.html()).contains("Decided by").contains("Ada Admin");
    }

    @Test
    @DisplayName("the rejection e-mail includes the rejection reason when there is one, and none when there is not")
    void rejected() {
        EmailTemplates.Rendered with = EmailTemplates.render(NotificationType.LEAVE_REJECTED, model("Trip", "Ada Admin", "Team is short-staffed"));
        assertThat(with.subject()).startsWith("Leave request not approved");
        assertThat(with.text()).contains("Reason given: Team is short-staffed");
        assertThat(with.html()).contains("Team is short-staffed");

        EmailTemplates.Rendered without = EmailTemplates.render(NotificationType.LEAVE_REJECTED, model("Trip", "Ada Admin", null));
        assertThat(without.text()).doesNotContain("Reason given");
    }

    @Test
    @DisplayName("a single-day request shows one date, and one day is not pluralised")
    void singleDay() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_SUBMITTED,
                new EmailTemplates.LeaveEmail("WorkTrack", "Priya", "Sick leave", LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 9), 1, "Flu", null, null));
        assertThat(mail.subject()).isEqualTo("Leave request received: 9 Mar");
        assertThat(mail.text()).contains("1 working day").doesNotContain("1 working days").doesNotContain(" to Mon");
    }

    @Test
    @DisplayName("user-typed text is HTML-escaped, so markup in a reason is shown, never executed")
    void htmlIsEscaped() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_REJECTED,
                model("<script>alert(1)</script> & \"quotes\"", "<b>Boss</b>", "<img src=x onerror=alert(2)>"));
        assertThat(mail.html()).doesNotContain("<script>").doesNotContain("<b>Boss").doesNotContain("<img src=x")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;").contains("&lt;img src=x onerror=alert(2)&gt;");
    }

    @Test
    @DisplayName("a line break typed into a field cannot start a fake line of the plain-text e-mail")
    void textLinesAreCollapsed() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_SUBMITTED, model("one\ntwo\r\nthree", null, null));
        assertThat(mail.text()).contains("Reason:  one two three");
    }

    @Test
    @DisplayName("subjects never contain text typed by a person")
    void subjectHasNoUserText() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_SUBMITTED, model("Bcc: attacker@evil.example", null, null));
        assertThat(mail.subject()).doesNotContain("attacker").doesNotContain("\n");
    }

    private static EmailTemplates.LeaveEmail adminModel() {
        return new EmailTemplates.LeaveEmail("WorkTrack", "Priya Nair", "Casual leave", LocalDate.of(2026, 3, 9),
                LocalDate.of(2026, 3, 11), 3, "Family event", null, null, "Ada Admin", "EMP-0012", "https://wt.example/leaves.html");
    }

    @Test
    @DisplayName("the administrator's submission e-mail names the employee and code, the dates, the length, the reason and a link")
    void submittedForAdministrator() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_SUBMITTED, adminModel());

        assertThat(mail.subject()).isEqualTo("New leave request awaiting a decision: 9 Mar - 11 Mar");
        for (String body : new String[]{mail.html(), mail.text()}) {
            assertThat(body).contains("Priya Nair").contains("EMP-0012").contains("Casual leave").contains("Mon 9 Mar 2026")
                    .contains("Wed 11 Mar 2026").contains("3 working days").contains("Family event").contains("https://wt.example/leaves.html");
        }
        assertThat(mail.text()).contains("Hello Ada Admin,").doesNotContain("We received your leave request");
        assertThat(mail.html()).contains("href=\"https://wt.example/leaves.html\"");
    }

    @Test
    @DisplayName("the cancellation e-mail for an administrator says who cancelled; the subject has no typed text")
    void cancelledForAdministrator() {
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.LEAVE_CANCELLED, adminModel());
        assertThat(mail.subject()).isEqualTo("Leave request cancelled: 9 Mar - 11 Mar");
        assertThat(mail.text()).contains("Priya Nair cancelled a leave request");
    }

    @Test
    @DisplayName("a general e-mail escapes typed text in HTML, keeps it as typed (no markup) in text, flattens line breaks and shows the action address as plain text too")
    void generalEmail() {
        EmailTemplates.Rendered mail = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail("WorkTrack", "<b>Sam</b>",
                "Subject", "Headline", "Intro\nline", java.util.List.of(new EmailTemplates.Row("Changed", "name <script>")),
                "Open it", "https://wt.example/x#token=abc", "Closing", "#0f766e"));

        assertThat(mail.html()).doesNotContain("<b>Sam").doesNotContain("<script>").contains("&lt;b&gt;Sam&lt;/b&gt;")
                .contains("href=\"https://wt.example/x#token=abc\"");
        // The plain-text part is not HTML: typed text is shown as typed, and the template adds no markup of its own.
        assertThat(mail.text()).contains("Intro line").contains("https://wt.example/x#token=abc")
                .contains("<b>Sam</b>").doesNotContain("&lt;").doesNotContain("<a ").doesNotContain("<p");
        assertThat(mail.subject()).isEqualTo("Subject");
    }
}