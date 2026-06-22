package com.luky.nexusmind.service;

import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FileProcessingStatusService {

    private final FileProcessingStatusRepository repository;
    private final ProcessingStatusEventService eventService;

    public FileProcessingStatusService(FileProcessingStatusRepository repository,
                                       ProcessingStatusEventService eventService) {
        this.repository = repository;
        this.eventService = eventService;
    }

    @Transactional
    public FileProcessingStatus markQueued(FileProcessingTask task) {
        FileProcessingStatus status = getOrCreate(task);
        status.setParseEngine(task.getParseEngine() == null ? ParseEngine.AUTO : task.getParseEngine());
        status.setChunkSize(task.getChunkSize());
        status.setActualParseEngine(null);
        status.setCurrentStage(ProcessingStage.QUEUED);
        status.setState(ProcessingState.PENDING);
        status.setMessage("等待处理");
        status.setErrorMessage(null);
        status.setParsedChunkCount(0);
        status.setVectorizedCount(0);
        status.setEsDocumentCount(0L);
        status.setCompletedAt(null);
        return saveAndPublish(status);
    }

    @Transactional
    public void markRunning(FileProcessingTask task, ProcessingStage stage, String message) {
        FileProcessingStatus status = getOrCreate(task);
        applyRunning(status, stage, message);
        saveAndPublish(status);
    }

    @Transactional
    public void markRunning(String fileMd5, String userId, ProcessingStage stage, String message) {
        repository.findByFileMd5AndUserId(fileMd5, userId).ifPresent(status -> {
            applyRunning(status, stage, message);
            saveAndPublish(status);
        });
    }

    private void applyRunning(FileProcessingStatus status, ProcessingStage stage, String message) {
        if (shouldAdvanceStage(status.getCurrentStage(), stage)) {
            status.setCurrentStage(stage);
            status.setMessage(message);
        }
        status.setState(ProcessingState.RUNNING);
        status.setErrorMessage(null);
    }

    @Transactional
    public void markParsed(FileProcessingTask task, int chunkCount) {
        FileProcessingStatus status = getOrCreate(task);
        if (shouldAdvanceStage(status.getCurrentStage(), ProcessingStage.CHUNKING)) {
            status.setCurrentStage(ProcessingStage.CHUNKING);
            status.setMessage("解析和切片完成");
        }
        status.setState(ProcessingState.RUNNING);
        status.setParsedChunkCount(chunkCount);
        status.setErrorMessage(null);
        saveAndPublish(status);
    }

    @Transactional
    public void markActualParseEngine(String fileMd5, String userId, ParseEngine actualParseEngine) {
        repository.findByFileMd5AndUserId(fileMd5, userId).ifPresent(status -> {
            status.setActualParseEngine(actualParseEngine);
            saveAndPublish(status);
        });
    }

    @Transactional
    public void markCompleted(FileProcessingTask task, int vectorizedCount, long esDocumentCount) {
        FileProcessingStatus status = getOrCreate(task);
        status.setCurrentStage(ProcessingStage.COMPLETED);
        status.setState(ProcessingState.SUCCEEDED);
        status.setVectorizedCount(vectorizedCount);
        status.setEsDocumentCount(esDocumentCount);
        status.setMessage("处理完成");
        status.setErrorMessage(null);
        status.setCompletedAt(LocalDateTime.now());
        saveAndPublish(status);
    }

    @Transactional
    public void markFailed(FileProcessingTask task, ProcessingStage stage, Exception exception) {
        FileProcessingStatus status = getOrCreate(task);
        status.setCurrentStage(stage == null ? status.getCurrentStage() : stage);
        status.setState(ProcessingState.FAILED);
        status.setMessage("处理失败");
        status.setErrorMessage(truncate(exception.getMessage(), 2000));
        status.setCompletedAt(LocalDateTime.now());
        saveAndPublish(status);
    }

    @Transactional(readOnly = true)
    public Optional<FileProcessingStatus> findByFileMd5AndUserId(String fileMd5, String userId) {
        return repository.findByFileMd5AndUserId(fileMd5, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, FileProcessingStatus> findLatestByFileMd5(Collection<String> fileMd5List, String userId) {
        List<FileProcessingStatus> statuses = repository.findByFileMd5InAndUserId(fileMd5List, userId);
        return statuses.stream()
                .collect(Collectors.toMap(FileProcessingStatus::getFileMd5, Function.identity(), (left, right) ->
                        left.getUpdatedAt() != null && right.getUpdatedAt() != null && left.getUpdatedAt().isAfter(right.getUpdatedAt())
                                ? left
                                : right
                ));
    }

    @Transactional
    public void delete(String fileMd5, String userId) {
        repository.deleteByFileMd5AndUserId(fileMd5, userId);
    }

    private FileProcessingStatus getOrCreate(FileProcessingTask task) {
        return repository.findByFileMd5AndUserId(task.getFileMd5(), task.getUserId())
                .orElseGet(() -> {
                    FileProcessingStatus status = new FileProcessingStatus();
                    status.setFileMd5(task.getFileMd5());
                    status.setFileName(task.getFileName());
                    status.setUserId(task.getUserId());
                    status.setParseEngine(task.getParseEngine() == null ? ParseEngine.AUTO : task.getParseEngine());
                    status.setChunkSize(task.getChunkSize());
                    status.setCreatedAt(LocalDateTime.now());
                    return status;
                });
    }

    private FileProcessingStatus saveAndPublish(FileProcessingStatus status) {
        FileProcessingStatus saved = repository.saveAndFlush(status);
        eventService.publish(saved);
        return saved;
    }

    private boolean shouldAdvanceStage(ProcessingStage currentStage, ProcessingStage nextStage) {
        if (nextStage == null) {
            return false;
        }
        if (currentStage == null) {
            return true;
        }
        return stageOrder(nextStage) >= stageOrder(currentStage);
    }

    private int stageOrder(ProcessingStage stage) {
        return switch (stage) {
            case QUEUED -> 0;
            case PARSING -> 1;
            case CHUNKING -> 2;
            case VECTORIZING -> 3;
            case INDEXING -> 4;
            case COMPLETED -> 5;
            case FAILED -> 6;
        };
    }

    private String truncate(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }
}
