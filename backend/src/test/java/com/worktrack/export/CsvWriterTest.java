package com.worktrack.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    private static String write(List<List<?>> rows) throws IOException {
        StringWriter out = new StringWriter();
        try (CsvWriter csv = new CsvWriter(out)) {
            for (List<?> row : rows) {
                csv.writeRow(row);
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("starts with a UTF-8 byte-order mark and ends rows with CRLF")
    void bomAndLineEnds() throws IOException {
        String csv = write(List.of(List.of("a", "b")));
        assertThat(csv).isEqualTo("﻿a,b\r\n");
    }

    @Test
    @DisplayName("commas, quotes and line breaks are quoted; quotes are doubled")
    void quoting() throws IOException {
        String csv = write(List.of(List.of("Smith, John", "say \"hi\"", "two\nlines")));
        assertThat(csv).isEqualTo("﻿\"Smith, John\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n");
    }

    @Test
    @DisplayName("numbers, dates and instants are written locale-independently; null is empty")
    void typedValues() throws IOException {
        String csv = write(List.of(Arrays.asList(new BigDecimal("3.50"), LocalDate.of(2026, 3, 4),
                Instant.parse("2026-03-04T09:30:15Z"), null, 42L)));
        assertThat(csv).isEqualTo("﻿3.50,2026-03-04,2026-03-04T09:30:15Z,,42\r\n");
    }

    @Test
    @DisplayName("formula-looking text is neutralised before quoting")
    void formulaInjection() throws IOException {
        String csv = write(List.of(List.of("=HYPERLINK(\"http://evil\",\"x\")", "@cmd", "-5+5")));
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"").contains(",'@cmd,").contains(",'-5+5");
        assertThat(csv).doesNotContain(",=").doesNotContain(",@cmd").doesNotContain("﻿=");
    }

    @Test
    @DisplayName("an empty file still carries the byte-order mark and nothing else")
    void emptyFile() throws IOException {
        assertThat(write(List.of())).isEqualTo("﻿");
    }
}
