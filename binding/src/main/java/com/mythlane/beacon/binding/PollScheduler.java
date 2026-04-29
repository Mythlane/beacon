package com.mythlane.beacon.binding;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PollScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PollScheduler.class);

    private final Supplier<ScheduledExecutorService> schedulerSource;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private boolean ownsExecutor;
    private boolean started;

    PollScheduler() {
        this(null);
    }

    PollScheduler(Supplier<ScheduledExecutorService> schedulerSource) {
        this.schedulerSource = schedulerSource;
    }

    void start(Runnable task, Duration period) {
        if (started) {
            throw new IllegalStateException("PollScheduler already started");
        }
        started = true;
        executor = resolveExecutor();
        long nanos = period.toNanos();
        future = executor.scheduleAtFixedRate(task, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    void cancel() {
        if (future != null) {
            try {
                future.cancel(false);
            } catch (Throwable t) {
                LOG.warn("PollScheduler cancel future failed", t);
            }
            future = null;
        }
        if (ownsExecutor && executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable t) {
                LOG.warn("PollScheduler shutdown executor failed", t);
            }
            executor = null;
        }
    }

    private ScheduledExecutorService resolveExecutor() {
        if (schedulerSource != null) {
            ScheduledExecutorService s = schedulerSource.get();
            if (s != null) {
                ownsExecutor = false;
                return s;
            }
        }
        ownsExecutor = true;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "beacon-poller");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(tf);
    }
}
