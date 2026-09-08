package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.client.KnowledgeGraphExtractionClient;
import com.luky.nexusmind.client.KnowledgeGraphExtractionClient.DictionaryEntry;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Remote calls never hold a database transaction. One coordinator serializes checkpoint writes. */
@Service
public class GraphExtractionEngine {
    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(GraphExtractionEngine.class);

    public static class Work {
        public GraphBatchPlan.Batch batch;
        public List<GraphBatchPlan.Batch> remaining = new ArrayList<>();
        public List<DictionaryEntry> entries = new ArrayList<>();
        public String status = "QUEUED";
        public String error;

        public Work() {}

        Work(GraphBatchPlan.Batch batch) {
            this.batch = batch;
            remaining.add(batch);
        }
    }

    public static class Snapshot {
        public String title;
        public String instructions;
        public String stage = "DICTIONARY";
        public List<Work> dictionary = new ArrayList<>();
        public List<Work> relations = new ArrayList<>();
        public List<DictionaryEntry> glossary = new ArrayList<>();
        public List<String> unresolved = new ArrayList<>();
    }

    public record StageProgress(
            int total, long ended, long succeeded, long failed, long retrying) {}

    public record Failure(String stage, int batch, List<String> ranges, String reason) {}

    public record Progress(
            String stage,
            StageProgress dictionary,
            StageProgress relations,
            int unresolved,
            boolean canRetry,
            List<Failure> failures) {}

    private record Result(GraphBatchPlan.Batch batch, Object value, Throwable error) {}

    private final FileUploadRepository files;
    private final GraphExtractionRunRepository runs;
    private final DocumentVectorRepository vectors;
    private final GraphCandidateRepository candidates;
    private final UserRepository users;
    private final GraphPromptTemplateService templates;
    private final KnowledgeGraphExtractionClient client;
    private final GraphModelScheduler scheduler;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public GraphExtractionEngine(
            FileUploadRepository files,
            GraphExtractionRunRepository runs,
            DocumentVectorRepository vectors,
            GraphCandidateRepository candidates,
            UserRepository users,
            GraphPromptTemplateService templates,
            KnowledgeGraphExtractionClient client,
            GraphModelScheduler scheduler,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.files = files;
        this.runs = runs;
        this.vectors = vectors;
        this.candidates = candidates;
        this.users = users;
        this.templates = templates;
        this.client = client;
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (GraphExtractionRun run : runs.findAll()) {
            tx.executeWithoutResult(
                    status ->
                            files.lockById(run.getFileId())
                                    .ifPresent(
                                            f -> {
                                                if (isActive(f.getId())) return;
                                                if (f.isGraphEnabled()
                                                        && (f.getGraphStatus()
                                                                        == KnowledgeGraphStatus
                                                                                .EXTRACTING
                                                                || f.getGraphStatus()
                                                                        == KnowledgeGraphStatus
                                                                                .QUEUED)) {
                                                    Snapshot snapshot = read(run.getSnapshot());
                                                    for (Work w : snapshot.dictionary)
                                                        if (!w.remaining.isEmpty())
                                                            w.status = "FAILED";
                                                    for (Work w : snapshot.relations)
                                                        if (!w.remaining.isEmpty())
                                                            w.status = "FAILED";
                                                    snapshot.stage = "INTERRUPTED";
                                                    run.setSnapshot(write(snapshot));
                                                    runs.save(run);
                                                    f.setGraphRunToken(
                                                            UUID.randomUUID().toString());
                                                    f.setGraphStatus(KnowledgeGraphStatus.FAILED);
                                                    f.setGraphError("服务重启中断了抽取，已保存进度，可重试未完成部分");
                                                    files.save(f);
                                                }
                                            }));
        }
        for (FileUpload pending :
                files.findByGraphStatusInAndGraphRunTokenIsNotNull(
                        List.of(KnowledgeGraphStatus.QUEUED, KnowledgeGraphStatus.EXTRACTING))) {
            tx.executeWithoutResult(
                    status -> files.lockById(pending.getId()).ifPresent(f -> {
                        if (isActive(f.getId())) return;
                        if (f.isGraphEnabled()
                                && (f.getGraphStatus() == KnowledgeGraphStatus.QUEUED
                                        || f.getGraphStatus() == KnowledgeGraphStatus.EXTRACTING)) {
                            f.setGraphRunToken(UUID.randomUUID().toString());
                            f.setGraphStatus(KnowledgeGraphStatus.FAILED);
                            f.setGraphError("服务重启中断了任务，请重新抽取");
                            files.save(f);
                        }
                    }));
        }
    }

    private boolean isActive(Long fileId) {
        return active.stream().anyMatch(key -> key.startsWith(fileId + ":"));
    }

    public void removeRun(Long fileId) {
        scheduler.cancelDocument(fileId.toString());
        runs.deleteById(fileId);
    }

    /** Discard an old run without blocking a new generation for the same document. */
    public void resetRun(Long fileId) {
        scheduler.resetDocument(fileId.toString());
        runs.deleteById(fileId);
    }

    public Progress progress(Long fileId) {
        return runs.findById(fileId)
                .map(
                        r -> {
                            Snapshot s = read(r.getSnapshot());
                            return new Progress(
                                    s.stage,
                                    count(s.dictionary),
                                    count(s.relations),
                                    s.unresolved.size(),
                                    s.dictionary.stream().anyMatch(w -> !w.remaining.isEmpty())
                                            || s.relations.stream()
                                                    .anyMatch(w -> !w.remaining.isEmpty())
                                            || !s.unresolved.isEmpty(),
                                    failures(s));
                        })
                .orElse(null);
    }

    private List<Failure> failures(Snapshot s) {
        List<Failure> result = new ArrayList<>();
        for (var stage : Map.of("实体词典", s.dictionary, "关系抽取", s.relations).entrySet())
            for (Work w : stage.getValue())
                if (w.status.equals("FAILED"))
                    result.add(
                            new Failure(
                                    stage.getKey(),
                                    w.batch.index() + 1,
                                    w.remaining.stream()
                                            .flatMap(b -> b.parts().stream())
                                            .map(
                                                    p ->
                                                            "切片 "
                                                                    + p.chunkId()
                                                                    + " ["
                                                                    + p.start()
                                                                    + ", "
                                                                    + p.end()
                                                                    + ")")
                                            .toList(),
                                    w.error));
        return result;
    }

    private String failureReason(Throwable error) {
        if (KnowledgeGraphExtractionClient.isTruncated(error)) return "模型输出达到 token 上限被截断（已重试）";
        Throwable e = error;
        while (e.getCause() != null) e = e.getCause();
        if (e
                instanceof
                org.springframework.web.reactive.function.client.WebClientResponseException http)
            return "模型接口 HTTP " + http.getStatusCode().value() + "（已重试）";
        return "模型请求或结果校验失败（已重试）";
    }

    private StageProgress count(List<Work> works) {
        return new StageProgress(
                works.size(),
                works.stream().filter(w -> Set.of("SUCCESS", "FAILED").contains(w.status)).count(),
                works.stream().filter(w -> w.status.equals("SUCCESS")).count(),
                works.stream().filter(w -> w.status.equals("FAILED")).count(),
                works.stream().filter(w -> w.status.equals("RETRYING")).count());
    }

    private Snapshot read(String json) {
        try {
            return mapper.readValue(json, Snapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("图谱任务快照无法读取", e);
        }
    }

    private String write(Snapshot s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException("图谱任务快照无法保存", e);
        }
    }

    public void run(
            String md5, String owner, boolean retry, KnowledgeGraphExtractionService factory) {
        FileUpload file = files.findByFileMd5AndUserId(md5, owner).orElseThrow();
        if (!file.isGraphEnabled()) return;
        String token = file.getGraphRunToken();
        String activeKey = file.getId() + ":" + token;
        if (!active.add(activeKey)) return;
        Snapshot s = null;
        try {
            if (token == null) {
                token = UUID.randomUUID().toString();
                String initialToken = token;
                tx.executeWithoutResult(
                        status -> {
                            FileUpload current = files.lockById(file.getId()).orElseThrow();
                            current.setGraphRunToken(initialToken);
                            files.save(current);
                        });
            }
            final String generation = token;
            s =
                    retry
                            ? read(runs.findById(file.getId()).orElseThrow().getSnapshot())
                            : new Snapshot();
            if (!retry) {
                s.title = file.getFileName();
                s.instructions = templates.resolve(file.getGraphPromptTemplateId()).instructions();
                List<GraphBatchPlan.Batch> batches =
                        GraphBatchPlan.create(vectors.findByFileMd5(md5), batchChars(file));
                if (batches.isEmpty()) throw new IllegalStateException("文档尚未完成解析");
                for (var batch : batches) {
                    s.dictionary.add(new Work(batch));
                    s.relations.add(new Work(batch));
                }
                checkpoint(
                        file.getId(),
                        generation,
                        s,
                        f -> candidates.deleteByFileUploadId(f.getId()));
            }
            String username =
                    users.findByUsername(owner)
                            .map(User::getUsername)
                            .orElseGet(
                                    () -> {
                                        try {
                                            return users.findById(Long.parseLong(owner))
                                                    .map(User::getUsername)
                                                    .orElse(owner);
                                        } catch (NumberFormatException e) {
                                            return owner;
                                        }
                                    });
            s.stage = "DICTIONARY";
            checkpoint(
                    file.getId(),
                    generation,
                    s,
                    f -> f.setGraphStatus(KnowledgeGraphStatus.EXTRACTING));
            process(file, generation, s, s.dictionary, username, true, factory);
            s.stage = "RESOLVING";
            checkpoint(file.getId(), generation, s, f -> {});
            mergeDictionary(file, generation, s, username);
            s.stage = "RELATIONS";
            checkpoint(file.getId(), generation, s, f -> {});
            process(file, generation, s, s.relations, username, false, factory);
            s.stage = "FINALIZING";
            checkpoint(file.getId(), generation, s, f -> {});
            s.stage = "COMPLETE";
            Snapshot finalSnapshot = s;
            checkpoint(
                    file.getId(),
                    generation,
                    s,
                    f -> {
                        long failed = count(finalSnapshot.relations).failed(),
                                dictionaryFailed = count(finalSnapshot.dictionary).failed();
                        int saved =
                                candidates
                                        .findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(f.getId())
                                        .size();
                        f.setGraphStatus(
                                saved == 0 && failed > 0
                                        ? KnowledgeGraphStatus.FAILED
                                        : KnowledgeGraphStatus.PENDING_REVIEW);
                        f.setGraphError(
                                failed + dictionaryFailed + finalSnapshot.unresolved.size() > 0
                                        ? "已保留 "
                                                + saved
                                                + " 条关系；关系抽取 "
                                                + failed
                                                + "/"
                                                + finalSnapshot.relations.size()
                                                + " 批未完成，实体词典 "
                                                + dictionaryFailed
                                                + "/"
                                                + finalSnapshot.dictionary.size()
                                                + " 批未完成，未解决映射 "
                                                + finalSnapshot.unresolved.size()
                                                + " 项。可重试未完成部分"
                                        : saved == 0 ? "未从文档中识别到可靠关系" : null);
                    });
        } catch (CancellationException ignored) {
            // A disabled, deleted, or superseded document must never receive stale results.
        } catch (Exception e) {
            final String generation = token;
            tx.executeWithoutResult(
                    status ->
                            files.lockById(file.getId())
                                    .ifPresent(
                                            f -> {
                                                if (Objects.equals(generation, f.getGraphRunToken())
                                                        && f.isGraphEnabled()) {
                                                    f.setGraphStatus(KnowledgeGraphStatus.FAILED);
                                                    f.setGraphError("图谱任务中断，已完成结果保留，可重试未完成部分");
                                                    files.save(f);
                                                }
                                            }));
            throw e;
        } finally {
            active.remove(activeKey);
        }
    }

    public static int batchChars(FileUpload f) {
        return f.getGraphBatchChars() == null ? 3072 : f.getGraphBatchChars();
    }

    public static void validateBatch(FileUpload f, Integer value) {
        int n = value == null ? batchChars(f) : value;
        int chunk = f.getTextChunkSize() == null ? 512 : f.getTextChunkSize();
        if (n < chunk || n > 100000)
            throw new com.luky.nexusmind.exception.CustomException(
                    "图谱批次大小不得低于切片大小（" + chunk + "），且不得超过 100000 字符",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        f.setGraphBatchChars(n);
    }

    private void current(Long id, String token) {
        FileUpload f = files.findById(id).orElseThrow(CancellationException::new);
        if (!f.isGraphEnabled() || !Objects.equals(token, f.getGraphRunToken()))
            throw new CancellationException();
    }

    private void checkpoint(Long id, String token, Snapshot s, Consumer<FileUpload> update) {
        tx.executeWithoutResult(
                status -> {
                    FileUpload f = files.lockById(id).orElseThrow(CancellationException::new);
                    if (!f.isGraphEnabled() || !Objects.equals(token, f.getGraphRunToken()))
                        throw new CancellationException();
                    update.accept(f);
                    files.save(f);
                    GraphExtractionRun r = new GraphExtractionRun();
                    r.setFileId(id);
                    r.setToken(token);
                    r.setSnapshot(write(s));
                    runs.save(r);
                });
    }

    private CompletableFuture<Result> request(
            FileUpload f,
            String token,
            Snapshot s,
            String username,
            GraphBatchPlan.Batch batch,
            boolean dictionary) {
        // Freeze input before handing work to another thread.
        String input = "文档标题：" + s.title + "\n" + GraphBatchPlan.input(batch);
        if (!dictionary) input += "\n本批相关实体词典（局部指代仅在标明的位置有效）：\n" + relevant(s.glossary, batch);
        final String prompt = input;
        return scheduler
                .submit(
                        f.getId().toString(),
                        username,
                        config -> {
                            current(f.getId(), token);
                            if (dictionary) {
                                var located =
                                        client
                                                .dictionaryOnce(
                                                        config, username, prompt, s.instructions, false)
                                                .stream()
                                                .map(e -> locate(e, batch))
                                                .toList();
                                Map<String, Long> discarded =
                                        located.stream()
                                                .map(e -> validationIssue(e, batch, s.title))
                                                .filter(Objects::nonNull)
                                                .collect(
                                                        java.util.stream.Collectors.groupingBy(
                                                                issue -> issue,
                                                                LinkedHashMap::new,
                                                                java.util.stream.Collectors.counting()));
                                if (!discarded.isEmpty())
                                    logger.warn(
                                            "实体词典丢弃未通过校验的条目: fileId={}, batch={}, count={}, reasons={}",
                                            f.getId(),
                                            batch.index() + 1,
                                            discarded.values().stream().mapToLong(Long::longValue).sum(),
                                            discarded);
                                return located.stream()
                                        .filter(e -> valid(e, batch, s.title))
                                        .toList();
                            }
                            return client.relationsOnce(config, username, prompt, s.instructions);
                        })
                .handle(
                        (value, error) -> {
                            logger.info(
                                    "图谱批次请求结束: fileId={}, run={}, stage={}, batch={}, ranges={},"
                                            + " result={}",
                                    f.getId(),
                                    token,
                                    dictionary ? "DICTIONARY" : "RELATIONS",
                                    batch.index() + 1,
                                    batch.parts().stream()
                                            .map(p -> p.chunkId() + ":" + p.start() + "-" + p.end())
                                            .toList(),
                                    error == null ? "SUCCESS" : failureReason(error));
                            return new Result(batch, value, error);
                        });
    }

    private String relevant(List<DictionaryEntry> glossary, GraphBatchPlan.Batch batch) {
        String content = GraphBatchPlan.input(batch);
        var relevant =
                glossary.stream()
                        .filter(
                                e ->
                                        batch.parts().stream()
                                                        .anyMatch(
                                                                p ->
                                                                        p.chunkId() == e.chunkId()
                                                                                && e.start()
                                                                                        >= p.start()
                                                                                && e.end()
                                                                                        <= p.end())
                                                || (!KnowledgeGraphExtractionService
                                                                .isAmbiguousEntityName(e.name())
                                                        && content.contains(e.name())))
                        .toList();
        try {
            return mapper.writeValueAsString(relevant);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void process(
            FileUpload f,
            String token,
            Snapshot s,
            List<Work> works,
            String username,
            boolean dictionary,
            KnowledgeGraphExtractionService factory) {
        record Done(Work work, List<Result> results, boolean retry) {}
        BlockingQueue<Done> completed = new LinkedBlockingQueue<>();
        int pending = 0;
        for (Work w : works) {
            if (w.remaining.isEmpty()) continue;
            pending++;
            w.status = "RUNNING";
            w.error = null;
            var requests =
                    w.remaining.stream()
                            .map(b -> request(f, token, s, username, b, dictionary))
                            .toList();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                    .thenRun(
                            () ->
                                    completed.add(
                                            new Done(
                                                    w,
                                                    requests.stream()
                                                            .map(CompletableFuture::join)
                                                            .toList(),
                                                    false)));
        }
        checkpoint(f.getId(), token, s, x -> {});
        while (pending-- > 0) {
            Done done;
            try {
                while ((done = completed.poll(100, TimeUnit.MILLISECONDS)) == null) {
                    current(f.getId(), token);
                }
                current(f.getId(), token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
            Work w = done.work;
            List<Result> results = new ArrayList<>(done.results);
            List<CompletableFuture<Result>> retries = new ArrayList<>();
            // Remove only coordinates whose request finished. A crash retains all other unfinished
            // ranges.
            for (Result result : results)
                if (result.error != null && !done.retry) {
                    w.remaining.remove(result.batch);
                    var parts =
                            KnowledgeGraphExtractionClient.isTruncated(result.error)
                                    ? GraphBatchPlan.halve(result.batch)
                                    : List.of(result.batch);
                    w.remaining.addAll(parts);
                    parts.forEach(b -> retries.add(request(f, token, s, username, b, dictionary)));
                }
            for (Result result : results) {
                if (result.error != null) {
                    if (done.retry) {
                        w.error = failureReason(result.error);
                    }
                    continue;
                }
                w.remaining.remove(result.batch);
                if (dictionary) {
                    @SuppressWarnings("unchecked")
                    var entries = (List<DictionaryEntry>) result.value;
                    for (var entry : entries)
                        if (valid(entry, result.batch, s.title)) w.entries.add(entry);
                } else {
                    var extraction = (KnowledgeGraphExtractionClient.ExtractionResult) result.value;
                    checkpoint(
                            f.getId(),
                            token,
                            s,
                            currentFile -> {
                                Set<String> keys = new HashSet<>();
                                candidates
                                        .findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(f.getId())
                                        .forEach(c -> keys.add(key(c)));
                                for (var relation : extraction.relations()) {
                                    if (relation == null || relation.evidence() == null) continue;
                                    var source =
                                            result.batch.parts().stream()
                                                    .filter(
                                                            p ->
                                                                    Objects.equals(
                                                                                    p.chunkId(),
                                                                                    relation
                                                                                            .chunkId())
                                                                            && p.text()
                                                                                    .contains(
                                                                                            relation.evidence()
                                                                                                    .trim())
                                                                            && !relation.evidence()
                                                                                    .isBlank())
                                                    .findFirst();
                                    if (source.isEmpty()) continue;
                                    var candidate =
                                            factory.toCandidate(
                                                    currentFile,
                                                    relation,
                                                    Map.of(
                                                            source.get().chunkId(),
                                                            source.get().text()),
                                                    scopedMappings(
                                                            s.glossary,
                                                            source.get(),
                                                            relation.evidence()),
                                                    extraction.modelName());
                                    if (candidate != null
                                            && !KnowledgeGraphExtractionService.isLowValueRelation(
                                                    candidate)
                                            && keys.add(key(candidate))) {
                                        int start =
                                                source.get().start()
                                                        + source.get()
                                                                .text()
                                                                .indexOf(
                                                                        relation.evidence().trim());
                                        candidate.setEvidenceStart(start);
                                        candidate.setEvidenceEnd(
                                                start + relation.evidence().trim().length());
                                        candidates.save(candidate);
                                    }
                                }
                            });
                }
            }
            if (!retries.isEmpty()) {
                w.status = "RETRYING";
                pending++;
                CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
                        .thenRun(
                                () ->
                                        completed.add(
                                                new Done(
                                                        w,
                                                        retries.stream()
                                                                .map(CompletableFuture::join)
                                                                .toList(),
                                                        true)));
            } else {
                w.status = w.remaining.isEmpty() ? "SUCCESS" : "FAILED";
            }
            checkpoint(f.getId(), token, s, x -> {});
        }
    }

    private Map<String, KnowledgeGraphExtractionClient.EntityResolution> scopedMappings(
            List<DictionaryEntry> glossary, GraphBatchPlan.Part source, String evidence) {
        int start = source.start() + source.text().indexOf(evidence.trim()),
                end = start + evidence.trim().length();
        Map<String, List<DictionaryEntry>> names = new HashMap<>();
        for (DictionaryEntry e : glossary) {
            boolean local = KnowledgeGraphExtractionService.isAmbiguousEntityName(e.name());
            if (!local || (e.chunkId() == source.chunkId() && e.start() >= start && e.end() <= end))
                names.computeIfAbsent(
                                e.name().trim().toLowerCase(java.util.Locale.ROOT),
                                k -> new ArrayList<>())
                        .add(e);
        }
        Map<String, KnowledgeGraphExtractionClient.EntityResolution> result = new HashMap<>();
        names.forEach(
                (name, entries) -> {
                    if (entries.stream()
                                    .map(e -> e.type() + "|" + e.canonicalName())
                                    .distinct()
                                    .count()
                            == 1) {
                        DictionaryEntry e = entries.get(0);
                        result.put(
                                name,
                                new KnowledgeGraphExtractionClient.EntityResolution(
                                        e.name(), e.canonicalName(), e.type()));
                    }
                });
        return result;
    }

    private static String key(GraphCandidate c) {
        return c.getEvidenceChunkId()
                + "|"
                + c.getSubjectType()
                + "|"
                + c.getSubjectName()
                + "|"
                + c.getPredicate()
                + "|"
                + c.getObjectType()
                + "|"
                + c.getObjectName();
    }

    // The model supplies semantics and an exact quote; deterministic code owns UTF-16 offsets.
    private DictionaryEntry locate(DictionaryEntry e, GraphBatchPlan.Batch batch) {
        if (e == null
                || e.chunkId() == null
                || e.name() == null
                || e.name().isBlank()
                || e.evidence() == null
                || e.evidence().isBlank()) return e;
        List<DictionaryEntry> matches = new ArrayList<>();
        for (var p : batch.parts())
            if (p.chunkId() == e.chunkId()) {
                for (int evidenceAt = p.text().indexOf(e.evidence());
                        evidenceAt >= 0;
                        evidenceAt = p.text().indexOf(e.evidence(), evidenceAt + 1))
                    for (int mentionAt = e.evidence().indexOf(e.name());
                            mentionAt >= 0;
                            mentionAt = e.evidence().indexOf(e.name(), mentionAt + 1)) {
                        int start = p.start() + evidenceAt + mentionAt;
                        matches.add(
                                new DictionaryEntry(
                                        e.name(),
                                        e.type(),
                                        e.canonicalName(),
                                        e.chunkId(),
                                        start,
                                        start + e.name().length(),
                                        e.evidence()));
                    }
            }
        matches =
                matches.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        value -> value.start() + ":" + value.end(),
                                        value -> value,
                                        (left, right) -> left,
                                        LinkedHashMap::new))
                        .values()
                        .stream()
                        .toList();
        if (matches.size() == 1) return matches.get(0);
        if (e.start() != null && e.end() != null)
            return matches.stream()
                    .filter(value -> e.start().equals(value.start()) && e.end().equals(value.end()))
                    .findFirst()
                    .orElse(e);
        return e;
    }

    private boolean valid(DictionaryEntry e, GraphBatchPlan.Batch batch, String title) {
        return validationIssue(e, batch, title) == null;
    }

    private String validationIssue(DictionaryEntry e, GraphBatchPlan.Batch batch, String title) {
        if (e == null
                || e.chunkId() == null
                || e.start() == null
                || e.end() == null
                || e.name() == null
                || e.name().isBlank()
                || e.canonicalName() == null
                || e.canonicalName().isBlank()
                || e.type() == null
                || e.evidence() == null
                || e.evidence().isBlank()) return "MISSING_REQUIRED_FIELD";
        if (KnowledgeGraphExtractionService.isAmbiguousEntityName(e.canonicalName()))
            return "AMBIGUOUS_CANONICAL_NAME";
        var part =
                batch.parts().stream()
                        .filter(p -> p.chunkId() == e.chunkId())
                        .findFirst()
                        .orElse(null);
        if (part == null) return "CHUNK_NOT_IN_BATCH";
        if (e.start() < part.start() || e.end() > part.end() || e.end() <= e.start())
            return "INVALID_OFFSETS";
        if (!part.text()
                .substring(e.start() - part.start(), e.end() - part.start())
                .equals(e.name())) return "MENTION_MISMATCH";
        if (!evidenceCoversMention(e, part)) return "EVIDENCE_MISMATCH";
        if (!GraphBatchPlan.input(batch).contains(e.canonicalName())
                && !title.contains(e.canonicalName())) return "CANONICAL_NAME_NOT_IN_DOCUMENT";
        return null;
    }

    private boolean evidenceCoversMention(DictionaryEntry e, GraphBatchPlan.Part p) {
        for (int at = p.text().indexOf(e.evidence());
                at >= 0;
                at = p.text().indexOf(e.evidence(), at + 1))
            if (p.start() + at <= e.start() && p.start() + at + e.evidence().length() >= e.end())
                return true;
        return false;
    }

    private void mergeDictionary(FileUpload f, String token, Snapshot s, String username) {
        Map<String, List<DictionaryEntry>> grouped = new LinkedHashMap<>();
        for (Work w : s.dictionary)
            for (DictionaryEntry e : w.entries) {
                String key =
                        KnowledgeGraphExtractionService.isAmbiguousEntityName(e.name())
                                ? e.chunkId() + ":" + e.start() + ":" + e.end()
                                : e.type() + ":" + e.name();
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        s.glossary = new ArrayList<>();
        s.unresolved = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            var entries = group.getValue().stream().distinct().toList();
            if (entries.stream().map(DictionaryEntry::canonicalName).distinct().count() == 1) {
                s.glossary.addAll(entries);
                continue;
            }
            // Bounded conflicts: never send a whole-document dictionary back to the model.
            if (entries.size() > 20) {
                s.unresolved.add(group.getKey());
                continue;
            }
            String input;
            try {
                input = mapper.writeValueAsString(entries);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (input.length() > batchChars(f) + 2000) {
                s.unresolved.add(group.getKey());
                continue;
            }
            List<DictionaryEntry> resolved = List.of();
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    resolved =
                            scheduler
                                    .submit(
                                            f.getId().toString(),
                                            username,
                                            config -> {
                                                current(f.getId(), token);
                                                return client.dictionaryOnce(
                                                        config, username, input, s.instructions, true);
                                            })
                                    .join();
                    break;
                } catch (CompletionException e) {
                    if (attempt == 1) s.unresolved.add(group.getKey());
                }
            }
            Set<String> chosen = new HashSet<>();
            for (var e : resolved) if (entries.contains(e)) chosen.add(e.canonicalName());
            if (chosen.size() == 1)
                entries.stream()
                        .filter(e -> chosen.contains(e.canonicalName()))
                        .forEach(s.glossary::add);
            else if (!s.unresolved.contains(group.getKey())) s.unresolved.add(group.getKey());
        }
        checkpoint(f.getId(), token, s, x -> {});
    }
}
