package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.ProcessingState;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ProcessingStatusEventService {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<String, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

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
            removeEmitter(userId, emitter);
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
                removeEmitter(status.getUserId(), emitter);
            }
        }
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
        data.put("processingStartedAt", status.getCreatedAt());
        data.put("processingUpdatedAt", status.getUpdatedAt());
        data.put("processingCompletedAt", status.getCompletedAt());
        data.put("processingDurationMillis", calculateProcessingDurationMillis(status));
        data.put("serverTime", LocalDateTime.now());
        return data;
    }

    private Long calculateProcessingDurationMillis(FileProcessingStatus status) {
        LocalDateTime startedAt = status.getCreatedAt();
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

        return Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId);
        }
    }
}
