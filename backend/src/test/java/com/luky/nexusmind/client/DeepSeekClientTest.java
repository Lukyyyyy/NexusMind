package com.luky.nexusmind.client;

import com.luky.nexusmind.config.AiProperties;
import com.luky.nexusmind.service.AiTraceService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void streamingRequestAsksDeepSeekToReturnUsageChunk() throws Exception {
        DeepSeekClient client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "",
                "deepseek-chat",
                new AiProperties(),
                new AiTraceService(false, "", "", "", "test", false));

        Method buildRequest = DeepSeekClient.class.getDeclaredMethod(
                "buildRequest",
                String.class,
                String.class,
                List.class);
        buildRequest.setAccessible(true);

        Map<String, Object> request = (Map<String, Object>) buildRequest.invoke(
                client,
                "你好",
                "参考内容",
                List.of());

        assertEquals(true, request.get("stream"));
        assertTrue(request.get("stream_options") instanceof Map);
        Map<String, Object> streamOptions = (Map<String, Object>) request.get("stream_options");
        assertEquals(true, streamOptions.get("include_usage"));
    }
}
