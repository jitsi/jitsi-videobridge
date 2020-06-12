package org.jitsi.videobridge.util;

import org.jetbrains.annotations.NotNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes submitted commands asynchronously through an Executor, while ensuring
 * that the commands are executed in the exact order that they were submitted in.
 * <p>
 * All methods in this class are thread-safe.
 */
public final class SequentialExecutor implements Executor {

    private final AtomicBoolean running = new AtomicBoolean();
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Executor executor;

    public SequentialExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Submit a command for asynchronous execution. The submitted command will
     * run after all previously submitted commands have completed.
     *
     * @param command The command to execute.
     */
    @Override
    public void execute(@NotNull Runnable command) {
        queue.add(command);
        triggerRun();
    }

    private void doRun() {
        try {
            Runnable runnable;
            while ((runnable = queue.poll()) != null) {
                // if run() throws, a new run will be triggered for the
                // remainder of the queue if it is not yet emptied
                runnable.run();
            }
        } finally {
            afterRun();
        }
    }

    private void afterRun() {
        running.set(false);
        if (!queue.isEmpty()) {
            triggerRun();
        }
    }

    /**
     * Atomically trigger a new run if none is running.
     */
    private void triggerRun() {
        if (running.compareAndSet(false, true)) {
            executor.execute(this::doRun);
        }
    }
}
