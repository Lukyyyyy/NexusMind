package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.repository.FileProcessingStatusRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 持久化代次隔离删除前后的请求；写入与删除共用数据库锁，不能只做先检查后写入。 */
@Service
public class FileTaskControl {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final java.util.Set<String> deletingHere = ConcurrentHashMap.newKeySet();
    private final JdbcTemplate jdbc;
    private final FileProcessingStatusRepository statuses;
    private final TransactionTemplate tx;
    private final TransactionTemplate freshTx;

    public FileTaskControl(JdbcTemplate jdbc, FileProcessingStatusRepository statuses,
                           PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.statuses = statuses;
        tx = new TransactionTemplate(manager);
        freshTx = new TransactionTemplate(manager);
        freshTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long uploadGeneration(String md5, String owner) {
        return freshTx.execute(ignored -> {
            ensure(md5, owner);
            var state = state(md5, owner, true);
            if (state.deleting) throw new Cancelled();
            return state.generation;
        });
    }

    public Scope open(String md5, String owner, Long generation, FileProcessingTask task) {
        long expected = generation == null ? uploadGeneration(md5, owner) : generation;
        if (generation == null && task == null && expected != 0) throw new Cancelled();
        Scope scope = new Scope(this, md5, owner, expected, task, CURRENT.get());
        CURRENT.set(scope);
        try {
            // Kafka 可能在上传/重试事务提交前收到消息；加锁等待提交，不能把未提交状态当成旧任务。
            freshTx.executeWithoutResult(ignored -> validate(scope, true));
            return scope;
        }
        catch (RuntimeException e) { scope.close(); throw e; }
    }

    /** 在删除事务开始清理前单独提交撤销，其他实例也能立即停止等待模型响应。 */
    public void beginDelete(String md5, String owner) {
        String key = owner + ":" + md5;
        deletingHere.add(key);
        try {
            freshTx.executeWithoutResult(ignored -> {
                ensure(md5, owner);
                state(md5, owner, true);
                jdbc.update("update file_task_generation set generation=generation+1, deleting=true where file_md5=? and user_id=?", md5, owner);
            });
        } finally { deletingHere.remove(key); }
    }


    /**
     * 与调用方的数据库事务一起提交删除代次。
     * 用于可靠删除入队：事务回滚时，取消标记和发件箱记录必须一起回滚。
     */
    public void beginDeleteTransactionally(String md5, String owner) {
        ensure(md5, owner);
        state(md5, owner, true);
        jdbc.update("update file_task_generation set generation=generation+1, deleting=true "
                + "where file_md5=? and user_id=?", md5, owner);
    }

    public void lockDeletion(String md5, String owner) { state(md5, owner, true); }

    public void finishDelete(String md5, String owner) {
        jdbc.update("update file_task_generation set deleting=false where file_md5=? and user_id=?", md5, owner);
    }

    /**
     * 兼容旧调用：仅在删除请求事务未提交时撤销删除标记。
     * 新的 Outbox 流程与 deletion_pending 同事务提交，正常路径不再调用此方法。
     */
    public void abortDelete(String md5, String owner) {
        freshTx.executeWithoutResult(ignored ->
                jdbc.update("update file_task_generation set deleting=false where file_md5=? and user_id=?", md5, owner));
    }

    private void ensure(String md5, String owner) {
        jdbc.update("insert ignore into file_task_generation(file_md5,user_id,generation,deleting) values (?,?,0,false)", md5, owner);
    }

    private record State(long generation, boolean deleting) {}
    private State state(String md5, String owner, boolean lock) {
        return jdbc.queryForObject("select generation,deleting from file_task_generation where file_md5=? and user_id=?"
                + (lock ? " for update" : ""), (rs, row) -> new State(rs.getLong(1), rs.getBoolean(2)), md5, owner);
    }

    private void validate(Scope scope, boolean lock) {
        if (deletingHere.contains(scope.owner + ":" + scope.md5)) throw new Cancelled();
        State state = state(scope.md5, scope.owner, lock);
        if (state.deleting || state.generation != scope.generation) throw new Cancelled();
        if (scope.task != null) {
            var status = lock ? statuses.findByFileMd5AndUserIdForUpdate(scope.md5, scope.owner)
                    : statuses.findByFileMd5AndUserId(scope.md5, scope.owner);
            if (status.filter(s -> Objects.equals(s.getAttemptId(), scope.task.getAttemptId())).isEmpty()) throw new Cancelled();
        }
    }

    public static void check() {
        Scope scope = CURRENT.get();
        if (Thread.currentThread().isInterrupted()) throw new Cancelled();
        if (scope != null) {
            if (scope.control.deletingHere.contains(scope.owner + ":" + scope.md5)) throw new Cancelled();
            long now = System.nanoTime();
            if (now >= scope.nextCheck) {
                scope.control.freshTx.executeWithoutResult(ignored -> scope.control.validate(scope, false));
                scope.nextCheck = now + TimeUnit.MILLISECONDS.toNanos(100);
            }
        }
    }

    /** 只包含短写入，不在持锁期间等待模型；提交完成后删除才能开始清理。 */
    public static <T> T write(Supplier<T> action) {
        Scope scope = CURRENT.get();
        if (scope == null) return action.get();
        return scope.control.tx.execute(ignored -> {
            scope.control.validate(scope, true);
            return action.get();
        });
    }
    public static void write(Runnable action) { write(() -> { action.run(); return null; }); }

    /** 取消 Future 会关闭支持取消的 HTTP 请求；迟到结果不会进入后续写入。 */
    public static <T> T await(Future<T> future) {
        try {
            while (true) {
                check();
                try {
                    T result = future.get(100, TimeUnit.MILLISECONDS);
                    check();
                    return result;
                } catch (TimeoutException ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new CompletionException(e.getCause());
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public static java.io.InputStream stream(java.io.InputStream input) {
        Supplier<Void> validate = propagate(() -> { check(); return null; });
        return new java.io.FilterInputStream(input) {
            @Override public int read() throws java.io.IOException { validate.get(); return super.read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                validate.get(); return in.read(bytes, offset, length);
            }
        };
    }

    public static <T> Supplier<T> propagate(Supplier<T> action) {
        Scope captured = CURRENT.get();
        return () -> {
            Scope previous = CURRENT.get();
            if (captured == null) CURRENT.remove(); else CURRENT.set(captured);
            try { check(); return action.get(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        };
    }

    public static boolean isCancelled(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof Cancelled) return true;
            if (current == current.getCause()) break;
        }
        return false;
    }

    public static final class Cancelled extends RuntimeException {
        public Cancelled() { super("文件任务已取消，请重新上传或重试删除"); }
    }

    public static final class Scope implements AutoCloseable {
        private final FileTaskControl control;
        private final String md5;
        private final String owner;
        private final long generation;
        private final FileProcessingTask task;
        private final Scope previous;
        private volatile long nextCheck;
        private Scope(FileTaskControl control, String md5, String owner, long generation, FileProcessingTask task, Scope previous) {
            this.control=control; this.md5=md5; this.owner=owner; this.generation=generation; this.task=task; this.previous=previous;
        }
        @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
