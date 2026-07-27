package com.luky.nexusmind.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphSchemaInitializerTest {

    @Test
    void upgradesOnlyUndersizedEvidenceTextColumns() {
        assertTrue(KnowledgeGraphSchemaInitializer.requiresEvidenceTextUpgrade("tinytext"));
        assertTrue(KnowledgeGraphSchemaInitializer.requiresEvidenceTextUpgrade("VARCHAR"));
        assertFalse(KnowledgeGraphSchemaInitializer.requiresEvidenceTextUpgrade("text"));
        assertFalse(KnowledgeGraphSchemaInitializer.requiresEvidenceTextUpgrade("mediumtext"));
        assertFalse(KnowledgeGraphSchemaInitializer.requiresEvidenceTextUpgrade("longtext"));
    }
}
