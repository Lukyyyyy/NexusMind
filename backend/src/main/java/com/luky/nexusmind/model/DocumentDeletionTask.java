package com.luky.nexusmind.model;

/** Kafka message for idempotent document cleanup. */
public record DocumentDeletionTask(Long fileId, String fileMd5, String userId) {}
