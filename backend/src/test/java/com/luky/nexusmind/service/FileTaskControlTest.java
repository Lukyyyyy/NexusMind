package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileTaskControlTest {
    FileTaskControl control;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    FileProcessingStatusRepository statuses;
    ExecutorService executor;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table file_task_generation(file_md5 varchar(64), user_id varchar(64), generation bigint, deleting boolean, primary key(file_md5,user_id))");
        jdbc.execute("create table results(value_id int)");
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        statuses = mock(FileProcessingStatusRepository.class);
        control = new FileTaskControl(jdbc, statuses, manager);
        executor = Executors.newFixedThreadPool(2);
    }
    @AfterEach void shutdown() { executor.shutdownNow(); }

    void delete() {
        control.beginDelete("md5", "owner");
        tx.executeWithoutResult(ignored -> {
            control.lockDeletion("md5", "owner");
            jdbc.update("delete from results");
            control.finishDelete("md5", "owner");
        });
    }

    @Test void cancelsBlockedRequestAndRejectsLateResult() throws Exception {
        long generation = control.uploadGeneration("md5", "owner");
        CompletableFuture<String> response = new CompletableFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Future<?> task = executor.submit(() -> {
            try (var scope = control.open("md5", "owner", generation, null)) {
                waiting.countDown();
                FileTaskControl.await(response);
                FileTaskControl.write(() -> jdbc.update("insert into results values(1)"));
            }
        });
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        delete();
        var failure = assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS));
        assertTrue(FileTaskControl.isCancelled(failure));
        assertTrue(response.isCancelled());
        assertFalse(response.complete("late response"));
        assertEquals(0, jdbc.queryForObject("select count(*) from results", Integer.class));
    }

    @Test void cancellationClosesActualMineruHttpConnection() throws Exception {
        long generation = control.uploadGeneration("md5", "owner");
        try (var server = new java.net.ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            Future<Boolean> disconnected = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    accepted.countDown();
                    while (socket.getInputStream().read() != -1) { }
                    return true;
                }
            });
            MinerUParseClient mineru = new MinerUParseClient();
            org.springframework.test.util.ReflectionTestUtils.setField(mineru, "baseUrl", "http://127.0.0.1:" + server.getLocalPort());
            org.springframework.test.util.ReflectionTestUtils.setField(mineru, "parsePath", "/file_parse");
            Future<?> parse = executor.submit(() -> {
                try (var scope = control.open("md5", "owner", generation, null)) {
                    return mineru.parseToText("pdf".getBytes(), "file.pdf");
                }
            });
            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            delete();
            var failure = assertThrows(ExecutionException.class, () -> parse.get(2, TimeUnit.SECONDS));
            assertTrue(FileTaskControl.isCancelled(failure));
            assertTrue(disconnected.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void oldUploadCannotRecreateDeletedFileButNewUploadCan() {
        long old = control.uploadGeneration("md5", "owner");
        delete();
        assertThrows(FileTaskControl.Cancelled.class, () -> control.open("md5", "owner", old, null));
        assertThrows(FileTaskControl.Cancelled.class, () -> control.open("md5", "owner", null, null));
        long next = control.uploadGeneration("md5", "owner");
        assertTrue(next > old);
        try (var scope = control.open("md5", "owner", next, null)) {
            FileTaskControl.write(() -> jdbc.update("insert into results values(2)"));
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from results", Integer.class));
    }

    @Test void deletionWaitsForCommitThenRemovesItsWrites() throws Exception {
        long generation = control.uploadGeneration("md5", "owner");
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> {
            try (var scope = control.open("md5", "owner", generation, null)) {
                FileTaskControl.write(() -> {
                    jdbc.update("insert into results values(1)");
                    writing.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                });
            }
        });
        assertTrue(writing.await(2, TimeUnit.SECONDS));
        Future<?> deletion = executor.submit(this::delete);
        assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
        release.countDown();
        writer.get(2, TimeUnit.SECONDS);
        deletion.get(2, TimeUnit.SECONDS);
        assertEquals(0, jdbc.queryForObject("select count(*) from results", Integer.class));
    }


    @Test void concurrentUploadWritesShareGenerationLock() throws Exception {
        long generation = control.uploadGeneration("md5", "owner");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Callable<Void> writer = () -> {
            try (var scope = control.open("md5", "owner", generation, null)) {
                FileTaskControl.write(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                });
            }
            return null;
        };

        Future<Void> first = executor.submit(writer);
        Future<Void> second = executor.submit(writer);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
    }

    @Test void anotherInstanceSeesDeletionAndStaleAttemptIsRejected() {
        var task = new FileProcessingTask("md5", "path", "file.pdf", "owner", "default", true);
        task.setAttemptId("old");
        FileProcessingStatus status = new FileProcessingStatus();
        status.setAttemptId("new");
        when(statuses.findByFileMd5AndUserIdForUpdate("md5", "owner")).thenReturn(Optional.of(status));
        assertThrows(FileTaskControl.Cancelled.class, () -> control.open("md5", "owner", null, task));
        // 没有 ThreadLocal 泄漏，后续非文件操作不受影响。
        assertDoesNotThrow(FileTaskControl::check);
    }

    @Test void failedCleanupKeepsUploadsBlockedUntilDeletionIsRetried() {
        control.uploadGeneration("md5", "owner");
        control.beginDelete("md5", "owner");
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(ignored -> {
            control.lockDeletion("md5", "owner");
            control.finishDelete("md5", "owner");
            throw new RuntimeException("storage unavailable");
        }));
        assertThrows(FileTaskControl.Cancelled.class, () -> control.uploadGeneration("md5", "owner"));
        delete();
        assertDoesNotThrow(() -> control.uploadGeneration("md5", "owner"));
    }
}
