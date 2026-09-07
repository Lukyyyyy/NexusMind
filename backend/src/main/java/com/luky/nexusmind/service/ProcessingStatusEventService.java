package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.ProcessingState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ProcessingStatusEventService {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long TICKET_TTL_MILLIS = 60_000L;

    private final Map<String, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public ProcessingStatusEventService() {
    }

    @Autowired
    public ProcessingStatusEventService(MeterRegistry meterRegistry) {
        Gauge.builder("nexusmind.sse.emitters", this, ProcessingStatusEventService::emitterCount)
                .description("Current processing status SSE emitters")
                .register(meterRegistry);
    }

    public String issueTicket(String userId) {
        // ponytail: in-memory tickets fit the single backend deployment; use Redis before horizontal scaling.
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(userId, System.currentTimeMillis() + TICKET_TTL_MILLIS));
        return ticket;
    }

    public String consumeTicket(String value) {
        Ticket ticket = value == null ? null : tickets.remove(value);
        return ticket != null && ticket.expiresAt() >= System.currentTimeMillis() ? ticket.userId() : null;
    }

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emittersByUser.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(throwable -> removeEmitter(userId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("serverTime", LocalDateTime.now())));
        } catch (IOException e) {
            discardEmitter(userId, emitter);
        }

        return emitter;
    }

    public void publish(FileProcessingStatus status) {
        if (status == null || status.getUserId() == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByUser.get(status.getUserId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> payload = toPayload(status);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("processing-status")
                        .data(payload));
            } catch (IOException | IllegalStateException e) {
                discardEmitter(status.getUserId(), emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 20_000L)
    public void heartbeat() {
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < System.currentTimeMillis());
        emittersByUser.forEach((userId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                discardEmitter(userId, emitter);
            }
        }));
    }

    public int emitterCount() {
        return emittersByUser.values().stream().mapToInt(List::size).sum();
    }

    public Map<String, Object> toPayload(FileProcessingStatus status) {
        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", status.getFileMd5());
        data.put("fileName", status.getFileName());
        data.put("processingStage", status.getCurrentStage());
        data.put("processingState", status.getState());
        data.put("processingMessage", status.getMessage());
        data.put("processingError", status.getErrorMessage());
        data.put("parseEngine", status.getParseEngine());
        data.put("actualParseEngine", status.getActualParseEngine() != null
                ? status.getActualParseEngine()
                : status.getParseEngine());
        data.put("actualChunkSize", status.getChunkSize());
        data.put("parsedChunkCount", status.getParsedChunkCount());
        data.put("vectorizedCount", status.getVectorizedCount());
        data.put("esDocumentCount", status.getEsDocumentCount());
        data.put("processingStartedAt", resolveProcessingStartedAt(status));
        data.put("processingAccumulatedDurationMillis", resolveAccumulatedDuration(status));
        data.put("processingUpdatedAt", status.getUpdatedAt());
        data.put("processingCompletedAt", status.getCompletedAt());
        data.put("processingDurationMillis", calculateProcessingDurationMillis(status));
        data.put("serverTime", LocalDateTime.now());
        return data;
    }

    private Long calculateProcessingDurationMillis(FileProcessingStatus status) {
        LocalDateTime startedAt = resolveProcessingStartedAt(status);
        if (startedAt == null) {
            return null;
        }

        LocalDateTime endedAt = status.getCompletedAt();
        if (endedAt == null && status.getState() == ProcessingState.FAILED) {
            endedAt = status.getUpdatedAt();
        }
        if (endedAt == null) {
            endedAt = LocalDateTime.now();
        }

        return resolveAccumulatedDuration(status)
                + Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
    }

    private long resolveAccumulatedDuration(FileProcessingStatus status) {
        return status.getAccumulatedProcessingDurationMillis() == null
                ? 0L
                : Math.max(0L, status.getAccumulatedProcessingDurationMillis());
    }

    private LocalDateTime resolveProcessingStartedAt(FileProcessingStatus status) {
        return status.getProcessingStartedAt() != null
                ? status.getProcessingStartedAt()
                : status.getCreatedAt();
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        emittersByUser.computeIfPresent(userId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private void discardEmitter(String userId, SseEmitter emitter) {
        removeEmitter(userId, emitter);
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Already completed by the servlet container.
        }
    }

    private record Ticket(String userId, long expiresAt) {}
}
