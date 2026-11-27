package com.fix.simulator.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Serves the FIX message history from the QuickFIX/J message log files in the log directory.
 *
 * <p>Each file {@code FIX.4.2-<sender>-<target>.messages.log} holds one session, one FIX message per
 * line. The reader keeps a byte-offset index per file, so paging touches only the lines it returns.
 * The index is extended when a file grows, so it follows a live session without a restart.
 *
 * <p>Session-level messages (heartbeat, test request, resend, reject, logout, logon) are skipped,
 * so the count matches the application messages the dashboard reports.
 *
 * <p>Results are newest first, merged across sessions by the SendingTime (tag 52) of each message.
 */
public final class FileMessageReader {

    private static final Logger log = LoggerFactory.getLogger(FileMessageReader.class);

    /** The simulator's own CompID (SenderCompID in server.cfg). */
    static final String SIM_COMP_ID = "EXCHANGE";

    private static final char SOH = '\u0001';
    private static final DateTimeFormatter FIX_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
    private static final Set<String> SESSION_MSG_TYPES = Set.of("0", "1", "2", "3", "4", "5", "A");

    /** Result of one query: total matching messages and the requested page. */
    public record Result(long total, List<Map<String, Object>> rows) {}

    private final Path dir;
    private final Map<String, FileIndex> indexes = new LinkedHashMap<>();   // keyed by file name

    public FileMessageReader(Path dir) {
        this.dir = dir;
    }

    /** True if at least one session message log exists. */
    public synchronized boolean hasFiles() {
        return !listFiles().isEmpty();
    }

    private List<Path> listFiles() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("FIX.") && n.endsWith(".messages.log");
            }).sorted().toList();
        } catch (IOException e) {
            log.warn("Cannot list message logs in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * One page of messages, newest first.
     *
     * @param clientId  only this client's sessions, or null
     * @param symbol    only messages for this symbol (tag 55), or null
     * @param text      only messages containing this text (case-insensitive), or null
     * @param offset    index of the first row to return, counted from the newest (0)
     * @param limit     maximum number of rows to return
     */
    public synchronized Result query(String clientId, String symbol, String text, int offset, int limit) throws IOException {
        List<FileIndex> files = new ArrayList<>();
        for (Path p : listFiles()) {
            FileIndex fi = indexes.computeIfAbsent(p.getFileName().toString(), n -> new FileIndex(p));
            fi.refresh();
            if (clientId == null || clientId.isBlank() || clientId.equals(fi.client)) files.add(fi);
        }
        boolean filtered = (symbol != null && !symbol.isBlank()) || (text != null && !text.isBlank());
        long offsetWanted = Math.max(0, offset);
        long limitWanted = Math.max(1, limit);
        long total = 0;
        List<Candidate> window = new ArrayList<>();

        if (!filtered) {
            for (FileIndex f : files) total += f.count;
            // The newest (page * pageSize) lines of each file are enough to fill the page.
            long need = offsetWanted + limitWanted;
            List<Candidate> all = new ArrayList<>();
            for (FileIndex f : files) {
                int from = (int) Math.max(0, f.count - need);
                for (int i = f.count - 1; i >= from; i--) all.add(new Candidate(f, i, f.sendingTime(i)));
            }
            all.sort(Comparator.comparing((Candidate c) -> c.time).reversed());
            window = slice(all, offsetWanted, limitWanted);
        } else {
            FilterCache cache = filterCache(symbol, text);
            for (FileIndex f : files) cache.extend(f);
            total = cache.total(files);
            window = cache.page(files, offsetWanted, limitWanted);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Candidate c : window) rows.add(c.file.toRow(c.index));
        return new Result(total, rows);
    }

    private static List<Candidate> slice(List<Candidate> sorted, long from, long count) {
        if (from >= sorted.size()) return List.of();
        return sorted.subList((int) from, (int) Math.min(sorted.size(), from + count));
    }

    private record Candidate(FileIndex file, int index, String time) {}

    // ── Filtered searches ────────────────────────────────────────────────────

    /** Recent filter results, so repeated pages and reloads do not rescan the logs. */
    private final Map<String, FilterCache> filterCaches = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, FilterCache> e) {
            return size() > 8;
        }
    };

    private FilterCache filterCache(String symbol, String text) {
        String key = (symbol == null ? "" : symbol) + "\u0000" + (text == null ? "" : text.toLowerCase(Locale.ROOT));
        return filterCaches.computeIfAbsent(key, k -> new FilterCache(symbol, text == null ? null : text.toLowerCase(Locale.ROOT)));
    }

    /**
     * The matching message indexes of each session, kept up to date as the logs grow.
     * Each session is scanned once per filter; later calls scan only new lines.
     */
    static final class FilterCache {
        private final String symbol;
        private final String needle;
        private final Map<String, Matches> perFile = new HashMap<>();

        FilterCache(String symbol, String needle) {
            this.symbol = symbol == null || symbol.isBlank() ? null : symbol;
            this.needle = needle == null || needle.isBlank() ? null : needle;
        }

        /** Scan the messages of {@code f} that are not scanned yet. */
        void extend(FileIndex f) throws IOException {
            Matches m = perFile.computeIfAbsent(f.path.getFileName().toString(), k -> new Matches());
            for (int i = m.scanned; i < f.count; i++) {
                String line = f.line(i);
                if (symbol != null && !symbol.equals(FileIndex.tag(line, 55))) continue;
                if (needle != null && !line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                m.add(i);
            }
            m.scanned = f.count;
        }

        long total(List<FileIndex> files) {
            long t = 0;
            for (FileIndex f : files) {
                Matches m = perFile.get(f.path.getFileName().toString());
                if (m != null) t += m.size;
            }
            return t;
        }

        /** The page of matches, newest first across sessions, by SendingTime. */
        List<Candidate> page(List<FileIndex> files, long from, long count) throws IOException {
            // Walk each session from its newest match, and take the newest of the heads each step.
            Map<FileIndex, Integer> cursor = new HashMap<>();
            PriorityQueue<Candidate> heads = new PriorityQueue<>(Comparator.comparing((Candidate c) -> c.time).reversed());
            for (FileIndex f : files) {
                Matches m = perFile.get(f.path.getFileName().toString());
                if (m == null || m.size == 0) continue;
                int at = m.size - 1;
                cursor.put(f, at);
                heads.add(new Candidate(f, m.idx[at], f.sendingTime(m.idx[at])));
            }
            List<Candidate> out = new ArrayList<>();
            long pos = 0;
            while (!heads.isEmpty() && out.size() < count) {
                Candidate c = heads.poll();
                if (pos++ >= from) out.add(c);
                int at = cursor.get(c.file) - 1;
                cursor.put(c.file, at);
                if (at >= 0) {
                    int idx = perFile.get(c.file.path.getFileName().toString()).idx[at];
                    heads.add(new Candidate(c.file, idx, c.file.sendingTime(idx)));
                }
            }
            return out;
        }

        static final class Matches {
            int[] idx = new int[64];
            int size = 0;
            int scanned = 0;

            void add(int i) {
                if (size == idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
                idx[size++] = i;
            }
        }
    }

    // ── One session file ─────────────────────────────────────────────────────

    /** Byte-offset index of one session log, extended as the file grows. */
    static final class FileIndex {
        final Path path;
        final String sender;
        final String target;
        final String client;       // the non-simulator side of the session
        private long[] offsets = new long[1024];
        private long[] ends = new long[1024];     // byte position just after each message's newline
        int count = 0;             // application messages indexed
        private long indexedTo = 0; // byte position just after the last newline read

        FileIndex(Path path) {
            this.path = path;
            // FIX.4.2-<sender>-<target>.messages.log
            String rest = path.getFileName().toString().substring("FIX.4.2-".length());
            rest = rest.substring(0, rest.length() - ".messages.log".length());
            int dash = rest.indexOf('-');
            this.sender = dash < 0 ? rest : rest.substring(0, dash);
            this.target = dash < 0 ? "" : rest.substring(dash + 1);
            this.client = SIM_COMP_ID.equals(sender) ? target : sender;
        }

        /**
         * Index the application messages appended since the last call. Session-level messages are
         * read but not indexed. Only complete lines (ending in a newline) are indexed.
         */
        void refresh() throws IOException {
            if (!Files.exists(path)) return;
            long size = Files.size(path);
            if (size <= indexedTo) return;
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(indexedTo);
                byte[] buf = new byte[1 << 16];
                ByteArrayOutputStream line = new ByteArrayOutputStream(512);
                long lineStart = indexedTo;
                long pos = indexedTo;
                while (pos < size) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, size - pos));
                    if (n <= 0) break;
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == '\n') {
                            String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                            if (!SESSION_MSG_TYPES.contains(msgType(text))) add(lineStart, pos + i + 1);
                            line.reset();
                            lineStart = pos + i + 1;
                            indexedTo = lineStart;
                        } else {
                            line.write(b);
                        }
                    }
                    pos += n;
                }
            }
        }

        private void add(long start, long end) {
            if (count == offsets.length) {
                offsets = Arrays.copyOf(offsets, offsets.length * 2);
                ends = Arrays.copyOf(ends, ends.length * 2);
            }
            offsets[count] = start;
            ends[count] = end;
            count++;
        }

        private RandomAccessFile reader;

        /** One read handle per session file, kept open. The index only reads bytes it has already seen. */
        private RandomAccessFile reader() throws IOException {
            if (reader == null) reader = new RandomAccessFile(path.toFile(), "r");
            return reader;
        }

        /** The text of indexed message {@code i}, without its line terminator. */
        String line(int i) throws IOException {
            long start = offsets[i];
            long end = ends[i];
            int len = (int) (end - start - 1);   // drop the '\n'
            byte[] b = new byte[Math.max(0, len)];
            RandomAccessFile raf = reader();
            raf.seek(start);
            raf.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        /** The value of a tag in a raw message, or null. */
        static String tag(String raw, int tag) {
            String key = SOH + Integer.toString(tag) + "=";
            int at = (SOH + raw).indexOf(key);
            if (at < 0) return null;
            int from = at + key.length() - 1;   // SOH is at index 0 of the padded string
            int to = raw.indexOf(SOH, from);
            return to < 0 ? raw.substring(from) : raw.substring(from, to);
        }

        String msgType(String raw) {
            String v = tag(raw, 35);
            return v == null ? "" : v;
        }

        /** SendingTime as a sortable string "yyyyMMdd-HH:mm:ss.SSS" ("" if absent). */
        String sendingTime(String raw) {
            String v = tag(raw, 52);
            return v == null ? "" : v;
        }

        String sendingTime(int i) throws IOException {
            return sendingTime(line(i));
        }

        /** One message as the Messages tab shows it, or null for session-level messages. */
        Map<String, Object> toRow(int i) throws IOException {
            return rowFor(line(i), i);
        }

        private Map<String, Object> rowFor(String raw, int i) {
            Map<String, Object> m = new LinkedHashMap<>();
            String sender49 = tag(raw, 49);
            String dirName = sender.equals(sender49) ? "OUT" : "IN";
            m.put("id", ((long) path.getFileName().toString().hashCode() << 32) | (i & 0xffffffffL));
            m.put("sessionId", "FIX.4.2:" + sender + "->" + target);
            m.put("compId", client);
            m.put("direction", dirName);
            m.put("msgType", msgType(raw));
            m.put("symbol", tag(raw, 55));
            m.put("time", toInstant(sendingTime(raw)));
            List<Map<String, Object>> tags = new ArrayList<>();
            for (String pair : raw.split(String.valueOf(SOH))) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("tag", pair.substring(0, eq));
                    t.put("val", pair.substring(eq + 1));
                    tags.add(t);
                }
            }
            m.put("tags", tags);
            return m;
        }

        private static String toInstant(String fixTime) {
            if (fixTime.isEmpty()) return null;
            try {
                return LocalDateTime.parse(fixTime, FIX_TIME).toInstant(ZoneOffset.UTC).toString();
            } catch (RuntimeException e) {
                return Instant.EPOCH.toString();
            }
        }
    }
}
