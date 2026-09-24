package com.luky.nexusmind.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptLoaderTest {
    @Test
    void loadsBothSystemPromptsFromClasspath() {
        assertTrue(PromptLoader.load("agent-system.md").contains("不得凭空枚举未提及的内容类别"));
        assertTrue(PromptLoader.load("knowledge-graph-extraction-system.md")
                .contains("只输出 valueScore 不低于 0.6 的关系"));
        assertThrows(IllegalStateException.class, () -> PromptLoader.load("missing.md"));
    }
}
