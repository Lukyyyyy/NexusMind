package com.luky.nexusmind.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class GraphModelSchedulerTest {
    static ModelConfigService.ResolvedModelConfig config(int tokens, int concurrency) {
        return new ModelConfigService.ResolvedModelConfig(
                1L,
                null,
                null,
                "model",
                "http://test",
                null,
                "test",
                null,
                null,
                tokens,
                null,
                null,
                null,
                concurrency,
                null,
                null,
                null);
    }

    @Test
    void deletionCancelsQueuedAndRunningJobsAndFreesModelSlot() throws Exception {
        var configs = mock(ModelConfigService.class);
        when(configs.resolveGraphExtractionConfig(anyString())).thenReturn(config(8192, 1));
        var scheduler = new GraphModelScheduler(configs);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var running = scheduler.submit("deleted", "u", c -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = scheduler.submit("deleted", "u", c -> fail("Cancelled job must not run"));
            scheduler.cancelDocument("deleted");
            assertTrue(running.isCancelled());
            assertTrue(queued.isCancelled());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(scheduler.submit("deleted", "u", c -> 1).isCompletedExceptionally());
            assertEquals(2, scheduler.submit("next", "u", c -> 2).get(2, TimeUnit.SECONDS));
        } finally { scheduler.close(); }
    }

    @Test
    void resetCancelsOldGenerationAndAllowsReplacementImmediately() throws Exception {
        var configs = mock(ModelConfigService.class);
        when(configs.resolveGraphExtractionConfig(anyString())).thenReturn(config(8192, 1));
        var scheduler = new GraphModelScheduler(configs);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var old =
                    scheduler.submit(
                            "rebuild",
                            "u",
                            c -> {
                                started.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                }
                                return 1;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            scheduler.resetDocument("rebuild");

            assertTrue(old.isCancelled());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(
                    2,
                    scheduler.submit("rebuild", "u", c -> 2).get(2, TimeUnit.SECONDS));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void sharesLimitAcrossDocumentsAndReadsLatestBudgetAtDispatch() throws Exception {
        var configs = mock(ModelConfigService.class);
        var latest = new AtomicReference<>(config(8192, 1));
        when(configs.resolveGraphExtractionConfig(anyString())).thenAnswer(i -> latest.get());
        var scheduler = new GraphModelScheduler(configs);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first =
                    scheduler.submit(
                            "A",
                            "u",
                            c -> {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                return c.maxTokens();
                            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second =
                    scheduler.submit("B", "u", ModelConfigService.ResolvedModelConfig::maxTokens);
            Thread.sleep(250);
            assertFalse(second.isDone());
            latest.set(config(16384, 1));
            release.countDown();
            assertEquals(8192, first.get(3, TimeUnit.SECONDS));
            assertEquals(16384, second.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    void documentsGetTurnsAndRequestsActuallyOverlap() throws Exception {
        var configs = mock(ModelConfigService.class);
        when(configs.resolveGraphExtractionConfig(anyString())).thenReturn(config(16384, 2));
        var scheduler = new GraphModelScheduler(configs);
        var gate = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var order = new CopyOnWriteArrayList<String>();
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                futures.add(
                        scheduler.submit(
                                "A",
                                "u",
                                c -> {
                                    int n = active.incrementAndGet();
                                    peak.accumulateAndGet(n, Math::max);
                                    started.countDown();
                                    try {
                                        gate.await(5, TimeUnit.SECONDS);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                    order.add("A");
                                    active.decrementAndGet();
                                    return "A";
                                }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            futures.add(
                    scheduler.submit(
                            "B",
                            "u",
                            c -> {
                                order.add("B");
                                return "B";
                            }));
            gate.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, peak.get());
            assertTrue(order.indexOf("B") < 6);
        } finally {
            gate.countDown();
            scheduler.close();
        }
    }
}
