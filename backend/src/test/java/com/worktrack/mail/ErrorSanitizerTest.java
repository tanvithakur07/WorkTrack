package com.worktrack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorSanitizerTest {

    @Test
    @DisplayName("configured secrets, e-mail addresses and password assignments never survive")
    void removesSecrets() {
        Exception error = new RuntimeException("535 auth failed for smtp-user with s3cretPass; rcpt jane.doe@example.com password=hunter2");
        String text = ErrorSanitizer.sanitize(error, "smtp-user", "s3cretPass");
        assertThat(text).doesNotContain("smtp-user").doesNotContain("s3cretPass").doesNotContain("jane.doe")
                .doesNotContain("hunter2").contains("RuntimeException").contains("[redacted]").contains("[address]");
    }

    @Test
    @DisplayName("the root cause is mentioned, so the administrator can see why")
    void includesRootCause() {
        Exception error = new RuntimeException("send failed", new java.net.ConnectException("Connection refused"));
        assertThat(ErrorSanitizer.sanitize(error)).contains("send failed").contains("ConnectException").contains("Connection refused");
    }

    @Test
    @DisplayName("control characters are removed and the text is limited in length")
    void cleansAndTruncates() {
        String text = ErrorSanitizer.sanitize(new IllegalStateException("line1\r\nline2\u0000" + "x".repeat(2000)));
        assertThat(text).doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000");
        assertThat(text.length()).isLessThanOrEqualTo(ErrorSanitizer.MAX_LENGTH + 3);
    }

    @Test
    @DisplayName("very short or blank secrets are ignored instead of blanking the whole message")
    void ignoresTinySecrets() {
        assertThat(ErrorSanitizer.clean("connection to a failed", "a", "", null)).isEqualTo("connection to a failed");
    }

    @Test
    @DisplayName("null input is harmless")
    void nullSafe() {
        assertThat(ErrorSanitizer.sanitize(null)).isEqualTo("Unknown error");
        assertThat(ErrorSanitizer.clean(null)).isEmpty();
    }
}
