package com.luky.nexusmind.service;

import com.luky.nexusmind.model.DocumentVector;
import com.luky.nexusmind.model.ParseEngine;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseServiceMinerURoutingTest {

    private ParseService parseService;
    private RecordingMinerUParseClient minerUParseClient;
    private RecordingFileProcessingStatusService processingStatusService;
    private List<DocumentVector> savedVectors;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        minerUParseClient = new RecordingMinerUParseClient();
        processingStatusService = new RecordingFileProcessingStatusService();
        savedVectors = new ArrayList<>();

        ReflectionTestUtils.setField(parseService, "documentVectorRepository", recordingDocumentVectorRepository(savedVectors));
        ReflectionTestUtils.setField(parseService, "aiTraceService",
                new AiTraceService(false, "", "", "", "test", false));
        ReflectionTestUtils.setField(parseService, "minerUParseClient", minerUParseClient);
        ReflectionTestUtils.setField(parseService, "processingStatusService", processingStatusService);
        ReflectionTestUtils.setField(parseService, "chunkSize", 1000);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 1);
        ReflectionTestUtils.setField(parseService, "maxChunkSize", 1000);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 1048576);
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.95);
        ReflectionTestUtils.setField(parseService, "minerUFallbackToTika", true);
    }

    @Test
    void explicitMinerURequestFallsBackToTikaForMarkdown() throws Exception {
        byte[] markdown = "# Title\n\nMarkdown content".getBytes(StandardCharsets.UTF_8);

        parseService.parseAndSave("md5", new ByteArrayInputStream(markdown),
                "user", "org", true, ParseEngine.MINERU, "note.md");

        assertFalse(minerUParseClient.parseToTextCalled);
        assertEquals(ParseEngine.TIKA, processingStatusService.actualParseEngine);
    }

    @Test
    void customChunkSizeOverridesDefaultWhenParsing() throws Exception {
        String content = "这是第一段用于验证自定义切片大小的文本。"
                + "这是第二段用于验证解析时不会使用默认切片大小的文本。"
                + "这是第三段用于让内容超过二十个字符。";

        int parsedChunks = parseService.parseAndSave("md5-custom", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "user", "org", true, ParseEngine.TIKA, "note.txt", 20);

        assertTrue(parsedChunks > 1);
        assertFalse(savedVectors.isEmpty());
        assertTrue(savedVectors.stream().allMatch(vector -> vector.getTextContent().length() <= 20),
                "所有切片都应使用请求传入的20字符上限");
    }

    private DocumentVectorRepository recordingDocumentVectorRepository(List<DocumentVector> savedVectors) {
        return (DocumentVectorRepository) Proxy.newProxyInstance(
                DocumentVectorRepository.class.getClassLoader(),
                new Class<?>[]{DocumentVectorRepository.class},
                (proxy, method, args) -> {
                    if ("saveAll".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Iterable<DocumentVector> vectors = (Iterable<DocumentVector>) args[0];
                        vectors.forEach(savedVectors::add);
                        return args[0];
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static class RecordingMinerUParseClient extends MinerUParseClient {
        boolean parseToTextCalled;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String parseToText(byte[] fileBytes, String fileName) throws IOException {
            parseToTextCalled = true;
            throw new IOException("MinerU should not be called for markdown");
        }
    }

    private static class RecordingFileProcessingStatusService extends FileProcessingStatusService {
        ParseEngine actualParseEngine;

        RecordingFileProcessingStatusService() {
            super(null, null);
        }

        @Override
        public void markActualParseEngine(String fileMd5, String userId, ParseEngine actualParseEngine) {
            this.actualParseEngine = actualParseEngine;
        }
    }
}
