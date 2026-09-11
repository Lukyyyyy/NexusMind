package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

@Service
public class MinerUParseClient {

    @org.springframework.beans.factory.annotation.Autowired
    private ParsedAssetService parsedAssetService;

    private static final java.util.concurrent.ExecutorService REQUESTS = new java.util.concurrent.ThreadPoolExecutor(
            0, 8, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
            runnable -> { Thread thread = new Thread(runnable, "mineru-request"); thread.setDaemon(true); return thread; });
    private final RestTemplate restTemplate = new RestTemplate(
            new org.springframework.http.client.JdkClientHttpRequestFactory());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.parsing.mineru.base-url:}")
    private String baseUrl;

    @Value("${file.parsing.mineru.parse-path:/file_parse}")
    private String parsePath;

    @Value("${file.parsing.mineru.parse-method:auto}")
    private String parseMethod;

    @Value("${file.parsing.mineru.backend:hybrid-auto-engine}")
    private String parseBackend;

    @Value("${file.parsing.mineru.ocr:false}")
    private boolean ocr;

    @Value("${file.parsing.mineru.enable-table:false}")
    private boolean enableTable;

    @Value("${file.parsing.mineru.enable-formula:false}")
    private boolean enableFormula;

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public String parseToText(byte[] fileBytes, String fileName) throws IOException {
        return parse(fileBytes, fileName, null);
    }

    public String parseDocument(byte[] fileBytes, String fileName, String fileMd5) throws IOException {
        return parse(fileBytes, fileName, fileMd5);
    }

    private String parse(byte[] fileBytes, String fileName, String fileMd5) throws IOException {
        if (!isEnabled()) {
            throw new IllegalStateException("MinerU parse service is not configured");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new NamedByteArrayResource(fileBytes, fileName));
        body.add("backend", resolveBackend());
        body.add("parse_method", resolveParseMethod());
        body.add("lang_list", "ch");
        body.add("formula_enable", String.valueOf(enableFormula));
        body.add("table_enable", String.valueOf(enableTable));
        body.add("return_md", "true");
        body.add("return_images", String.valueOf(fileMd5 != null));
        body.add("response_format_zip", "false");

        ResponseEntity<String> response;
        try {
            response = FileTaskControl.await(REQUESTS.submit(() -> restTemplate.postForEntity(
                    normalizeBaseUrl() + parsePath,
                    new HttpEntity<>(body, headers),
                    String.class
            )));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 507) {
                throw new MinerUOutOfMemoryException("MinerU解析超出可用内存" + formatErrorBody(e.getResponseBodyAsString()), e);
            }
            throw new IOException("MinerU parse service returned " + e.getStatusCode() + formatErrorBody(e.getResponseBodyAsString()), e);
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IOException("MinerU parse service returned " + response.getStatusCode());
        }

        JsonNode result = objectMapper.readTree(response.getBody());
        String markdown = extractText(result);
        return fileMd5 == null ? markdown
                : parsedAssetService.persist(fileMd5, markdown, result);
    }

    private String resolveBackend() {
        if (parseBackend != null && !parseBackend.isBlank()) {
            return parseBackend.trim();
        }
        return "hybrid-auto-engine";
    }

    private String resolveParseMethod() {
        if (parseMethod != null && !parseMethod.isBlank()) {
            return parseMethod.trim();
        }
        return ocr ? "ocr" : "auto";
    }

    private String normalizeBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String formatErrorBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 1000) {
            normalized = normalized.substring(0, 1000) + "...";
        }
        return ": " + normalized;
    }

    private String extractText(JsonNode root) throws IOException {
        return findTextContent(root)
                .orElseThrow(() -> new IOException(
                        "MinerU parse response does not contain markdown/text content. topLevelFields=" + topLevelFields(root)
                ));
    }

    private Optional<String> findTextContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        String[] candidates = {"md_content", "markdown", "md", "text", "content"};
        if (node.isObject()) {
            for (String candidate : candidates) {
                JsonNode candidateNode = node.get(candidate);
                if (candidateNode != null && candidateNode.isTextual() && !candidateNode.asText().isBlank()) {
                    return Optional.of(candidateNode.asText());
                }
            }

            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                Optional<String> nested = findTextContent(values.next());
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> nested = findTextContent(item);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }

        return Optional.empty();
    }

    private String topLevelFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "<non-object>";
        }

        StringBuilder builder = new StringBuilder();
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(fields.next());
        }
        return builder.toString();
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    public static class MinerUOutOfMemoryException extends IOException {
        public MinerUOutOfMemoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
