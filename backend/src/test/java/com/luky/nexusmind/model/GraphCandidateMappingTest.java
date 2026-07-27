package com.luky.nexusmind.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphCandidateMappingTest {

    @Test
    void evidenceTextUsesTextColumnInsteadOfDialectDependentLob() throws Exception {
        Column column = GraphCandidate.class.getDeclaredField("evidenceText").getAnnotation(Column.class);

        assertEquals("TEXT", column.columnDefinition());
    }
}
