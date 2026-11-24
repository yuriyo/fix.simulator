package com.fix.simulator.engine;

import com.fix.simulator.admin.EventBus;
import com.fix.simulator.admin.InstrumentStateManager;
import io.javalin.websocket.WsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Branch and path coverage for {@link MatchingEngine}. */
@DisplayName("MatchingEngine – coverage of remaining branches")
class MatchingEngineCoverageTest {

    static final String SYM   = "AAPL";
    static final String SELL  = "SELLER:S";
    static final String BUY   = "BUYER:S";
    static final String QUOTE = "QUOTER:S";

    MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static Order order(String sess, String c, OrderSide side, OrderType type, TimeInForce tif,
                       long qty, String px, String stopPx) {
        return new Order(sess, c, null, SYM, side, type, tif, qty,
                px == null ? null : new BigDecimal(px),
                stopPx == null ? null : new BigDecimal(stopPx));
    }

    static Order limit(String sess, String c, OrderSide side, long qty, String px) {
        return order(sess, c, side, OrderType.LIMIT, TimeInForce.DAY, qty, px, null);
    }

    static Order market(String sess, String c, OrderSide side, TimeInForce tif, long qty) {
        return order(sess, c, side, OrderType.MARKET, tif, qty, null, null);
    }

    static Order stop(String sess, String c, OrderSide side, long qty, String stopPx) {
        return order(sess, c, side, OrderType.STOP, TimeInForce.GTC, qty, null, stopPx);
    }

    static Order stopLimit(String sess, String c, OrderSide side, long qty, String px, String stopPx) {
        return order(sess, c, side, OrderType.STOP_LIMIT, TimeInForce.GTC, qty, px, stopPx);
    }

    static long fillRecords(MatchingEngine e, Order o) {
        return e.getExecutionLog(null, null).stream()
                .filter(m -> Long.valueOf(o.getInternalId()).equals(m.get("orderId")))
                .filter(m -> "FILL".equals(m.get("execType")) || "PARTIAL_FILL".equals(m.get("execType")))
                .count();
    }

    static void tick(EventBus bus) throws Exception {
        Method m = EventBus.class.getDeclaredMethod("tick");
        m.setAccessible(true);
        m.invoke(bus);
    }

    static WsContext subscriber(EventBus bus) {
        WsContext ctx = mock(WsContext.class);
        bus.subscribe(ctx);
        return ctx;
    }

    @Nested
    @DisplayName("event bus wiring and publication")
    class EventBusWiring {

        @Test
        void snapshotProviderPublishesBookUpdateForDirtySymbol() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            WsContext ctx = subscriber(bus);
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            tick(bus);
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(ctx, atLeastOnce()).send(json.capture());
            assertTrue(json.getAllValues().stream().anyMatch(s -> s.contains("book_update") && s.contains(SYM)));
        }

        @Test
        void dirtyUnknownSymbol_snapshotIsNull_nothingSent() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            WsContext ctx = subscriber(bus);
            bus.markBookDirty("NOPE");
            tick(bus);
            verify(ctx, never()).send(anyString());
        }

        @Test
        void tradeWithSubscriber_publishesTradeEvent() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            WsContext ctx = subscriber(bus);
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            tick(bus);
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(ctx, atLeastOnce()).send(json.capture());
            assertTrue(json.getAllValues().stream().anyMatch(s -> s.contains("\"type\":\"trade\"")));
        }

        @Test
        void cancelWithBus_marksBookDirty() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            WsContext ctx = subscriber(bus);
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.cancelOrder(SELL, "S1", null, SYM);
            tick(bus);
            verify(ctx, atLeastOnce()).send(anyString());
        }

        @Test
        void quoteWithBus_withdrawsAndMarksDirty() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            subscriber(bus);
            engine.onQuote(QUOTE, SYM, new BigDecimal("9.00"), 5, null, 0, "Q1");
            engine.onQuote(QUOTE, SYM, new BigDecimal("9.00"), 5, null, 0, "Q2");
            assertEquals(1, engine.getAllActiveOrders(SYM).size());
        }

        @Test
        void setAdminBookLevelWithBus_marksDirty() throws Exception {
            EventBus bus = new EventBus();
            engine.setEventBus(bus);
            WsContext ctx = subscriber(bus);
            engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 7);
            tick(bus);
            verify(ctx, atLeastOnce()).send(anyString());
        }

        @Test
        void stateManagerSetter_accepted() {
            engine.setStateManager(new InstrumentStateManager());
            assertEquals(1, engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 1, "10.00")).size());
        }
    }

    @Nested
    @DisplayName("new order edge cases")
    class NewOrderEdges {

        @Test
        void unknownSymbol_isRejectedAndNotRegistered() {
            Order o = new Order(SELL, "X1", null, "NOPE", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 1, new BigDecimal("1"), null);
            List<Execution> r = engine.newOrder(o);
            assertEquals(Execution.ExecType.REJECTED, r.get(0).getExecType());
            assertNull(engine.findOrderById(o.getInternalId()));
        }

        @Test
        void marketOrderWithNoLiquidity_remainderCancelled() {
            List<Execution> r = engine.newOrder(market(BUY, "B1", OrderSide.BUY, TimeInForce.DAY, 5));
            assertEquals(Execution.ExecType.CANCELLED, r.get(r.size() - 1).getExecType());
        }

        @Test
        void marketOrderPartiallyFilled_remainderCancelled() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 3, "10.00"));
            Order m = market(BUY, "B1", OrderSide.BUY, TimeInForce.DAY, 5);
            List<Execution> r = engine.newOrder(m);
            assertEquals(OrderStatus.CANCELLED, m.getStatus());
            assertEquals(3, m.getFilledQty());
            assertTrue(r.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void marketFokWithInsufficientLiquidity_cancelled() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 2, "10.00"));
            Order m = market(BUY, "B1", OrderSide.BUY, TimeInForce.FOK, 5);
            engine.newOrder(m);
            assertEquals(OrderStatus.CANCELLED, m.getStatus());
            assertEquals(0, m.getFilledQty());
        }

        @Test
        void stopBuyAlreadyTriggeredAtReceipt_marketFires() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));   // last price 10.00
            Order s = stop(BUY, "STP", OrderSide.BUY, 2, "9.00");
            engine.newOrder(s);
            assertTrue(s.isTriggered());
        }

        @Test
        void stopSellAlreadyTriggeredAtReceipt_marketFires() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));   // last price 10.00
            Order s = stop(SELL, "STP", OrderSide.SELL, 2, "11.00");
            engine.newOrder(s);
            assertTrue(s.isTriggered());
        }

        @Test
        void stopSellNotYetTriggered_rests() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));   // last price 10.00
            Order s = stop(SELL, "STP", OrderSide.SELL, 2, "9.00");
            engine.newOrder(s);
            assertFalse(s.isTriggered());
            assertTrue(engine.getAllActiveOrders(SYM).contains(s));
        }

        @Test
        void stopWithoutStopPrice_neverTriggersAtReceipt() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            Order s = order(BUY, "STP", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 2, null, null);
            engine.newOrder(s);
            assertFalse(s.isTriggered());
        }

        @Test
        void stopWithNoLastPrice_rests() {
            Order s = stop(BUY, "STP", OrderSide.BUY, 2, "9.00");
            engine.newOrder(s);
            assertFalse(s.isTriggered());
        }

        @Test
        void stopLimitTriggeredByFill_executesAsLimit() {
            engine.newOrder(limit(SELL, "A1", OrderSide.SELL, 5, "10.00"));
            Order sl = stopLimit(BUY, "SL", OrderSide.BUY, 2, "10.50", "10.00");
            engine.newOrder(sl);
            engine.newOrder(limit(SELL, "P2", OrderSide.SELL, 2, "10.20"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            assertTrue(sl.isTriggered());
            assertEquals(OrderStatus.FILLED, sl.getStatus());
        }

        @Test
        void stopLimitTriggeredAtReceipt_executesAsLimit() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));  // last 10.00
            engine.newOrder(limit(SELL, "P2", OrderSide.SELL, 2, "10.20"));
            Order sl = stopLimit(BUY, "SL", OrderSide.BUY, 2, "10.50", "9.00");
            engine.newOrder(sl);
            assertTrue(sl.isTriggered());
            assertEquals(OrderStatus.FILLED, sl.getStatus());
        }

        @Test
        void aggressorFilledByStopFill_stopFillLoggedOnce() {
            engine.newOrder(limit(SELL, "A1", OrderSide.SELL, 5, "10.00"));
            Order sl = stopLimit(BUY, "SL", OrderSide.BUY, 2, "10.50", "10.00");
            engine.newOrder(sl);
            engine.newOrder(limit(SELL, "P2", OrderSide.SELL, 2, "10.20"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            assertEquals(1, fillRecords(engine, sl));
        }
    }

    @Nested
    @DisplayName("cancel edge cases")
    class CancelEdges {

        @Test
        void unknownSymbol_returnsEmpty() {
            assertTrue(engine.cancelOrder(SELL, "S1", null, "NOPE").isEmpty());
        }

        @Test
        void orderNotFound_returnsEmpty() {
            assertTrue(engine.cancelOrder(SELL, "MISSING", null, SYM).isEmpty());
        }

        @Test
        void restingStop_cancelledFromPendingList() {
            Order s = stop(SELL, "STP", OrderSide.SELL, 2, "9.00");
            engine.newOrder(s);
            List<Execution> r = engine.cancelOrder(SELL, "STP", null, SYM);
            assertEquals(Execution.ExecType.CANCELLED, r.get(0).getExecType());
            assertFalse(engine.getAllActiveOrders(SYM).contains(s));
        }

        @Test
        void alreadyCancelled_returnsEmpty() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.cancelOrder(SELL, "S1", null, SYM);
            assertTrue(engine.cancelOrder(SELL, "S1", null, SYM).isEmpty());
        }
    }

    @Nested
    @DisplayName("cancel-replace edge cases")
    class ReplaceEdges {

        @Test
        void unknownReplacementSymbol_returnsEmpty() {
            Order repl = new Order(SELL, "R1", "S1", "NOPE", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null);
            assertTrue(engine.replaceOrder(SELL, "S1", repl).isEmpty());
        }

        @Test
        void originalNotFound_returnsEmpty() {
            Order repl = limit(SELL, "R1", OrderSide.SELL, 5, "10.00");
            assertTrue(engine.replaceOrder(SELL, "MISSING", repl).isEmpty());
        }

        @Test
        void completedOriginal_returnsEmpty() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            assertTrue(engine.replaceOrder(SELL, "S1", limit(SELL, "R1", OrderSide.SELL, 5, "10.00")).isEmpty());
        }

        @Test
        void restingStop_replacedByLimit() {
            engine.newOrder(stop(SELL, "STP", OrderSide.SELL, 2, "9.00"));
            List<Execution> r = engine.replaceOrder(SELL, "STP",
                    limit(SELL, "R1", OrderSide.SELL, 4, "10.00"));
            assertEquals(Execution.ExecType.REPLACED, r.get(0).getExecType());
            assertEquals(1, engine.getAllActiveOrders(SYM).size());
        }

        @Test
        void restingLimit_replacedByLimit() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.replaceOrder(SELL, "S1", limit(SELL, "R1", OrderSide.SELL, 4, "10.50"));
            assertEquals(1, engine.getAllActiveOrders(SYM).size());
            assertEquals(4L, engine.getAllActiveOrders(SYM).get(0).getOpenQty());
        }
    }

    @Nested
    @DisplayName("quotes")
    class QuoteEdges {

        @Test
        void unknownSymbol_noBookChange_butMarketStateUpdated() {
            assertTrue(engine.onQuote(QUOTE, "NOPE", new BigDecimal("9"), 5, null, 0, "Q1").isEmpty());
            assertNotNull(engine.getMarketState().get("NOPE"));
        }

        @Test
        void askOnly_restsOneAsk() {
            engine.onQuote(QUOTE, SYM, null, 0, new BigDecimal("11.00"), 5, "Q1");
            assertEquals(1, engine.getBook(SYM).getAskLevels(10).size());
            assertTrue(engine.getBook(SYM).getBidLevels(10).isEmpty());
        }

        @Test
        void zeroSizeSide_notRested() {
            engine.onQuote(QUOTE, SYM, new BigDecimal("9.00"), 0, new BigDecimal("11.00"), 0, "Q1");
            assertTrue(engine.getAllActiveOrders(SYM).isEmpty());
        }

        @Test
        void newQuoteAfterPreviousBidFilled_skipsFilledWithdrawal() {
            engine.onQuote(QUOTE, SYM, new BigDecimal("9.00"), 5, null, 0, "Q1");
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "9.00"));  // fills the quote bid
            engine.onQuote(QUOTE, SYM, new BigDecimal("8.00"), 5, null, 0, "Q2");
            assertEquals(1, engine.getAllActiveOrders(SYM).size());
        }

        @Test
        void newQuoteAfterPartiallyFilledBid_withdrawsRemainder() {
            engine.onQuote(QUOTE, SYM, new BigDecimal("9.00"), 10, null, 0, "Q1");
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 4, "9.00"));  // partial fill of quote bid
            engine.onQuote(QUOTE, SYM, new BigDecimal("8.00"), 5, null, 0, "Q2");
            assertEquals(1, engine.getAllActiveOrders(SYM).size());
            assertEquals(5L, engine.getAllActiveOrders(SYM).get(0).getOpenQty());
        }

        @Test
        void quoteFillsIntoEngineRecordedOnce() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 4, "10.00"));
            List<Execution> fills = engine.onQuote(QUOTE, SYM, new BigDecimal("10.00"), 4, null, 0, "Q1");
            assertFalse(fills.isEmpty());
            assertEquals(1, engine.getTradeLog(SYM).size());
        }
    }

    @Nested
    @DisplayName("queries and filters")
    class Queries {

        @Test
        void tradeLog_filteredBySymbolAndAll() {
            engine.registerSymbol("MSFT");
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 1, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 1, "10.00"));
            engine.newOrder(new Order(SELL, "S2", null, "MSFT", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 1, new BigDecimal("20.00"), null));
            engine.newOrder(new Order(BUY, "B2", null, "MSFT", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 1, new BigDecimal("20.00"), null));
            assertEquals(1, engine.getTradeLog("MSFT").size());
            assertEquals(2, engine.getTradeLog(null).size());
            assertEquals(2, engine.getTradeLog("").size());
            assertEquals(java.util.Set.of(SYM, "MSFT"), engine.getTradedSymbols());
        }

        @Test
        void executionLog_filteredBySymbolAndClient() {
            engine.registerSymbol("MSFT");
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 1, "10.00"));
            engine.newOrder(new Order(BUY, "B2", null, "MSFT", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 1, new BigDecimal("20.00"), null));
            assertFalse(engine.getExecutionLog(SYM, "").isEmpty());
            assertTrue(engine.getExecutionLog(null, "BUYER").stream()
                    .allMatch(m -> ((String) m.get("sessionId")).contains("BUYER")));
            assertTrue(engine.getExecutionLog("MSFT", null).stream()
                    .allMatch(m -> "MSFT".equals(m.get("symbol"))));
            assertEquals(2, engine.getExecutionLog("", "").size());   // two NEW acks, no fills
        }

        @Test
        void activeAndCompletedOrders_bySymbol() {
            engine.registerSymbol("MSFT");
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "10.00"));
            engine.newOrder(new Order(SELL, "M1", null, "MSFT", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 2, new BigDecimal("30.00"), null));
            assertEquals(1, engine.getAllActiveOrders("MSFT").size());
            assertTrue(engine.getAllActiveOrders("NOPE").isEmpty());
            assertEquals(2, engine.getCompletedOrders(SYM).size());
            assertEquals(2, engine.getCompletedOrders(null).size());
            assertTrue(engine.getCompletedOrders("MSFT").isEmpty());
        }

        @Test
        void orderStats_countsByStatusForSession() {
            engine.newOrder(limit(SELL, "S1", OrderSide.SELL, 5, "10.00"));        // open
            engine.newOrder(limit(SELL, "S2", OrderSide.SELL, 5, "11.00"));
            engine.cancelOrder(SELL, "S2", null, SYM);                              // cancelled
            engine.newOrder(limit(SELL, "S3", OrderSide.SELL, 5, "9.00"));
            engine.newOrder(limit(BUY, "B1", OrderSide.BUY, 5, "9.00"));            // S3 filled
            Map<String, Integer> st = engine.getOrderStats(SELL);
            assertEquals(3, st.get("orders"));
            assertEquals(1, st.get("open"));
            assertEquals(1, st.get("cancelled"));
            assertEquals(1, st.get("filled"));
        }

        @Test
        void lookupsAndGetters() {
            Order o = limit(SELL, "S1", OrderSide.SELL, 5, "10.00");
            engine.newOrder(o);
            assertTrue(engine.hasSymbol(SYM));
            assertFalse(engine.hasSymbol("NOPE"));
            assertNull(engine.getBook("NOPE"));
            assertSame(o, engine.findOrderById(o.getInternalId()));
            assertTrue(engine.getSymbols().contains(SYM));
            assertNotNull(engine.getMarketState());
            assertNull(engine.getMarketState().getLastPrice(SYM));
        }

        @Test
        void setAdminBookLevel_unknownSymbolIsNoop() {
            engine.setAdminBookLevel("NOPE", OrderSide.BUY, new BigDecimal("1"), 5);
            assertNull(engine.getBook("NOPE"));
        }

        @Test
        void setAdminBookLevel_appliesToBook() {
            engine.setAdminBookLevel(SYM, OrderSide.SELL, new BigDecimal("12.00"), 40);
            assertEquals(40L, engine.getBook(SYM).getLevelQty(OrderSide.SELL, new BigDecimal("12.00")));
        }
    }

    @Nested
    @DisplayName("history retention")
    class Retention {

        @Test
        void completedOrdersBeyondCap_areEvicted() {
            int n = MatchingEngine.ORDER_HISTORY_CAP / 2 + 100;   // each round trip registers 2 orders
            Order first = null;
            for (int i = 0; i < n; i++) {
                Order s = limit(SELL, "S" + i, OrderSide.SELL, 1, "10.00");
                if (first == null) first = s;
                engine.newOrder(s);
                engine.newOrder(limit(BUY, "B" + i, OrderSide.BUY, 1, "10.00"));
            }
            assertTrue(engine.getCompletedOrders(null).size() <= MatchingEngine.ORDER_HISTORY_CAP);
            assertNull(engine.findOrderById(first.getInternalId()));
        }

        @Test
        void liveOrdersAreNeverEvicted() {
            Order oldest = null;
            for (int i = 0; i < MatchingEngine.ORDER_HISTORY_CAP + 50; i++) {
                Order b = limit(BUY, "B" + i, OrderSide.BUY, 1, "1.00");   // rest, never match
                if (oldest == null) oldest = b;
                engine.newOrder(b);
            }
            assertNotNull(engine.findOrderById(oldest.getInternalId()));
            assertEquals(MatchingEngine.ORDER_HISTORY_CAP + 50, engine.getAllActiveOrders(SYM).size());
        }
    }
}
