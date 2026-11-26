package com.fix.simulator.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AsyncLogFactory – session log writes happen off the caller thread")
class AsyncLogFactoryTest {

    private static final SessionID SID = new SessionID("FIX.4.2", "EXCHANGE", "CLIENT1");

    /** Records every call in order. {@code delayMs} simulates a slow disk. */
    static final class Recording implements LogFactory {
        final List<String> lines = new CopyOnWriteArrayList<>();
        volatile long delayMs = 0;
        volatile boolean failNext = false;

        @Override public Log create(SessionID s) {
            return new Log() {
                @Override public void onIncoming(String m) { write("in " + m); }
                @Override public void onOutgoing(String m) { write("out " + m); }
                @Override public void onEvent(String t)    { write("ev " + t); }
                @Override public void onErrorEvent(String t){ write("err " + t); }
                @Override public void clear()              { write("clear"); }
            };
        }

        void write(String line) {
            if (failNext) { failNext = false; throw new IllegalStateException("disk full"); }
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            lines.add(line);
        }
    }

    @Test
    void callsAreWrittenInOrder_afterFlush() {
        Recording rec = new Recording();
        AsyncLogFactory f = new AsyncLogFactory(rec);
        Log log = f.create(SID);
        log.onIncoming("a");
        log.onOutgoing("b");
        log.onEvent("c");
        log.onErrorEvent("d");
        log.clear();
        assertTrue(f.flush(5_000));
        assertEquals(List.of("in a", "out b", "ev c", "err d", "clear"), rec.lines);
        f.close();
    }

    @Test
    void caller_doesNotWaitForSlowDisk() {
        Recording rec = new Recording();
        rec.delayMs = 50;                       // each write takes 50 ms
        AsyncLogFactory f = new AsyncLogFactory(rec);
        Log log = f.create(SID);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) log.onIncoming("m" + i);   // would take ~1 s if synchronous
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 200, "enqueueing 20 log lines took " + elapsedMs + " ms");
        assertTrue(f.flush(10_000));
        assertEquals(20, rec.lines.size());
        f.close();
    }

    @Test
    void pendingDropsToZero_afterFlush() {
        Recording rec = new Recording();
        rec.delayMs = 5;
        AsyncLogFactory f = new AsyncLogFactory(rec);
        Log log = f.create(SID);
        for (int i = 0; i < 10; i++) log.onEvent("e" + i);
        assertTrue(f.flush(10_000));
        assertEquals(0, f.pending());
        f.close();
    }

    @Test
    void close_drainsQueuedLinesBeforeStopping() {
        Recording rec = new Recording();
        rec.delayMs = 2;
        AsyncLogFactory f = new AsyncLogFactory(rec);
        Log log = f.create(SID);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 15; i++) { log.onOutgoing("x" + i); expected.add("out x" + i); }
        f.close();
        assertEquals(expected, rec.lines);
    }

    @Test
    void closeTwice_isHarmless() {
        AsyncLogFactory f = new AsyncLogFactory(new Recording());
        f.close();
        assertDoesNotThrow(f::close);
    }

    @Test
    void failingWrite_doesNotStopLaterWrites() {
        Recording rec = new Recording();
        rec.failNext = true;
        AsyncLogFactory f = new AsyncLogFactory(rec);
        Log log = f.create(SID);
        log.onIncoming("lost");
        log.onIncoming("kept");
        assertTrue(f.flush(5_000));
        assertEquals(List.of("in kept"), rec.lines);
        f.close();
    }
}
