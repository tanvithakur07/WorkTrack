package com.worktrack.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpreadsheetSafeTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1+1", "-2+3", "@SUM(A1:A9)", "=HYPERLINK(\"http://evil\",\"click\")",
            "=cmd|' /C calc'!A0", "\t=1+1", "\r=1+1", "\n=1+1", "  =1+1", " @SUM(1)", "＝1+1"})
    @DisplayName("text that a spreadsheet could read as a formula gets a leading quote")
    void formulaStartsAreNeutralised(String dangerous) {
        assertThat(SpreadsheetSafe.neutralize(dangerous)).startsWith("'").endsWith(dangerous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fix login bug", "a=b", "x+y", "Done - mostly", "email me @ noon", "100%", "'already quoted", ""})
    @DisplayName("ordinary text is left exactly as it is")
    void ordinaryTextIsUntouched(String text) {
        assertThat(SpreadsheetSafe.neutralize(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null becomes an empty string")
    void nullIsEmpty() {
        assertThat(SpreadsheetSafe.neutralize(null)).isEmpty();
    }
}
