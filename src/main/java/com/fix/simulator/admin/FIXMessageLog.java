package com.fix.simulator.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Field;
import quickfix.FieldMap;
import quickfix.Message;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory log of raw FIX messages.
 *
 * <p>Stores the most recent {@value #MAX_MESSAGES} messages.  Each entry
 * records direction (IN from client, OUT to client), compId, sessionId,
 * msgType (tag 35), symbol (tag 55 if present), timestamp, and the full
 * tag–value list extracted from header + body + trailer.
 */
public class FIXMessageLog {

    private static final Logger log = LoggerFactory.getLogger(FIXMessageLog.class);

    /** Hard cap – oldest messages are dropped when exceeded. */
    private static final int MAX_MESSAGES = 20_000;

    public enum Direction { IN, OUT }

    // ── Entry ─────────────────────────────────────────────────────────────

    public static class Entry {
        public final long   id;
        public final String sessionId;
        public final String compId;
        public final Direction direction;
        public final String msgType;   // tag 35
        public final String symbol;    // tag 55, may be null
        public final Instant time;
        /** Ordered list of {tag, val} maps, header → body → trailer. */
        public final List<Map<String, Object>> tags;

        Entry(long id, String sessionId, String compId, Direction direction,
              String msgType, String symbol, Instant time,
              List<Map<String, Object>> tags) {
            this.id        = id;
            this.sessionId = sessionId;
            this.compId    = compId;
            this.direction = direction;
            this.msgType   = msgType;
            this.symbol    = symbol;
            this.time      = time;
            this.tags      = tags;
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Record one FIX message.
     *
     * @param sessionId  QuickFIX session-id string
     * @param compId     SenderCompID (for IN) or TargetCompID (for OUT)
     * @param direction  IN = from client, OUT = to client
     * @param message    the QuickFIX message object
     */
    public void record(String sessionId, String compId, Direction direction, Message message) {
        try {
            String msgType = safeGet(message.getHeader(), 35);
            String symbol  = safeGet(message, 55);
            List<Map<String, Object>> tags = extractTags(message);
            Entry e = new Entry(seq.incrementAndGet(), sessionId, compId,
                                direction, msgType, symbol, Instant.now(), tags);
            entries.add(e);
            // Drop oldest when over cap
            while (entries.size() > MAX_MESSAGES) entries.remove(0);
        } catch (Exception ex) {
            log.debug("FIXMessageLog.record skipped: {}", ex.getMessage());
        }
    }

    /**
     * Return all entries matching the given filters (all optional / blank = no filter).
     * Newest first.
     */
    public List<Entry> getMessages(String compId, String symbol) {
        return getMessages(compId, symbol, null);
    }

    /**
     * Return all entries matching the given filters (all optional / blank = no filter).
     * {@code text} is matched case-insensitively against any tag value, compId, symbol,
     * or msgType.  Newest first.
     */
    public List<Entry> getMessages(String compId, String symbol, String text) {
        List<Entry> all = new ArrayList<>(entries);
        Collections.reverse(all);
        return all.stream()
                .filter(e -> compId == null || compId.isBlank() || compId.equals(e.compId))
                .filter(e -> symbol == null || symbol.isBlank() || symbol.equals(e.symbol))
                .filter(e -> text == null || text.isBlank() || matchesText(e, text))
                .collect(Collectors.toList());
    }

    private static boolean matchesText(Entry e, String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (e.compId  != null && e.compId.toLowerCase(java.util.Locale.ROOT).contains(lower))  return true;
        if (e.symbol  != null && e.symbol.toLowerCase(java.util.Locale.ROOT).contains(lower))  return true;
        if (e.msgType != null && e.msgType.toLowerCase(java.util.Locale.ROOT).contains(lower)) return true;
        for (Map<String, Object> tag : e.tags) {
            Object val = tag.get("val");
            if (val != null && val.toString().toLowerCase(java.util.Locale.ROOT).contains(lower)) return true;
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String safeGet(FieldMap fm, int tag) {
        try { return fm.getString(tag); } catch (Exception e) { return null; }
    }

    private static List<Map<String, Object>> extractTags(Message msg) {
        List<Map<String, Object>> result = new ArrayList<>();
        addFields(msg.getHeader(), result);
        addFields(msg, result);
        addFields(msg.getTrailer(), result);
        return result;
    }

    private static void addFields(FieldMap fm, List<Map<String, Object>> out) {
        Iterator<Field<?>> it = fm.iterator();
        while (it.hasNext()) {
            Field<?> f = it.next();
            out.add(Map.of("tag", f.getTag(), "val", String.valueOf(f.getObject())));
        }
    }
}
