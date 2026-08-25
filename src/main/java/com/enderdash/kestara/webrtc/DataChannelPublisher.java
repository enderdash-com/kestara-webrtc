package com.enderdash.kestara.webrtc;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

final class DataChannelPublisher implements Flow.Publisher<DataChannelMessage> {
    private final Executor executor;
    private final int capacity;
    private final Runnable onCancel;
    private final Queue<DataChannelMessage> messages = new ArrayDeque<>();
    private Subscription subscription;
    private Throwable failure;
    private boolean complete;
    private boolean draining;

    DataChannelPublisher(Executor executor, int capacity, Runnable onCancel) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.capacity = capacity;
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
    }

    @Override
    public synchronized void subscribe(Flow.Subscriber<? super DataChannelMessage> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (subscription != null) {
            subscriber.onSubscribe(new EmptySubscription());
            subscriber.onError(new IllegalStateException("A DataChannel supports one message subscriber"));
            return;
        }
        subscription = new Subscription(subscriber);
        subscriber.onSubscribe(subscription);
        scheduleDrain();
    }

    synchronized void publish(DataChannelMessage message) {
        if (complete || failure != null || subscription != null && subscription.cancelled) {
            message.close();
            return;
        }
        if (messages.size() >= capacity) {
            message.close();
            fail(new IllegalStateException("Native receive queue exceeded its configured capacity"));
            return;
        }
        messages.add(message);
        scheduleDrain();
    }

    synchronized void complete() {
        complete = true;
        if (subscription == null) {
            closeQueued();
        }
        scheduleDrain();
    }

    synchronized void fail(Throwable error) {
        failure = Objects.requireNonNull(error, "error");
        scheduleDrain();
    }

    synchronized void discard() {
        complete = true;
        closeQueued();
    }

    private synchronized void scheduleDrain() {
        if (!draining && subscription != null) {
            draining = true;
            executor.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            DataChannelMessage message;
            Flow.Subscriber<? super DataChannelMessage> subscriber;
            Throwable terminalFailure;
            boolean terminalComplete;
            synchronized (this) {
                if (subscription.cancelled) {
                    draining = false;
                    closeQueued();
                    return;
                }
                subscriber = subscription.subscriber;
                message = subscription.demand == 0 ? null : messages.poll();
                if (message != null) {
                    subscription.demand--;
                    terminalFailure = null;
                    terminalComplete = false;
                } else {
                    terminalFailure = messages.isEmpty() ? failure : null;
                    terminalComplete = messages.isEmpty() && complete && failure == null;
                    if (terminalFailure == null && !terminalComplete) {
                        draining = false;
                        return;
                    }
                    subscription.cancelled = true;
                    draining = false;
                }
            }
            if (message != null) {
                try {
                    subscriber.onNext(message);
                } catch (RuntimeException | Error error) {
                    message.close();
                    cancelFromCallback(error, subscriber);
                    return;
                }
            } else if (terminalFailure != null) {
                subscriber.onError(terminalFailure);
                return;
            } else if (terminalComplete) {
                subscriber.onComplete();
                return;
            }
        }
    }

    private void cancelFromCallback(
            Throwable error, Flow.Subscriber<? super DataChannelMessage> subscriber) {
        synchronized (this) {
            subscription.cancelled = true;
            draining = false;
            closeQueued();
        }
        onCancel.run();
        try {
            subscriber.onError(error);
        } catch (RuntimeException | Error ignored) {
            // Subscriber callbacks must not escape the configured callback executor.
        }
    }

    private void closeQueued() {
        DataChannelMessage message;
        while ((message = messages.poll()) != null) {
            message.close();
        }
    }

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super DataChannelMessage> subscriber;
        private long demand;
        private boolean cancelled;

        private Subscription(Flow.Subscriber<? super DataChannelMessage> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                synchronized (DataChannelPublisher.this) {
                    if (cancelled) {
                        return;
                    }
                    closeQueued();
                    failure = new IllegalArgumentException("Flow demand must be positive");
                    scheduleDrain();
                }
                return;
            }
            synchronized (DataChannelPublisher.this) {
                if (cancelled) {
                    return;
                }
                long updated = demand + count;
                demand = updated < 0 ? Long.MAX_VALUE : updated;
                scheduleDrain();
            }
        }

        @Override
        public void cancel() {
            synchronized (DataChannelPublisher.this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                closeQueued();
            }
            onCancel.run();
        }
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
