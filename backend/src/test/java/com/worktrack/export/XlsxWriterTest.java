package com.worktrack.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class XlsxWriterTest {

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static byte[] build(List<?>... rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "Work reports", List.of("Name", "Date", "Hours"), new int[]{20, 12, 8})) {
            for (List<?> row : rows) {
                xlsx.writeRow(row);
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("produces a zip with every part an xlsx file needs")
    void hasAllParts() throws IOException {
        Map<String, String> parts = unzip(build(Arrays.asList("Ada", LocalDate.of(2026, 3, 4), new BigDecimal("3.50"))));
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("<autoFilter ref=\"A1:C2\"/>").contains("state=\"frozen\"");
    }

    @Test
    @DisplayName("text is always an inline string cell; there is never a formula element, even for =formula text")
    void neverWritesFormulas() throws IOException {
        String sheet = unzip(build(Arrays.asList("=HYPERLINK(\"http://evil\",\"x\")", LocalDate.of(2026, 3, 4),
                new BigDecimal("1.00")))).get("xl/worksheets/sheet1.xml");
        assertThat(sheet).doesNotContain("<f>").doesNotContain("<f ").doesNotContain("t=\"str\"");
        assertThat(sheet).contains("t=\"inlineStr\"").contains("&apos;=HYPERLINK(&quot;http://evil&quot;,&quot;x&quot;)");
    }

    @Test
    @DisplayName("typed cells: dates and numbers are numeric serials with a style, not text")
    void typedCells() throws IOException {
        String sheet = unzip(build(Arrays.asList("Ada", LocalDate.of(2026, 3, 4), new BigDecimal("3.50")))).get("xl/worksheets/sheet1.xml");
        // 2026-03-04 is serial 46085 in the 1900 date system (epoch day 20516 + 25569)
        assertThat(sheet).contains("<c r=\"B2\" s=\"2\"><v>46085</v></c>").contains("<c r=\"C2\" s=\"4\"><v>3.50</v></c>");
    }

    @Test
    @DisplayName("instants become date-time serials in UTC")
    void instantCells() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter xlsx = new XlsxWriter(out, "S", List.of("When"), new int[]{20})) {
            xlsx.writeRow(List.of(Instant.parse("2026-03-04T12:00:00Z")));
        }
        String sheet = unzip(out.toByteArray()).get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("<c r=\"A2\" s=\"3\"><v>46085.5000000");
    }

    @Test
    @DisplayName("characters that cannot appear in XML are removed and special characters are escaped")
    void xmlSafety() throws IOException {
        String sheet = unzip(build(Arrays.asList("A<B>&\u0001\u0008 end", LocalDate.of(2026, 3, 4), new BigDecimal("1")))).get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("A&lt;B&gt;&amp; end").doesNotContain("\u0001");
    }

    @Test
    @DisplayName("text longer than Excel's cell limit is cut")
    void longTextIsCut() throws IOException {
        String sheet = unzip(build(Arrays.asList("x".repeat(40_000), LocalDate.of(2026, 3, 4), new BigDecimal("1")))).get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("x".repeat(XlsxWriter.MAX_CELL_CHARS)).doesNotContain("x".repeat(XlsxWriter.MAX_CELL_CHARS + 1));
    }

    @Test
    @DisplayName("a row with the wrong number of cells is refused")
    void wrongCellCount() {
        assertThatThrownBy(() -> build(List.of("only one"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("column names run A..Z, AA, AB ...")
    void columnNames() {
        assertThat(XlsxWriter.columnName(0)).isEqualTo("A");
        assertThat(XlsxWriter.columnName(25)).isEqualTo("Z");
        assertThat(XlsxWriter.columnName(26)).isEqualTo("AA");
        assertThat(XlsxWriter.columnName(27)).isEqualTo("AB");
    }

    @Test
    @DisplayName("an export with no data rows is still a valid workbook with a header")
    void headerOnly() throws IOException {
        Map<String, String> parts = unzip(build());
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("Name").contains("<autoFilter ref=\"A1:C1\"/>");
    }
}
