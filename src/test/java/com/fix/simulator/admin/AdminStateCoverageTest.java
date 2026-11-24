package com.fix.simulator.admin;

import io.javalin.websocket.WsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Branch and path coverage for {@link FIXMessageLog}, {@link EventBus} and {@link InstrumentStateManager}. */
@DisplayName("Admin state holders – message log, event bus, instrument states")
class AdminStateCoverageTest {

    // ═════════════════════════════════════════════════════════════════════════
    //  FIXMessageLog
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FIXMessageLog")
    class MessageLog {

        FIXMessageLog log;

        @BeforeEach
        void init() { log = new FIXMessageLog(); }

        static NewOrderSingle order(String clOrdId, String symbol) throws Exception {
            NewOrderSingle m = new NewOrderSingle();
            m.set(new ClOrdID(clOrdId));
            if (symbol != null) m.set(new Symbol(symbol));
            m.set(new Side(Side.BUY));
            m.set(new OrdType(OrdType.LIMIT));
            m.set(new OrderQty(5));
            m.set(new Price(10.0));
            m.set(new TransactTime());
            return m;
        }

        @Test
        void record_thenQueryNewestFirst() throws Exception {
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, order("A1", "AAPL"));
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.OUT, order("A2", "AAPL"));
            List<FIXMessageLog.Entry> all = log.getMessages(null, null);
            assertEquals(2, all.size());
            assertEquals(FIXMessageLog.Direction.OUT, all.get(0).direction);
            assertEquals("D", all.get(0).msgType);
            assertEquals("AAPL", all.get(0).symbol);
        }

        @Test
        void filterByCompIdAndSymbol() throws Exception {
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, order("A1", "AAPL"));
            log.record("S2", "CLIENT2", FIXMessageLog.Direction.IN, order("B1", "MSFT"));
            assertEquals(1, log.getMessages("CLIENT1", null).size());
            assertEquals(1, log.getMessages(null, "MSFT").size());
            assertEquals(0, log.getMessages("CLIENT1", "MSFT").size());
            assertEquals(2, log.getMessages("", "  ").size());   // blank = no filter
        }

        @Test
        void textFilter_matchesCompIdSymbolMsgTypeAndTagValue() throws Exception {
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, order("ORDER-XYZ", "AAPL"));
            assertEquals(1, log.getMessages(null, null, "client1").size());   // compId
            assertEquals(1, log.getMessages(null, null, "aapl").size());      // symbol
            assertEquals(1, log.getMessages(null, null, "d").size());         // msgType
            assertEquals(1, log.getMessages(null, null, "xyz").size());       // tag value
            assertEquals(0, log.getMessages(null, null, "nomatch").size());
            assertEquals(1, log.getMessages(null, null, "   ").size());       // blank text = no filter
            assertEquals(1, log.getMessages(null, null, null).size());
        }

        @Test
        void messageWithoutSymbol_recordedWithNullSymbol() throws Exception {
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, order("A1", null));
            FIXMessageLog.Entry e = log.getMessages(null, null).get(0);
            assertNull(e.symbol);
            assertEquals(0, log.getMessages(null, "AAPL").size());
        }

        @Test
        void nullMessage_isSkippedSilently() {
            assertDoesNotThrow(() -> log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, null));
            assertTrue(log.getMessages(null, null).isEmpty());
        }

        @Test
        void capacity_oldestDroppedAtLimit() throws Exception {
            NewOrderSingle m = order("A", "AAPL");
            for (int i = 0; i < 20_005; i++) log.record("S", "C", FIXMessageLog.Direction.IN, m);
            List<FIXMessageLog.Entry> all = log.getMessages(null, null);
            assertEquals(20_000, all.size());
            assertEquals(20_005L, all.get(0).id);           // newest retained
            assertEquals(6L, all.get(all.size() - 1).id);   // oldest five dropped
        }

        @Test
        void textFilter_handlesMissingCompIdSymbolAndMsgType() throws Exception {
            log.record("S1", null, FIXMessageLog.Direction.IN, order("A1", null));
            log.record("S1", "C", FIXMessageLog.Direction.IN, new quickfix.Message());   // no MsgType, no symbol
            assertEquals(0, log.getMessages(null, null, "zzzz-not-present").size());
            assertEquals(1, log.getMessages(null, null, "a1").size());
        }

        @Test
        void entryTags_parsedLazilyAndCached() throws Exception {
            log.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, order("A1", "AAPL"));
            FIXMessageLog.Entry e = log.getMessages(null, null).get(0);
            List<Map<String, Object>> first = e.getTags();
            assertSame(first, e.getTags());
            assertTrue(first.stream().anyMatch(t -> "11".equals(t.get("tag")) && "A1".equals(t.get("val"))));
        }

        @Test
        void entryWithNullRaw_hasNoTags() {
            FIXMessageLog.Entry e = new FIXMessageLog.Entry(1, "S", "C", FIXMessageLog.Direction.IN,
                    "D", null, Instant.now(), null);
            assertTrue(e.getTags().isEmpty());
        }

        @Test
        void entryRawWithMalformedPairs_skipsThem() {
            String raw = "8=FIX.4.2\u0001noequals\u0001=novalue\u000135=D\u0001";
            FIXMessageLog.Entry e = new FIXMessageLog.Entry(1, "S", "C", FIXMessageLog.Direction.IN,
                    "D", null, Instant.now(), raw);
            List<Map<String, Object>> tags = e.getTags();
            assertEquals(2, tags.size());
            assertEquals("35", tags.get(1).get("tag"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  EventBus
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EventBus")
    class Events {

        EventBus bus;

        @BeforeEach
        void init() { bus = new EventBus(); }

        static void tick(EventBus b) throws Exception {
            Method m = EventBus.class.getDeclaredMethod("tick");
            m.setAccessible(true);
            m.invoke(b);
        }

        WsContext subscribe() {
            WsContext ctx = mock(WsContext.class);
            bus.subscribe(ctx);
            return ctx;
        }

        @Test
        void subscriptionBookkeeping() {
            assertFalse(bus.hasClients());
            WsContext a = subscribe();
            subscribe();
            assertEquals(2, bus.subscriberCount());
            assertTrue(bus.hasClients());
            bus.unsubscribe(a);
            assertEquals(1, bus.subscriberCount());
        }

        @Test
        void publishAndMarkDirty_ignoredWithoutClients() throws Exception {
            bus.publish("ignored");
            bus.markBookDirty("AAPL");
            WsContext ctx = subscribe();
            tick(bus);
            verify(ctx, never()).send(anyString());
        }

        @Test
        void tickWithoutClients_drainsQueuedEvents() throws Exception {
            WsContext ctx = subscribe();
            bus.publish("queued");
            bus.markBookDirty("AAPL");
            bus.unsubscribe(ctx);
            tick(bus);                              // clears queues while nobody listens
            WsContext late = subscribe();
            tick(bus);
            verify(late, never()).send(anyString());
        }

        @Test
        void tickSendsQueuedEventsAsJsonArray() throws Exception {
            WsContext ctx = subscribe();
            bus.publish(Map.of("type", "trade", "symbol", "AAPL"));
            tick(bus);
            verify(ctx).send(org.mockito.ArgumentMatchers.argThat((String s) ->
                    s.startsWith("[") && s.contains("\"trade\"")));
        }

        @Test
        void tickEmitsSnapshotFromProvider() throws Exception {
            bus.setBookSnapshotProvider(sym -> Map.<String, Object>of("type", "book_update", "symbol", sym));
            WsContext ctx = subscribe();
            bus.markBookDirty("AAPL");
            tick(bus);
            verify(ctx).send(org.mockito.ArgumentMatchers.contains("book_update"));
        }

        @Test
        void tickSkipsNullSnapshot() throws Exception {
            bus.setBookSnapshotProvider(sym -> null);
            WsContext ctx = subscribe();
            bus.markBookDirty("AAPL");
            tick(bus);
            verify(ctx, never()).send(anyString());
        }

        @Test
        void tickSurvivesProviderException() throws Exception {
            bus.setBookSnapshotProvider(sym -> { throw new IllegalStateException("boom"); });
            WsContext ctx = subscribe();
            bus.markBookDirty("AAPL");
            bus.publish(Map.of("type", "trade"));
            tick(bus);
            verify(ctx).send(anyString());
        }

        @Test
        void tickWithDirtyButNoProvider_sendsOnlyEvents() throws Exception {
            WsContext ctx = subscribe();
            bus.markBookDirty("AAPL");
            tick(bus);                               // no provider: nothing to send
            verify(ctx, never()).send(anyString());
            bus.publish(Map.of("type", "trade"));
            tick(bus);
            verify(ctx).send(anyString());
        }

        @Test
        void tickWithNothingQueued_sendsNothing() throws Exception {
            WsContext ctx = subscribe();
            tick(bus);
            verify(ctx, never()).send(anyString());
        }

        @Test
        void clientWhoseSendFails_isDropped() throws Exception {
            WsContext ctx = subscribe();
            doThrow(new RuntimeException("closed")).when(ctx).send(anyString());
            bus.publish(Map.of("type", "trade"));
            tick(bus);
            assertEquals(0, bus.subscriberCount());
        }

        @Test
        void unserialisableEvent_logsAndSendsNothing() throws Exception {
            WsContext ctx = subscribe();
            bus.publish(new Object() {
                @SuppressWarnings("unused")
                public String getBoom() { throw new IllegalStateException("cannot serialise"); }
            });
            tick(bus);
            verify(ctx, never()).send(anyString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  InstrumentStateManager
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("InstrumentStateManager")
    class States {

        @Test
        void defaultsToOpen_setAndSnapshot() {
            InstrumentStateManager m = new InstrumentStateManager();
            assertEquals(InstrumentStatus.OPEN, m.getState("AAPL"));
            m.setState("AAPL", InstrumentStatus.HALT);
            assertEquals(InstrumentStatus.HALT, m.getState("AAPL"));
            assertEquals(Map.of("AAPL", InstrumentStatus.HALT), m.snapshot());
            assertEquals(java.util.Set.of("AAPL"), m.symbols());
        }
    }
}
