package com.fix.simulator.defects;

import com.fix.simulator.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect regression tests D1–D3 (quote handling in {@link MatchingEngine#onQuote}).
 *
 * <p>Every assertion states the CORRECT behaviour. A failing assertion is the proof
 * that the defect exists. Control tests (named {@code control_*}) cover the
 * non-defective branches and are expected to pass.
 */
@DisplayName("Defects D1-D3 – quote handling")
class QuoteDefectsTest {

    private static final String SYM    = "AAPL";
    private static final String QUOTER = "QUOTER:S";
    private static final String SELLER = "CLIENT2:S";
    private static final String BUYER  = "CLIENT3:S";

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order order(String sess, String clOrdId, OrderSide side, OrderType type,
                        TimeInForce tif, long qty, String px, String stopPx) {
        return new Order(sess, clOrdId, null, SYM, side, type, tif, qty,
                px == null ? null : new BigDecimal(px),
                stopPx == null ? null : new BigDecimal(stopPx));
    }

    private Order limit(String sess, String clOrdId, OrderSide side, long qty, String px) {
        return order(sess, clOrdId, side, OrderType.LIMIT, TimeInForce.DAY, qty, px, null);
    }

    private void quote(String bidPx, long bidSz, String askPx, long askSz, String quoteId) {
        quote(QUOTER, bidPx, bidSz, askPx, askSz, quoteId);
    }

    private void quote(String sess, String bidPx, long bidSz, String askPx, long askSz, String quoteId) {
        engine.onQuote(sess, SYM,
                bidPx == null ? null : new BigDecimal(bidPx), bidSz,
                askPx == null ? null : new BigDecimal(askPx), askSz, quoteId);
    }

    private List<Map<String, Object>> bids() { return engine.getBook(SYM).getBidLevels(50); }
    private List<Map<String, Object>> asks() { return engine.getBook(SYM).getAskLevels(50); }

    /** Asserts the side has exactly one price level with the given price, aggregate qty and order count. */
    private static void assertOneLevel(List<Map<String, Object>> levels, String px, long qty, int orders) {
        assertEquals(1, levels.size(), "expected exactly one price level, got " + levels);
        Map<String, Object> l = levels.get(0);
        assertEquals(0, new BigDecimal((String) l.get("price")).compareTo(new BigDecimal(px)), "price");
        assertEquals(qty, ((Number) l.get("qty")).longValue(), "aggregate qty at level " + levels);
        assertEquals(orders, ((Number) l.get("orders")).intValue(), "order count at level " + levels);
    }

    // ── D1: partially/fully filled quote remainder is mis-handled ───────────

    @Nested
    @DisplayName("D1 – quote remainder inserted twice; fully filled quote left as ghost")
    class D1_QuoteRemainderHandling {

        @Test
        void partialFillBid_restsExactlyOnce() {
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            quote("10.00", 10, null, 0, "Q1");
            assertOneLevel(bids(), "10.00", 6, 1);   // actual: qty 12, orders 2
        }

        @Test
        void partialFillAsk_restsExactlyOnce() {
            engine.newOrder(limit(SELLER, "B1", OrderSide.BUY, 4, "10.00"));
            quote(null, 0, "10.00", 10, "Q1");
            assertOneLevel(asks(), "10.00", 6, 1);   // actual: qty 12, orders 2
        }

        @Test
        void fullFillBid_leavesNoGhostOrder() {
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            quote("10.00", 4, null, 0, "Q1");
            assertTrue(bids().isEmpty(), "fully filled quote bid must not remain in book: " + bids());
        }

        @Test
        void fullFillAsk_leavesNoGhostOrder() {
            engine.newOrder(limit(SELLER, "B1", OrderSide.BUY, 4, "10.00"));
            quote(null, 0, "10.00", 4, "Q1");
            assertTrue(asks().isEmpty(), "fully filled quote ask must not remain in book: " + asks());
        }

        // NOTE: not a control. onQuote() re-inserts an order that is still open after match(),
        // and that happens even when nothing matched, so every resting quote is double-counted.
        @Test
        void nonCrossingBid_restsOnce() {
            quote("9.00", 5, null, 0, "Q1");
            assertOneLevel(bids(), "9.00", 5, 1);    // actual: qty 10, orders 2
        }

        @Test
        void nonCrossingAsk_restsOnce() {
            quote(null, 0, "11.00", 5, "Q1");
            assertOneLevel(asks(), "11.00", 5, 1);   // actual: qty 10, orders 2
        }
    }

    // ── D2: a new quote does not replace the previous quote ─────────────────

    @Nested
    @DisplayName("D2 – repeated quotes stack instead of replacing the previous quote")
    class D2_QuoteStacking {

        @Test
        void newQuoteReplacesPriorQuote_bid() {
            quote("9.00", 10, null, 0, "Q1");
            quote("9.00", 10, null, 0, "Q2");
            assertOneLevel(bids(), "9.00", 10, 1);   // actual: qty 20, orders 2
        }

        @Test
        void newQuoteReplacesPriorQuote_ask() {
            quote(null, 0, "11.00", 10, "Q1");
            quote(null, 0, "11.00", 10, "Q2");
            assertOneLevel(asks(), "11.00", 10, 1);  // actual: qty 20, orders 2
        }

        @Test
        void repeatedSameQuoteId_doesNotStack() {
            quote("9.00", 10, null, 0, "Q1");
            quote("9.00", 10, null, 0, "Q1");
            assertOneLevel(bids(), "9.00", 10, 1);   // actual: qty 20, orders 2
        }

        @Test
        void fiveUpdatesFromSameSession_doNotAccumulate() {
            for (int i = 1; i <= 5; i++) quote("9.00", 10, null, 0, "Q" + i);
            assertOneLevel(bids(), "9.00", 10, 1);   // actual: qty 50, orders 5
        }

        /** Independent of the D1 double-insert: the superseded quote's order must no longer be active. */
        @Test
        void replacedBidQuote_isRemovedFromBook() {
            quote("9.00", 10, null, 0, "Q1");
            quote("9.00", 10, null, 0, "Q2");
            assertFalse(activeClOrdIds().contains("q-bid-Q1"),
                    "superseded quote Q1 still active: " + activeClOrdIds());     // actual: present
        }

        @Test
        void replacedAskQuote_isRemovedFromBook() {
            quote(null, 0, "11.00", 10, "Q1");
            quote(null, 0, "11.00", 10, "Q2");
            assertFalse(activeClOrdIds().contains("q-ask-Q1"),
                    "superseded quote Q1 still active: " + activeClOrdIds());     // actual: present
        }

        // Not a control: each quote is double-inserted (D1), so the aggregate is 40, not 20.
        @Test
        void differentSessions_bothRest() {
            quote(QUOTER, "9.00", 10, null, 0, "Q1");
            quote(BUYER,  "9.00", 10, null, 0, "Q1");
            assertOneLevel(bids(), "9.00", 20, 2);   // actual: qty 40, orders 4
        }
    }

    private java.util.List<String> activeClOrdIds() {
        return engine.getAllActiveOrders(SYM).stream().map(Order::getClOrdId).toList();
    }

    // ── D3: quote-generated fills skip the trade / last-price / stop pipeline ─

    @Nested
    @DisplayName("D3 – quote fills are not recorded as trades, do not set last price, do not trigger stops")
    class D3_QuoteFillsBypassPipeline {

        @Test
        void quoteFill_isRecordedAsTrade() {
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            quote("10.00", 4, null, 0, "Q1");
            List<Map<String, Object>> trades = engine.getTradeLog(SYM);
            assertEquals(1, trades.size(), "quote fill should appear in trade log");   // actual: 0
            assertEquals(4L, ((Number) trades.get(0).get("qty")).longValue());
        }

        // Quote bid is 10.50 and the fill is at the resting price 10.00. MarketState.update() sets
        // lastPrice to the quote mid on first quote, so a 10.00 quote bid would pass for the wrong reason.
        @Test
        void quoteFill_updatesLastPriceToFillPrice() {
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            quote("10.50", 4, null, 0, "Q1");
            BigDecimal last = engine.getMarketState().getLastPrice(SYM);
            assertNotNull(last, "last price should be set by quote fill");
            assertEquals(0, last.compareTo(new BigDecimal("10.00")),
                    "last price should be fill price 10.00, got " + last);                // actual: 10.50 (quote mid)
        }

        @Test
        void quoteFill_triggersPendingBuyStop() {
            Order stop = order(BUYER, "S1", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 2, null, "10.00");
            engine.newOrder(stop);
            assertFalse(stop.isTriggered(), "precondition: stop parked, no last price yet");
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            quote("10.00", 4, null, 0, "Q1");
            assertTrue(stop.isTriggered(), "quote fill at 10.00 must trigger buy stop @10.00");   // actual: false
        }

        @Test
        void control_engineFill_recordsTradeAndUpdatesLastPrice() {
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            engine.newOrder(limit(BUYER,  "B1", OrderSide.BUY,  4, "10.00"));
            assertEquals(1, engine.getTradeLog(SYM).size());
            assertEquals(0, engine.getMarketState().getLastPrice(SYM).compareTo(new BigDecimal("10.00")));
        }

        @Test
        void control_engineFill_triggersPendingBuyStop() {
            Order stop = order(BUYER, "S1", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 2, null, "10.00");
            engine.newOrder(stop);
            engine.newOrder(limit(SELLER, "A1", OrderSide.SELL, 4, "10.00"));
            engine.newOrder(limit(BUYER,  "B1", OrderSide.BUY,  4, "10.00"));
            assertTrue(stop.isTriggered());
        }
    }
}
