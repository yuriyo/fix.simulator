package com.fix.simulator.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Broadcasts JSON events to all connected admin WebSocket clients.
 *
 * <p>The matching engine hot path never blocks on serialisation or I/O:
 * <ul>
 *   <li>{@link #markBookDirty(String)} — O(1) Set.add; book levels are computed
 *       once per symbol per tick, not once per order.
 *   <li>{@link #publish(Object)} — O(1) queue offer for trades / order events.
 * </ul>
 *
 * <p>A single background thread fires every {@value #TICK_MS} ms, computes
 * the current book snapshot for each dirty symbol, drains queued events, and
 * sends one JSON array to every connected browser.
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final int TICK_MS = 200;

    private final Set<WsContext> clients = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Symbols whose book changed since the last tick. */
    private final Set<String> dirtyBooks = ConcurrentHashMap.newKeySet();

    /** Non-book events (trades, order_events, …) waiting for the next tick. */
    private final ConcurrentLinkedQueue<Object> pendingEvents = new ConcurrentLinkedQueue<>();

    /**
     * Provides the current book snapshot for a given symbol.
     * Set once by MatchingEngine via {@link #setBookSnapshotProvider}.
     */
    private volatile Function<String, Map<String, Object>> bookSnapshotProvider;

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eventbus-ticker");
        t.setDaemon(true);
        return t;
    });

    public EventBus() {
        ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    public void subscribe(WsContext ctx)   { clients.add(ctx); }
    public void unsubscribe(WsContext ctx) { clients.remove(ctx); }

    /** Called once by MatchingEngine so the tick thread can build book snapshots. */
    public void setBookSnapshotProvider(Function<String, Map<String, Object>> fn) {
        this.bookSnapshotProvider = fn;
    }

    // ── Hot-path API (called from the matching engine thread) ─────────────

    /** Mark a symbol's book as changed. O(1). The snapshot is sent on the next tick. */
    public void markBookDirty(String symbol) {
        if (!clients.isEmpty()) dirtyBooks.add(symbol);
    }

    /** Enqueue a non-book event (trade, order_event, etc.). O(1). */
    public void publish(Object event) {
        if (!clients.isEmpty()) pendingEvents.offer(event);
    }

    // ── Tick (background thread, every 200 ms) ────────────────────────────

    private void tick() {
        if (clients.isEmpty()) {
            dirtyBooks.clear();
            while (pendingEvents.poll() != null) { /* drain */ }
            return;
        }

        List<Object> batch = new ArrayList<>();

        // All queued non-book events
        Object e;
        while ((e = pendingEvents.poll()) != null) batch.add(e);

        // One book snapshot per dirty symbol
        Function<String, Map<String, Object>> fn = this.bookSnapshotProvider;
        if (fn != null) {
            Iterator<String> it = dirtyBooks.iterator();
            while (it.hasNext()) {
                String sym = it.next();
                it.remove();
                try {
                    Map<String, Object> snap = fn.apply(sym);
                    if (snap != null) batch.add(snap);
                } catch (Exception ex) {
                    log.debug("EventBus: book snapshot failed for {}: {}", sym, ex.getMessage());
                }
            }
        }

        if (batch.isEmpty()) return;

        try {
            String json = mapper.writeValueAsString(batch);
            clients.removeIf(ctx -> {
                try { ctx.send(json); return false; }
                catch (Exception ex) { return true; }
            });
        } catch (Exception ex) {
            log.warn("EventBus tick serialisation failed: {}", ex.getMessage());
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    public int subscriberCount() { return clients.size(); }
    public boolean hasClients()  { return !clients.isEmpty(); }
}
