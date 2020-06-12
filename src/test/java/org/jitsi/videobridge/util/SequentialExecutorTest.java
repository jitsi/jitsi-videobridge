package org.jitsi.videobridge.util;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SequentialExecutorTest {

    private final Queue<Runnable> runnables = new ArrayDeque<>();
    private final SequentialExecutor sequentialExecutor = new SequentialExecutor(runnables::add);

    @Test
    public void shouldRunCommandInExecutor() {
        AtomicInteger commandInvocations = new AtomicInteger();
        sequentialExecutor.execute(commandInvocations::incrementAndGet);
        assertEquals(0, commandInvocations.get());
        assertEquals(1, runnables.size());
        runNextRunnable();
        assertEquals(1, commandInvocations.get());
        assertEquals(0, runnables.size());
    }

    @Test
    public void shouldRunCommandsSequentially() {
        List<Integer> expectedInvocations = Arrays.asList(1, 2, 3);
        List<Integer> actualInvocations = new ArrayList<>();
        expectedInvocations.forEach(i ->
                sequentialExecutor.execute(() -> actualInvocations.add(i))
        );
        assertEquals(0, actualInvocations.size());
        assertEquals(1, runnables.size());
        runNextRunnable();
        assertEquals(expectedInvocations, actualInvocations);
        assertEquals(0, runnables.size());
    }

    @Test
    public void shouldTriggerNextRun() {
        sequentialExecutor.execute(() -> {
        });
        runNextRunnable();
        shouldRunCommandInExecutor();
    }

    @Test
    public void shouldTriggerRunForRemainingCommands() {
        AtomicInteger commandInvocations = new AtomicInteger();

        sequentialExecutor.execute(() -> {
            throw new ExpectedTestException();
        });
        sequentialExecutor.execute(commandInvocations::incrementAndGet);
        assertEquals(1, runnables.size());

        assertThrows(ExpectedTestException.class, this::runNextRunnable);
        assertEquals(0, commandInvocations.get());
        assertEquals(1, runnables.size());

        runNextRunnable();
        assertEquals(1, commandInvocations.get());
        assertEquals(0, runnables.size());
    }

    @Test
    public void shouldAcceptCommandsWhileRunning() {
        List<Integer> expectedInvocations = Arrays.asList(1, 2, 3, 4);
        ArrayList<Integer> actualInvocations = new ArrayList<>();

        sequentialExecutor.execute(() -> {
            sequentialExecutor.execute(() -> {
                sequentialExecutor.execute(() -> {
                    actualInvocations.add(3);
                });
                sequentialExecutor.execute(() -> {
                    actualInvocations.add(4);
                });
                actualInvocations.add(2);
            });
            actualInvocations.add(1);
        });
        assertEquals(1, runnables.size());
        assertEquals(0, actualInvocations.size());

        runNextRunnable();

        assertEquals(expectedInvocations, actualInvocations);
        assertEquals(0, runnables.size());
    }

    private void runNextRunnable() {
        assertFalse(runnables.isEmpty());
        runnables.remove().run();
    }

    private static class ExpectedTestException extends RuntimeException {
    }
}