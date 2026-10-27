package com.worktrack.export;

/**
 * Defence against spreadsheet formula injection ("CSV injection").
 *
 * Text typed by users (task titles, descriptions, names) ends up in files that people open in Excel,
 * LibreOffice or Google Sheets. A cell whose text starts with '=', '+', '-' or '@' (or with a tab or
 * carriage return) can be interpreted as a formula, for example =HYPERLINK(...) or =cmd|' /C calc'!A0.
 * Following the OWASP guidance, such text is prefixed with a single quote, which spreadsheet programs
 * treat as "this is plain text" and do not display.
 *
 * The first character that is not whitespace is checked as well, so " =1+1" and " @SUM(A1)" are
 * neutralised too. The cost: a description that genuinely starts with "-" (for example "- fixed login")
 * is exported with a leading quote. That is deliberate and documented in the README.
 *
 * Numbers and dates are written as typed cells (never as text), so they are not affected.
 */
public final class SpreadsheetSafe {

    private SpreadsheetSafe() {
    }

    public static String neutralize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n' || isFormulaStart(first)) {
            return "'" + text;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            return isFormulaStart(c) ? "'" + text : text;
        }
        return text;
    }

    private static boolean isFormulaStart(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@'
                // full-width variants that some spreadsheet programs also treat as operators
                || c == '＝' || c == '＋' || c == '－' || c == '＠';
    }
}
