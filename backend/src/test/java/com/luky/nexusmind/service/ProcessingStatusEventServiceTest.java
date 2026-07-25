package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.ProcessingState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingStatusEventServiceTest {

    private final ProcessingStatusEventService service = new ProcessingStatusEventService();

    @Test
    void durationUsesLatestProcessingStartInsteadOfRecordCreationTime() {
        LocalDateTime now = LocalDateTime.now();
        FileProcessingStatus status = new FileProcessingStatus();
        status.setFileMd5("0123456789abcdef0123456789abcdef");
        status.setUserId("1");
        status.setCreatedAt(now.minusHours(2));
        status.setProcessingStartedAt(now.minusSeconds(5));
        status.setAccumulatedProcessingDurationMillis(12_000L);
        status.setUpdatedAt(now);
        status.setState(ProcessingState.FAILED);

        Map<String, Object> payload = service.toPayload(status);

        assertEquals(status.getProcessingStartedAt(), payload.get("processingStartedAt"));
        long durationMillis = (long) payload.get("processingDurationMillis");
        assertTrue(durationMillis >= 16_000L && durationMillis < 22_000L);
    }

    @Test
    void legacyStatusFallsBackToCreationTime() {
        LocalDateTime now = LocalDateTime.now();
        FileProcessingStatus status = new FileProcessingStatus();
        status.setFileMd5("0123456789abcdef0123456789abcdef");
        status.setUserId("1");
        status.setCreatedAt(now.minusSeconds(3));
        status.setUpdatedAt(now);
        status.setState(ProcessingState.FAILED);

        Map<String, Object> payload = service.toPayload(status);

        assertEquals(status.getCreatedAt(), payload.get("processingStartedAt"));
        long durationMillis = (long) payload.get("processingDurationMillis");
        assertTrue(durationMillis >= 2_000L && durationMillis < 10_000L);
    }
}
