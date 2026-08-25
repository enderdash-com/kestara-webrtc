package com.enderdash.alloy.webrtc.internal;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Preserves callback order without blocking the native event dispatcher.
 *
 * @hidden
 */
public final class SerialExecutor implements Executor {
    private final Executor delegate;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean running;

    public SerialExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        boolean schedule;
        synchronized (tasks) {
            tasks.add(command);
            schedule = !running;
            if (schedule) {
                running = true;
            }
        }
        if (schedule) {
            scheduleNext();
        }
    }

    private void runNext() {
        Runnable task;
        synchronized (tasks) {
            task = tasks.poll();
            if (task == null) {
                running = false;
                return;
            }
        }
        try {
            task.run();
        } finally {
            scheduleNext();
        }
    }

    private void scheduleNext() {
        try {
            delegate.execute(this::runNext);
        } catch (RuntimeException error) {
            synchronized (tasks) {
                tasks.clear();
                running = false;
            }
            throw error;
        }
    }
}
