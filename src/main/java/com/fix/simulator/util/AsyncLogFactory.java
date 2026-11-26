package com.fix.simulator.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Moves FIX session log writes off the session threads.
 *
 * <p>Every {@link Log} call is queued and written by one background thread, in the order the
 * calls were made. The delegate factory (for example {@code FileLogFactory}) still does the
 * writing, so the log content is unchanged. Session threads only enqueue a lambda, so slow
 * disk writes no longer hold up order matching or message sends.
 *
 * <p>The queue is unbounded: a slow disk grows memory instead of blocking the session.
 */
public final class AsyncLogFactory implements LogFactory {

    private static final Logger log = LoggerFactory.getLogger(AsyncLogFactory.class);

    private final LogFactory delegate;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean running = true;

    public AsyncLogFactory(LogFactory delegate) {
        this.delegate = delegate;
        this.writer = new Thread(this::drainLoop, "fix-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public Log create(SessionID sessionID) {
        return new AsyncLog(delegate.create(sessionID));
    }

    /** Wait until everything queued so far has been written, up to {@code timeoutMs}. */
    public boolean flush(long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        queue.add(done::countDown);
        try {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Flush pending writes and stop the writer thread. Safe to call more than once. */
    public void close() {
        if (!running) return;
        boolean drained = flush(5_000);
        running = false;
        writer.interrupt();
        if (!drained) log.warn("FIX log writer did not drain within 5 s; some log lines may be lost");
    }

    /** Number of log calls still waiting to be written. For monitoring and tests. */
    public int pending() {
        return queue.size();
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task != null) run(task);
            } catch (InterruptedException e) {
                // close() interrupts after the drain; keep looping until the queue is empty
            }
        }
    }

    private static void run(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("FIX log write failed: {}", e.getMessage());
        }
    }

    private final class AsyncLog implements Log {
        private final Log target;

        AsyncLog(Log target) { this.target = target; }

        @Override public void onIncoming(String message) { queue.add(() -> target.onIncoming(message)); }
        @Override public void onOutgoing(String message) { queue.add(() -> target.onOutgoing(message)); }
        @Override public void onEvent(String text)       { queue.add(() -> target.onEvent(text)); }
        @Override public void onErrorEvent(String text)  { queue.add(() -> target.onErrorEvent(text)); }
        @Override public void clear()                    { queue.add(target::clear); }
    }
}
