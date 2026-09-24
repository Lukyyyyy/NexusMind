package com.luky.nexusmind.utils;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PromptLoader {
    private PromptLoader() {}

    public static String load(String name) {
        try {
            return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载系统提示词: " + name, e);
        }
    }
}
