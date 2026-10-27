package com.worktrack.mail;

/** One message ready to hand to an SMTP server: both an HTML and a plain-text version. */
public record OutboundEmail(String to, String subject, String html, String text) {
}
