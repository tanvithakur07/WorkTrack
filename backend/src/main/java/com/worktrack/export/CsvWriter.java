package com.worktrack.export;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Minimal RFC 4180 CSV writer: comma separated, CRLF line ends, fields containing a comma, quote or
 * line break are quoted with embedded quotes doubled. Text is passed through {@link SpreadsheetSafe}
 * first (formula injection). Numbers use a plain "." decimal point and dates are ISO-8601, so the file
 * means the same thing in every locale. A UTF-8 byte-order mark is written so Excel detects the encoding.
 */
public final class CsvWriter implements AutoCloseable {

    private final Writer out;
    private boolean started;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    public void writeRow(List<?> cells) throws IOException {
        if (!started) {
            out.write('﻿');
            started = true;
        }
        boolean first = true;
        for (Object cell : cells) {
            if (!first) {
                out.write(',');
            }
            first = false;
            out.write(escape(render(cell)));
        }
        out.write("\r\n");
    }

    static String render(Object cell) {
        if (cell == null) {
            return "";
        }
        if (cell instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (cell instanceof Number number) {
            return number.toString();
        }
        if (cell instanceof LocalDate date) {
            return date.toString();
        }
        if (cell instanceof Instant instant) {
            return instant.toString();
        }
        return SpreadsheetSafe.neutralize(cell.toString());
    }

    static String escape(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (!started) {
            out.write('﻿');
            started = true;
        }
        out.flush();
    }
}
