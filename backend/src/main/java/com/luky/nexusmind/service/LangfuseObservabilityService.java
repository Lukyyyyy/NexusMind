package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LangfuseObservabilityService {

    static final String OBSERVATION_FIELDS = "core,basic,model,usage,metrics,trace_context";

    private final boolean enabled;
    private final String baseUrl;
    private final String publicKey;
    private final String secretKey;
    private final LangfuseObservationClient client;

    @Autowired
    public LangfuseObservabilityService(@Value("${langfuse.tracing.enabled:false}") boolean tracingEnabled,
                                        @Value("${langfuse.tracing.base-url:https://cloud.langfuse.com}") String baseUrl,
                                        @Value("${langfuse.tracing.public-key:}") String publicKey,
                                        @Value("${langfuse.tracing.secret-key:}") String secretKey,
                                        ObjectMapper objectMapper) {
        this(tracingEnabled, baseUrl, publicKey, secretKey,
                new HttpLangfuseObservationClient(baseUrl, publicKey, secretKey, objectMapper));
    }

    LangfuseObservabilityService(boolean tracingEnabled,
                                 String baseUrl,
                                 String publicKey,
                                 String secretKey,
                                 LangfuseObservationClient client) {
        this.enabled = tracingEnabled && hasText(publicKey) && hasText(secretKey);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.publicKey = publicKey;
        this.secretKey = secretKey;
        this.client = client;
    }

    public OverviewResponse getOverview(String userId, Instant from, Instant to) {
        if (!isConfigured()) {
            return OverviewResponse.disabled("Langfuse 未配置或未启用");
        }

        LangfuseObservationPage page = client.fetchObservations(LangfuseObservationQuery.forUser(
                userId, from, to, null, null, null, 1000, OBSERVATION_FIELDS));
        List<LangfuseObservation> observations = page.data();
        Map<String, TraceAccumulator> traces = groupByTrace(observations);

        long totalLatencyMs = observations.stream()
                .map(LangfuseObservation::latencyMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        long latencyCount = observations.stream().filter(item -> item.latencyMs() != null).count();
        long avgLatencyMs = latencyCount == 0 ? 0 : totalLatencyMs / latencyCount;
        long errorCount = observations.stream().filter(LangfuseObservabilityService::isError).count();
        long totalTokens = observations.stream().mapToLong(item -> nullToZero(resolvedTotalUsage(item))).sum();
        double totalCost = observations.stream().mapToDouble(item -> nullToZero(resolvedTotalCost(item))).sum();

        List<ModelSummary> byModel = observations.stream()
                .filter(item -> hasText(resolvedModelName(item)))
                .collect(Collectors.groupingBy(LangfuseObservabilityService::resolvedModelName, LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> new ModelSummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().mapToLong(item -> nullToZero(resolvedTotalUsage(item))).sum(),
                        entry.getValue().stream().mapToDouble(item -> nullToZero(resolvedTotalCost(item))).sum()))
                .sorted(Comparator.comparing(ModelSummary::count).reversed())
                .toList();

        List<TrendPoint> trend = observations.stream()
                .collect(Collectors.groupingBy(item -> hourBucket(item.startTime()), LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> new TrendPoint(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().filter(LangfuseObservabilityService::isError).count(),
                        entry.getValue().stream().mapToLong(item -> nullToZero(resolvedTotalUsage(item))).sum()))
                .sorted(Comparator.comparing(TrendPoint::time))
                .toList();

        return new OverviewResponse(
                true,
                null,
                traces.size(),
                observations.size(),
                errorCount,
                avgLatencyMs,
                totalTokens,
                roundMoney(totalCost),
                byModel,
                trend);
    }

    public TraceListResponse getTraces(String userId,
                                       Instant from,
                                       Instant to,
                                       String level,
                                       String traceName,
                                       String cursor,
                                       int limit) {
        if (!isConfigured()) {
            return new TraceListResponse(false, "Langfuse 未配置或未启用", List.of(), null);
        }

        LangfuseObservationPage page = client.fetchObservations(LangfuseObservationQuery.forUser(
                userId, from, to, level, traceName, cursor, Math.max(1, Math.min(limit, 1000)), OBSERVATION_FIELDS));
        List<TraceListItem> items = groupByTrace(page.data()).values().stream()
                .map(TraceAccumulator::toListItem)
                .sorted(Comparator.comparing(TraceListItem::startTime).reversed())
                .toList();
        return new TraceListResponse(true, null, items, page.nextCursor());
    }

    public TraceDetailResponse getTraceDetail(String userId, String traceId, Instant from, Instant to) {
        if (!isConfigured()) {
            return new TraceDetailResponse(false, "Langfuse 未配置或未启用", traceId, null, null, List.of());
        }

        LangfuseObservationPage page = client.fetchObservations(LangfuseObservationQuery.forTrace(
                userId, traceId, from, to, 1000, OBSERVATION_FIELDS));
        List<ObservationView> observations = page.data().stream()
                .sorted(Comparator.comparing(LangfuseObservation::startTime))
                .map(ObservationView::from)
                .toList();
        String traceName = observations.stream()
                .map(ObservationView::traceName)
                .filter(LangfuseObservabilityService::hasText)
                .findFirst()
                .orElse(null);
        String sessionId = observations.stream()
                .map(ObservationView::sessionId)
                .filter(LangfuseObservabilityService::hasText)
                .findFirst()
                .orElse(null);
        return new TraceDetailResponse(true, null, traceId, traceName, sessionId, observations);
    }

    private boolean isConfigured() {
        return enabled && hasText(baseUrl) && hasText(publicKey) && hasText(secretKey);
    }

    private static Map<String, TraceAccumulator> groupByTrace(List<LangfuseObservation> observations) {
        Map<String, TraceAccumulator> traces = new LinkedHashMap<>();
        for (LangfuseObservation observation : observations) {
            traces.computeIfAbsent(observation.traceId(), TraceAccumulator::new).add(observation);
        }
        return traces;
    }

    private static boolean isError(LangfuseObservation observation) {
        return "ERROR".equalsIgnoreCase(observation.level());
    }

    private static String hourBucket(Instant instant) {
        Instant truncated = instant == null ? Instant.EPOCH : instant.truncatedTo(ChronoUnit.HOURS);
        return DateTimeFormatter.ISO_INSTANT.format(truncated);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0 : value;
    }

    private static double nullToZero(Double value) {
        return value == null ? 0 : value;
    }

    private static double roundMoney(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static String normalizeBaseUrl(String value) {
        if (!hasText(value)) {
            return "https://cloud.langfuse.com";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("/api/public")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/api/public".length());
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public interface LangfuseObservationClient {
        LangfuseObservationPage fetchObservations(LangfuseObservationQuery query);
    }

    public record LangfuseObservationQuery(String userId,
                                           String traceId,
                                           Instant from,
                                           Instant to,
                                           String level,
                                           String traceName,
                                           String cursor,
                                           int limit,
                                           String fields) {
        static LangfuseObservationQuery forUser(String userId,
                                                Instant from,
                                                Instant to,
                                                String level,
                                                String traceName,
                                                String cursor,
                                                int limit,
                                                String fields) {
            return new LangfuseObservationQuery(userId, null, from, to, level, traceName, cursor, limit, fields);
        }

        static LangfuseObservationQuery forTrace(String userId,
                                                 String traceId,
                                                 Instant from,
                                                 Instant to,
                                                 int limit,
                                                 String fields) {
            return new LangfuseObservationQuery(userId, traceId, from, to, null, null, null, limit, fields);
        }
    }

    public record LangfuseObservationPage(List<LangfuseObservation> data, String nextCursor) {
    }

    public record LangfuseObservation(String id,
                                      String traceId,
                                      Instant startTime,
                                      Instant endTime,
                                      String parentObservationId,
                                      String type,
                                      String name,
                                      String level,
                                      String statusMessage,
                                      String environment,
                                      String sessionId,
                                      String providedModelName,
                                      Long totalUsage,
                                      Double totalCost,
                                      Double latency,
                                      String traceName,
                                      Map<String, Object> metadata,
                                      String input,
                                      String output) {
        Long latencyMs() {
            if (latency == null) {
                return null;
            }
            return Math.round(latency * 1000);
        }
    }

    public record OverviewResponse(boolean enabled,
                                   String message,
                                   int totalTraces,
                                   int totalObservations,
                                   long errorCount,
                                   long avgLatencyMs,
                                   long totalTokens,
                                   double totalCost,
                                   List<ModelSummary> byModel,
                                   List<TrendPoint> trend) {
        static OverviewResponse disabled(String message) {
            return new OverviewResponse(false, message, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }
    }

    public record ModelSummary(String model, int count, long totalTokens, double totalCost) {
    }

    public record TrendPoint(String time, int count, long errorCount, long totalTokens) {
    }

    public record TraceListResponse(boolean enabled, String message, List<TraceListItem> items, String nextCursor) {
    }

    public record TraceListItem(String traceId,
                                String traceName,
                                Instant startTime,
                                Instant endTime,
                                long durationMs,
                                String level,
                                int observationCount,
                                List<String> modelNames,
                                long totalTokens,
                                double totalCost) {
    }

    public record TraceDetailResponse(boolean enabled,
                                      String message,
                                      String traceId,
                                      String traceName,
                                      String sessionId,
                                      List<ObservationView> observations) {
    }

    public record ObservationView(String id,
                                  String traceId,
                                  String parentObservationId,
                                  String type,
                                  String name,
                                  String level,
                                  String statusMessage,
                                  Instant startTime,
                                  Instant endTime,
                                  Long durationMs,
                                  String modelName,
                                  Long totalTokens,
                                  Double totalCost,
                                  String traceName,
                                  String sessionId,
                                  Map<String, Object> metadata,
                                  String input,
                                  String output) {
        static ObservationView from(LangfuseObservation observation) {
            return new ObservationView(
                    observation.id(),
                    observation.traceId(),
                    observation.parentObservationId(),
                    observation.type(),
                    observation.name(),
                    observation.level(),
                    observation.statusMessage(),
                    observation.startTime(),
                    observation.endTime(),
                    observation.latencyMs(),
                    resolvedModelName(observation),
                    resolvedTotalUsage(observation),
                    resolvedTotalCost(observation),
                    observation.traceName(),
                    observation.sessionId(),
                    observation.metadata() == null ? Map.of() : observation.metadata(),
                    null,
                    null);
        }
    }

    public static class LangfuseObservabilityException extends RuntimeException {
        private final HttpStatus status;

        public LangfuseObservabilityException(HttpStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }

    private static String resolvedModelName(LangfuseObservation observation) {
        if (hasText(observation.providedModelName())) {
            return observation.providedModelName();
        }
        return firstTextMetadataValue(observation.metadata(),
                "langfuse.observation.model.name",
                "gen_ai.request.model",
                "gen_ai.response.model",
                "llm.model_name",
                "model");
    }

    private static Long resolvedTotalUsage(LangfuseObservation observation) {
        if (observation.totalUsage() != null) {
            return observation.totalUsage();
        }
        Long total = firstLongMetadataValue(observation.metadata(),
                "gen_ai.usage.total_tokens",
                "gen_ai.usage.total",
                "llm.token_count.total",
                "total_tokens");
        if (total != null) {
            return total;
        }
        Long input = firstLongMetadataValue(observation.metadata(),
                "gen_ai.usage.prompt_tokens",
                "gen_ai.usage.input_tokens",
                "llm.token_count.prompt",
                "prompt_tokens");
        Long output = firstLongMetadataValue(observation.metadata(),
                "gen_ai.usage.completion_tokens",
                "gen_ai.usage.output_tokens",
                "llm.token_count.completion",
                "completion_tokens");
        if (input == null && output == null) {
            return null;
        }
        return nullToZero(input) + nullToZero(output);
    }

    private static Double resolvedTotalCost(LangfuseObservation observation) {
        if (observation.totalCost() != null) {
            return observation.totalCost();
        }
        return firstDoubleMetadataValue(observation.metadata(),
                "gen_ai.usage.cost",
                "langfuse.observation.cost",
                "total_cost",
                "cost");
    }

    private static String firstTextMetadataValue(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadataValue(metadata, key);
            if (value instanceof String text && hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private static Long firstLongMetadataValue(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadataValue(metadata, key);
            Long parsed = longValue(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Double firstDoubleMetadataValue(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadataValue(metadata, key);
            Double parsed = doubleValue(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object direct = metadata.get(key);
        if (direct != null) {
            return direct;
        }
        Object attributes = metadata.get("attributes");
        if (attributes instanceof Map<?, ?> attributesMap) {
            return ((Map<String, Object>) attributesMap).get(key);
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static class TraceAccumulator {
        private final String traceId;
        private final List<LangfuseObservation> observations = new ArrayList<>();

        private TraceAccumulator(String traceId) {
            this.traceId = traceId;
        }

        private void add(LangfuseObservation observation) {
            observations.add(observation);
        }

        private TraceListItem toListItem() {
            Instant start = observations.stream()
                    .map(LangfuseObservation::startTime)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(null);
            Instant end = observations.stream()
                    .map(LangfuseObservation::endTime)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(start);
            String traceName = observations.stream()
                    .map(LangfuseObservation::traceName)
                    .filter(LangfuseObservabilityService::hasText)
                    .findFirst()
                    .orElseGet(() -> observations.stream()
                            .map(LangfuseObservation::name)
                            .filter(LangfuseObservabilityService::hasText)
                            .findFirst()
                            .orElse("unknown"));
            String level = observations.stream().anyMatch(LangfuseObservabilityService::isError) ? "ERROR" : "DEFAULT";
            Set<String> modelNames = observations.stream()
                    .map(LangfuseObservabilityService::resolvedModelName)
                    .filter(LangfuseObservabilityService::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            long duration = start == null || end == null ? 0 : Math.max(0, end.toEpochMilli() - start.toEpochMilli());
            long totalTokens = observations.stream().mapToLong(item -> nullToZero(resolvedTotalUsage(item))).sum();
            double totalCost = observations.stream().mapToDouble(item -> nullToZero(resolvedTotalCost(item))).sum();
            return new TraceListItem(
                    traceId,
                    traceName,
                    start,
                    end,
                    duration,
                    level,
                    observations.size(),
                    List.copyOf(modelNames),
                    totalTokens,
                    roundMoney(totalCost));
        }
    }

    private static class HttpLangfuseObservationClient implements LangfuseObservationClient {
        private final String baseUrl;
        private final String authHeader;
        private final ObjectMapper objectMapper;
        private final WebClient webClient;

        private HttpLangfuseObservationClient(String baseUrl, String publicKey, String secretKey, ObjectMapper objectMapper) {
            this.baseUrl = normalizeBaseUrl(baseUrl);
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            this.objectMapper = objectMapper;
            this.webClient = WebClient.builder().build();
        }

        @Override
        public LangfuseObservationPage fetchObservations(LangfuseObservationQuery query) {
            URI uri = buildUri(query);
            try {
                String body = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, authHeader)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                return parsePage(body);
            } catch (WebClientResponseException e) {
                HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
                throw new LangfuseObservabilityException(
                        status == null ? HttpStatus.BAD_GATEWAY : status,
                        "Langfuse 请求失败: " + e.getStatusCode().value(),
                        e);
            } catch (Exception e) {
                throw new LangfuseObservabilityException(HttpStatus.BAD_GATEWAY, "Langfuse 暂不可用", e);
            }
        }

        private URI buildUri(LangfuseObservationQuery query) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/public/v2/observations")
                    .queryParam("fromStartTime", DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(query.from()))
                    .queryParam("toStartTime", DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(query.to()))
                    .queryParam("userId", query.userId())
                    .queryParam("limit", query.limit())
                    .queryParam("fields", query.fields());
            if (hasText(query.traceId())) {
                builder.queryParam("traceId", query.traceId());
            }
            if (hasText(query.level())) {
                builder.queryParam("level", query.level());
            }
            if (hasText(query.traceName())) {
                builder.queryParam("name", query.traceName());
            }
            if (hasText(query.cursor())) {
                builder.queryParam("cursor", query.cursor());
            }
            return builder.build().toUri();
        }

        private LangfuseObservationPage parsePage(String body) throws Exception {
            JsonNode root = objectMapper.readTree(body);
            List<LangfuseObservation> rows = new ArrayList<>();
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    rows.add(parseObservation(item));
                }
            }
            String cursor = textOrNull(root.path("meta").path("cursor"));
            return new LangfuseObservationPage(rows, cursor);
        }

        private LangfuseObservation parseObservation(JsonNode item) {
            return new LangfuseObservation(
                    textOrNull(item.path("id")),
                    textOrNull(item.path("traceId")),
                    instantOrNull(item.path("startTime")),
                    instantOrNull(item.path("endTime")),
                    textOrNull(item.path("parentObservationId")),
                    textOrNull(item.path("type")),
                    textOrNull(item.path("name")),
                    textOrNull(item.path("level")),
                    textOrNull(item.path("statusMessage")),
                    textOrNull(item.path("environment")),
                    textOrNull(item.path("sessionId")),
                    textOrNull(item.path("providedModelName")),
                    longOrNull(item.path("totalUsage")),
                    doubleOrNull(item.path("totalCost")),
                    doubleOrNull(item.path("latency")),
                    textOrNull(item.path("traceName")),
                    objectMap(item.path("metadata")),
                    null,
                    null);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> objectMap(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, Map.class);
        }

        private static String textOrNull(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            String value = node.asText();
            return hasText(value) ? value : null;
        }

        private static Instant instantOrNull(JsonNode node) {
            String value = textOrNull(node);
            return value == null ? null : Instant.parse(value);
        }

        private static Long longOrNull(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asLong();
        }

        private static Double doubleOrNull(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asDouble();
        }
    }
}
