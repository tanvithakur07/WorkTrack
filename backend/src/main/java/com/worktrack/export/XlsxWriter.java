package com.worktrack.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small streaming writer for a single-sheet .xlsx file (Office Open XML), built on java.util.zip only,
 * so the project needs no extra dependency. Rows are written as they arrive: memory use does not grow
 * with the number of rows.
 *
 * Safety:
 * - Text is always written as an inline STRING cell (never as a formula element) and is also passed
 *   through {@link SpreadsheetSafe}, so nothing a user typed can run as a formula.
 * - Characters that are illegal in XML are removed, and a cell is cut at Excel's 32,767 character limit.
 *
 * Typed cells: numbers use a "0.00" style, dates a yyyy-mm-dd style and instants a yyyy-mm-dd hh:mm:ss
 * style (UTC). The first row is a bold, frozen header with a filter.
 */
public final class XlsxWriter implements AutoCloseable {

    /** Excel's limit for the text of one cell. */
    static final int MAX_CELL_CHARS = 32_767;

    private static final int STYLE_HEADER = 1;
    private static final int STYLE_DATE = 2;
    private static final int STYLE_DATETIME = 3;
    private static final int STYLE_NUMBER = 4;

    private final ZipOutputStream zip;
    private final int columnCount;
    private int rowNumber;
    private boolean finished;

    /**
     * @param columnWidths approximate widths in characters; its length is the number of columns
     */
    public XlsxWriter(OutputStream target, String sheetName, List<String> header, int[] columnWidths) throws IOException {
        if (header.size() != columnWidths.length) {
            throw new IllegalArgumentException("one width per column is required");
        }
        this.zip = new ZipOutputStream(target, StandardCharsets.UTF_8);
        this.columnCount = header.size();
        writePackageParts(sheetName);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" "
                + "activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                + "<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int i = 0; i < columnWidths.length; i++) {
            write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + columnWidths[i] + "\" customWidth=\"1\"/>");
        }
        write("</cols><sheetData>");
        writeRow(header.stream().map(HeaderCell::new).toList());
    }

    /** Marks a cell as part of the header row (bold). */
    private record HeaderCell(String text) {
    }

    public void writeRow(List<?> cells) throws IOException {
        if (cells.size() != columnCount) {
            throw new IllegalArgumentException("expected " + columnCount + " cells but got " + cells.size());
        }
        rowNumber++;
        StringBuilder row = new StringBuilder(columnCount * 24);
        row.append("<row r=\"").append(rowNumber).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            appendCell(row, columnName(i) + rowNumber, cells.get(i));
        }
        row.append("</row>");
        write(row.toString());
    }

    private void appendCell(StringBuilder row, String ref, Object value) {
        if (value == null) {
            return; // an empty cell is simply not written
        }
        if (value instanceof HeaderCell header) {
            appendText(row, ref, header.text(), STYLE_HEADER, false);
        } else if (value instanceof BigDecimal number) {
            row.append("<c r=\"").append(ref).append("\" s=\"").append(STYLE_NUMBER).append("\"><v>")
                    .append(number.toPlainString()).append("</v></c>");
        } else if (value instanceof Number number) {
            row.append("<c r=\"").append(ref).append("\"><v>").append(number).append("</v></c>");
        } else if (value instanceof LocalDate date) {
            row.append("<c r=\"").append(ref).append("\" s=\"").append(STYLE_DATE).append("\"><v>")
                    .append(date.toEpochDay() + 25569).append("</v></c>");
        } else if (value instanceof Instant instant) {
            // exact decimal arithmetic, plus a hair (about 0.2 ms) so no program truncates 09:30:15 to 09:30:14
            BigDecimal serial = BigDecimal.valueOf(instant.getEpochSecond())
                    .divide(BigDecimal.valueOf(86400), 12, java.math.RoundingMode.HALF_UP)
                    .add(BigDecimal.valueOf(25569)).add(new BigDecimal("0.000000002"));
            row.append("<c r=\"").append(ref).append("\" s=\"").append(STYLE_DATETIME).append("\"><v>")
                    .append(serial.toPlainString()).append("</v></c>");
        } else {
            appendText(row, ref, value.toString(), 0, true);
        }
    }

    private static void appendText(StringBuilder row, String ref, String text, int style, boolean neutralize) {
        String safe = cleanXml(neutralize ? SpreadsheetSafe.neutralize(text) : text);
        if (safe.length() > MAX_CELL_CHARS) {
            safe = safe.substring(0, MAX_CELL_CHARS);
        }
        row.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
        if (style != 0) {
            row.append(" s=\"").append(style).append('"');
        }
        row.append("><is><t xml:space=\"preserve\">").append(safe).append("</t></is></c>");
    }

    /** Removes characters that cannot appear in XML 1.0 and escapes the five special characters. */
    static String cleanXml(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean legal = cp == 0x9 || cp == 0xA || cp == 0xD
                    || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD)
                    || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!legal) {
                continue;
            }
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int n = index;
        do {
            name.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return name.toString();
    }

    private void write(String xml) throws IOException {
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
    }

    private void writePackageParts(String sheetName) throws IOException {
        String safeName = cleanXml(sheetName.replaceAll("[\\\\/?*\\[\\]:]", " ")).trim();
        if (safeName.isEmpty()) {
            safeName = "Sheet1";
        }
        if (safeName.length() > 31) {
            safeName = safeName.substring(0, 31);
        }
        part("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>");
        part("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        part("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + safeName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        part("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        // cellXfs: 0 default, 1 bold header, 2 date, 3 date-time, 4 number 0.00
        part("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss\"/></numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"5\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");
    }

    private void part(String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        write(xml);
        zip.closeEntry();
    }

    /** Finishes the sheet and the zip. Safe to call twice. */
    @Override
    public void close() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        write("</sheetData><autoFilter ref=\"A1:" + columnName(columnCount - 1) + Math.max(rowNumber, 1) + "\"/></worksheet>");
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }
}
