package com.fix.simulator.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileMessageReader – Messages tab history from the session logs")
class FileMessageReaderTest {

    private static final char SOH = '\u0001';

    @TempDir Path dir;
    private FileMessageReader reader;

    @BeforeEach
    void setUp() {
        reader = new FileMessageReader(dir);
    }

    /** One FIX line with the given tags, in order. */
    static String fix(String... tagValues) {
        StringBuilder sb = new StringBuilder("8=FIX.4.2");
        for (String tv : tagValues) sb.append(SOH).append(tv);
        sb.append(SOH).append("10=000");
        return sb.append('\n').toString();
    }

    static String order(String symbol, String time, String clOrdId) {
        return fix("35=D", "49=CLIENT1", "56=EXCHANGE", "52=" + time, "11=" + clOrdId, "55=" + symbol);
    }

    static String heartbeat(String time) {
        return fix("35=0", "49=CLIENT1", "56=EXCHANGE", "52=" + time);
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private void append(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Nested
    @DisplayName("counting and ordering")
    class Counting {

        @Test
        @DisplayName("session-level messages are not counted")
        void skipsSessionMessages() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log",
                    heartbeat("20260301-10:00:00.000")
                    + order("AAPL", "20260301-10:00:01.000", "A1")
                    + heartbeat("20260301-10:00:02.000")
                    + order("AAPL", "20260301-10:00:03.000", "A2"));
            FileMessageReader.Result r = reader.query(null, null, null, 0, 100);
            assertEquals(2, r.total());
            assertEquals(2, r.rows().size());
        }

        @Test
        @DisplayName("newest first within one session")
        void newestFirst() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log",
                    order("AAPL", "20260301-10:00:01.000", "A1")
                    + order("AAPL", "20260301-10:00:02.000", "A2")
                    + order("AAPL", "20260301-10:00:03.000", "A3"));
            List<Map<String, Object>> rows = reader.query(null, null, null, 0, 10).rows();
            assertEquals("2026-03-01T10:00:03Z", rows.get(0).get("time").toString().replace(".000", ""));
            assertEquals("2026-03-01T10:00:01Z", rows.get(2).get("time").toString().replace(".000", ""));
        }

        @Test
        @DisplayName("sessions are merged by SendingTime, not by file")
        void mergedAcrossSessions() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log",
                    order("AAPL", "20260301-10:00:01.000", "C1-1")
                    + order("AAPL", "20260301-10:00:05.000", "C1-2"));
            write("FIX.4.2-EXCHANGE-CLIENT2.messages.log",
                    order("MSFT", "20260301-10:00:03.000", "C2-1"));
            List<Map<String, Object>> rows = reader.query(null, null, null, 0, 10).rows();
            assertEquals(3, rows.size());
            assertEquals("C1-2", clOrdIdOf((List<?>) rows.get(0).get("tags")));
            assertEquals("C2-1", clOrdIdOf((List<?>) rows.get(1).get("tags")));
            assertEquals("C1-1", clOrdIdOf((List<?>) rows.get(2).get("tags")));
        }

        @Test
        @DisplayName("pages split the newest-first list with no overlap")
        void pages() throws IOException {
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 25; i++) {
                content.append(order("AAPL", String.format("20260301-10:00:%02d.000", i), "P" + i));
            }
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log", content.toString());
            FileMessageReader.Result p1 = reader.query(null, null, null, 0, 10);
            FileMessageReader.Result p2 = reader.query(null, null, null, 10, 10);
            FileMessageReader.Result p3 = reader.query(null, null, null, 20, 10);
            assertEquals(25, p1.total());
            assertEquals(10, p1.rows().size());
            assertEquals(10, p2.rows().size());
            assertEquals(5, p3.rows().size());
            assertEquals("P24", clOrdIdOf((List<?>) p1.rows().get(0).get("tags")));
            assertEquals("P14", clOrdIdOf((List<?>) p2.rows().get(0).get("tags")));
            assertTrue(reader.query(null, null, null, 30, 10).rows().isEmpty());
        }
    }

    @Nested
    @DisplayName("filters")
    class Filters {

        @BeforeEach
        void files() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log",
                    order("AAPL", "20260301-10:00:01.000", "A1")
                    + order("MSFT", "20260301-10:00:02.000", "M1"));
            write("FIX.4.2-EXCHANGE-CLIENT2.messages.log",
                    order("AAPL", "20260301-10:00:03.000", "A2"));
        }

        @Test
        @DisplayName("client keeps only that session")
        void client() throws IOException {
            FileMessageReader.Result r = reader.query("CLIENT2", null, null, 0, 10);
            assertEquals(1, r.total());
            assertEquals("CLIENT2", r.rows().get(0).get("compId"));
        }

        @Test
        @DisplayName("symbol matches tag 55 exactly")
        void symbol() throws IOException {
            assertEquals(2, reader.query(null, "AAPL", null, 0, 10).total());
            assertEquals(1, reader.query(null, "MSFT", null, 0, 10).total());
            assertEquals(0, reader.query(null, "NVDA", null, 0, 10).total());
        }

        @Test
        @DisplayName("text is case-insensitive and matches any tag value")
        void text() throws IOException {
            assertEquals(1, reader.query(null, null, "a2", 0, 10).total());
            assertEquals(3, reader.query(null, null, "CLIENT", 0, 10).total());
        }

        @Test
        @DisplayName("symbol and client combine")
        void combined() throws IOException {
            FileMessageReader.Result r = reader.query("CLIENT1", "AAPL", null, 0, 10);
            assertEquals(1, r.total());
            assertEquals("A1", clOrdIdOf((List<?>) r.rows().get(0).get("tags")));
        }
    }

    @Nested
    @DisplayName("live files")
    class Live {

        @Test
        @DisplayName("messages appended after a query appear in the next query")
        void followsGrowth() throws IOException {
            Path p = write("FIX.4.2-EXCHANGE-CLIENT1.messages.log", order("AAPL", "20260301-10:00:01.000", "A1"));
            assertEquals(1, reader.query(null, null, null, 0, 10).total());
            append(p, order("AAPL", "20260301-10:00:02.000", "A2"));
            FileMessageReader.Result r = reader.query(null, null, null, 0, 10);
            assertEquals(2, r.total());
            assertEquals("A2", clOrdIdOf((List<?>) r.rows().get(0).get("tags")));
        }

        @Test
        @DisplayName("a line still being written (no newline yet) is not counted")
        void partialLineIgnored() throws IOException {
            Path p = write("FIX.4.2-EXCHANGE-CLIENT1.messages.log", order("AAPL", "20260301-10:00:01.000", "A1"));
            append(p, "8=FIX.4.2\u000135=D\u000149=CLIENT1");   // no newline yet
            assertEquals(1, reader.query(null, null, null, 0, 10).total());
            append(p, "\u000156=EXCHANGE\u000152=20260301-10:00:09.000\u000111=A9\u000110=000\n");
            assertEquals(2, reader.query(null, null, null, 0, 10).total());
        }

        @Test
        @DisplayName("no log files means hasFiles is false")
        void noFiles() {
            assertFalse(reader.hasFiles());
        }
    }

    @Nested
    @DisplayName("row fields")
    class Rows {

        @Test
        @DisplayName("direction is OUT when the simulator sent it, IN when the client did")
        void direction() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log",
                    order("AAPL", "20260301-10:00:01.000", "IN1")
                    + fix("35=8", "49=EXCHANGE", "56=CLIENT1", "52=20260301-10:00:02.000", "11=IN1", "55=AAPL"));
            List<Map<String, Object>> rows = reader.query(null, null, null, 0, 10).rows();
            assertEquals("OUT", rows.get(0).get("direction"));
            assertEquals("IN", rows.get(1).get("direction"));
            assertEquals("8", rows.get(0).get("msgType"));
            assertEquals("AAPL", rows.get(0).get("symbol"));
            assertEquals("FIX.4.2:EXCHANGE->CLIENT1", rows.get(0).get("sessionId"));
            assertEquals("CLIENT1", rows.get(0).get("compId"));
        }

        @Test
        @DisplayName("tags come back in message order, with their values")
        void tags() throws IOException {
            write("FIX.4.2-EXCHANGE-CLIENT1.messages.log", order("AAPL", "20260301-10:00:01.000", "A1"));
            List<?> tags = (List<?>) reader.query(null, null, null, 0, 10).rows().get(0).get("tags");
            Map<?, ?> first = (Map<?, ?>) tags.get(0);
            assertEquals("8", first.get("tag"));
            assertEquals("FIX.4.2", first.get("val"));
        }
    }

    /** The value of tag 11 in a row's tag list. */
    private static String clOrdIdOf(List<?> tags) {
        for (Object o : tags) {
            Map<?, ?> t = (Map<?, ?>) o;
            if ("11".equals(t.get("tag"))) return (String) t.get("val");
        }
        return "";
    }
}
