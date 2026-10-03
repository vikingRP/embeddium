package org.embeddedt.embeddium.impl.gametest.tests;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ChunkCompletionChecks {
    static void checkPublication() {
        var executor = Executors.newFixedThreadPool(2);
        var publishing = new CountDownLatch(1);
        var allowPublication = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var published = new AtomicBoolean();
        var collector = new ChunkJobCollector(1, result -> {
            publishing.countDown();
            try {
                if (!allowPublication.await(5, TimeUnit.SECONDS)) throw new AssertionError("Publication timed out");
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            published.set(true);
        });
        collector.addSubmittedJob(new ChunkJob() {
            public boolean isStarted() { return true; }
            public boolean isCancelled() { return false; }
            public void setCancelled() { }
            public void execute(ChunkBuildContext context) { throw new AssertionError("Already started"); }
        });
        try {
            var worker = executor.submit(() -> collector.onJobFinished(ChunkJobResult.successfully(null)));
            if (!publishing.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
            var render = executor.submit(() -> {
                waiting.countDown();
                collector.awaitCompletion(null); // Already-started job: no stealing or actual builder needed.
                if (!published.get()) throw new AssertionError("Render thread woke before result publication");
            });
            if (!waiting.await(5, TimeUnit.SECONDS)) throw new AssertionError("Waiter did not start");
            try {
                render.get(100, TimeUnit.MILLISECONDS);
                throw new AssertionError("Wait completed before publication");
            } catch (TimeoutException expected) {
                // Must wait while the result is still being handed to the upload queue.
            }
            allowPublication.countDown();
            worker.get(5, TimeUnit.SECONDS);
            render.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
        }
    }
}
