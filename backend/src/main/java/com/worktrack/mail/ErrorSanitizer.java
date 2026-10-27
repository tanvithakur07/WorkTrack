package com.worktrack.mail;

import java.util.regex.Pattern;

/**
 * Turns an exception into a short string that is safe to store and show to an administrator.
 *
 * SMTP errors are chatty: they can echo the user name, the recipient address, the host, even a fragment
 * of a login exchange. This keeps the exception class and a trimmed message, then removes configured
 * secrets, e-mail addresses, anything shaped like "password=...", and control characters. It is a safety
 * net, not a guarantee: never put real credentials into messages in the first place.
 */
public final class ErrorSanitizer {

    static final int MAX_LENGTH = 400;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+");
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|auth)\\s*[=:]\\s*\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^ ]]+");

    private ErrorSanitizer() {
    }

    public static String sanitize(Throwable error, String... secrets) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            text += ": " + error.getMessage();
        }
        if (root != error) {
            text += " (caused by " + root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : ": " + root.getMessage()) + ")";
        }
        return clean(text, secrets);
    }

    public static String clean(String text, String... secrets) {
        String result = text == null ? "" : text;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && secret.length() >= 3) {
                    result = result.replace(secret, "[redacted]");
                }
            }
        }
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=[redacted]");
        result = EMAIL.matcher(result).replaceAll("[address]");
        result = CONTROL.matcher(result).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) + "..." : result;
    }
}
