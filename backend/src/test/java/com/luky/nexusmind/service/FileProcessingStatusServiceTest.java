package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.model.ProcessingState;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileProcessingStatusServiceTest {

    @Test
    void retryAccumulatesPreviousAttemptButExcludesIdleTime() {
        LocalDateTime now = LocalDateTime.now();
        FileProcessingStatus status = new FileProcessingStatus();
        status.setFileMd5("0123456789abcdef0123456789abcdef");
        status.setUserId("1");
        status.setState(ProcessingState.FAILED);
        status.setProcessingStartedAt(now.minusMinutes(30));
        status.setCompletedAt(now.minusMinutes(29));
        status.setUpdatedAt(now.minusMinutes(29));
        status.setAccumulatedProcessingDurationMillis(20_000L);

        FileProcessingStatusRepository repository = repositoryReturning(status);
        FileProcessingStatusService service = new FileProcessingStatusService(
                repository, new ProcessingStatusEventService());
        FileProcessingTask task = new FileProcessingTask();
        task.setFileMd5(status.getFileMd5());
        task.setUserId(status.getUserId());

        assertTrue(service.claimRetry(task));

        long accumulated = status.getAccumulatedProcessingDurationMillis();
        assertTrue(accumulated >= 79_000L && accumulated < 82_000L);
        assertTrue(status.getProcessingStartedAt().isAfter(now.minusSeconds(2)));
        assertNull(status.getCompletedAt());
        assertEquals(ProcessingState.PENDING, status.getState());
    }

    private FileProcessingStatusRepository repositoryReturning(FileProcessingStatus status) {
        return (FileProcessingStatusRepository) Proxy.newProxyInstance(
                FileProcessingStatusRepository.class.getClassLoader(),
                new Class<?>[]{FileProcessingStatusRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByFileMd5AndUserIdForUpdate" -> Optional.of(status);
                    case "saveAndFlush" -> args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
