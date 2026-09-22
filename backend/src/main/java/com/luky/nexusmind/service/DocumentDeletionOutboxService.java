package com.luky.nexusmind.service;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.DocumentDeletionTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 数据库是删除请求的可靠来源；Kafka 只负责唤醒异步清理消费者。 */
@Service
@Slf4j
public class DocumentDeletionOutboxService {
    private static final int BATCH_SIZE = 20;
    private static final int RETRY_DELAY_SECONDS = 30;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;
    private final KafkaConfig kafkaConfig;

    public DocumentDeletionOutboxService(JdbcTemplate jdbc, KafkaTemplate<String, Object> kafka,
                                         KafkaConfig kafkaConfig) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.kafkaConfig = kafkaConfig;
    }

    /** 必须在标记 deletion_pending 的同一数据库事务中调用。 */
    public void enqueue(DocumentDeletionTask task) {
        jdbc.update("insert into document_deletion_outbox "
                        + "(file_id,file_md5,user_id,next_attempt_at) values (?,?,?,current_timestamp(6)) "
                        + "on duplicate key update file_id=values(file_id), attempts=0, "
                        + "next_attempt_at=current_timestamp(6), last_error=null",
                task.fileId(), task.fileMd5(), task.userId());
    }

    @Scheduled(initialDelayString = "${document.deletion.outbox.initial-delay-ms:1000}",
            fixedDelayString = "${document.deletion.outbox.poll-delay-ms:2000}")
    public void publishDue() {
        List<OutboxRow> rows = jdbc.query("select id,file_id,file_md5,user_id from document_deletion_outbox "
                        + "where next_attempt_at <= current_timestamp(6) order by id limit " + BATCH_SIZE,
                (rs, row) -> new OutboxRow(rs.getLong("id"),
                        rs.getObject("file_id", Long.class), rs.getString("file_md5"), rs.getString("user_id")));
        rows.forEach(this::publish);
    }

    private void publish(OutboxRow row) {
        DocumentDeletionTask task = new DocumentDeletionTask(row.fileId(), row.fileMd5(), row.userId());
        try {
            kafka.executeInTransaction(operations -> {
                try {
                    operations.send(kafkaConfig.getDocumentDeletionTopic(), row.fileMd5(), task)
                            .get(15, TimeUnit.SECONDS);
                    return null;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("删除任务投递被中断", error);
                } catch (Exception error) {
                    throw new IllegalStateException("删除任务投递失败", error);
                }
            });
            defer(row.id(), null);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null) message = error.getClass().getSimpleName();
            log.warn("删除任务投递失败，将自动重试: fileMd5={}, userId={}, error={}",
                    row.fileMd5(), row.userId(), message);
            defer(row.id(), message);
        }
    }

    private void defer(long id, String error) {
        // 使用数据库时钟，避免 JVM 与 MySQL 时区不一致导致 next_attempt_at 立即再次到期。
        jdbc.update("update document_deletion_outbox set attempts=attempts+1,"
                        + "next_attempt_at=date_add(current_timestamp(6), interval "
                        + RETRY_DELAY_SECONDS + " second),last_error=? where id=?",
                abbreviate(error), id);
    }

    /** 只确认同一条文件记录，避免迟到的旧消息移除后来上传产生的新任务。 */
    public void complete(DocumentDeletionTask task) {
        if (task.fileId() == null) {
            jdbc.update("delete from document_deletion_outbox where file_md5=? and user_id=? and file_id is null",
                    task.fileMd5(), task.userId());
        } else {
            jdbc.update("delete from document_deletion_outbox where file_md5=? and user_id=? and file_id=?",
                    task.fileMd5(), task.userId(), task.fileId());
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 1000) return value;
        return value.substring(0, 1000);
    }

    private record OutboxRow(long id, Long fileId, String fileMd5, String userId) { }
}
