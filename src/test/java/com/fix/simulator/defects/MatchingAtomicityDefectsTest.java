package com.fix.simulator.defects;

import com.fix.simulator.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect regression tests D5: match and rest (and status-check and remove) must be atomic
 * with respect to other operations on the same symbol.
 *
 * <p>Each race window is reproduced with a {@link HookedBook}. At the window, the hook starts a
 * second thread that submits a competing order and then waits up to {@link #WINDOW_MS} for it.
 * Without a lock the competing order runs inside the window. With the lock held by the engine it
 * blocks until the window closes and then runs against the completed state.
 */
@DisplayName("Defect D5 – match/rest and check/cancel are atomic per symbol")
class MatchingAtomicityDefectsTest {

    private static final String SYM = "AAPL";
    private static final long   WINDOW_MS = 500;

    private MatchingEngine engine;
    private HookedBook book;

    /** OrderBook that runs one injected action at a chosen point in a book operation. */
    static class HookedBook extends OrderBook {
        Runnable afterMatch;
        Runnable beforeRemoveLimit;

        HookedBook(String symbol) { super(symbol); }

        @Override
        public List<Execution> match(Order aggressor) {
            List<Execution> r = super.match(aggressor);
            Runnable h = afterMatch;
            if (h != null) { afterMatch = null; h.run(); }
            return r;
        }

        @Override
        public boolean removeLimit(Order order) {
            Runnable h = beforeRemoveLimit;
            if (h != null) { beforeRemoveLimit = null; h.run(); }
            return super.removeLimit(order);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchingEngine();
        book = new HookedBook(SYM);
        // Install the hooked book into the engine's per-symbol map (private field, reflection).
        Field f = MatchingEngine.class.getDeclaredField("books");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, OrderBook> books = (Map<String, OrderBook>) f.get(engine);
        books.put(SYM, book);
    }

    private Order limit(String sess, String clOrdId, OrderSide side, long qty, String px) {
        return new Order(sess, clOrdId, null, SYM, side, OrderType.LIMIT, TimeInForce.DAY, qty,
                new BigDecimal(px), null);
    }

    /** Starts {@code action} on a new thread and waits up to the window, as a concurrent client would. */
    private static Thread concurrently(Runnable action) {
        Thread t = new Thread(action, "competing-client");
        t.start();
        try { t.join(WINDOW_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return t;
    }

    private static void awaitThread(Thread t) throws InterruptedException {
        if (t != null) t.join(5_000);
    }

    private static boolean crossed(OrderBook b) {
        BigDecimal bid = b.bestBid(), ask = b.bestAsk();
        return bid != null && ask != null && bid.compareTo(ask) >= 0;
    }

    // ── D5a: match-then-rest window ──────────────────────────────────────────

    @Nested
    @DisplayName("D5a – order arriving between match() and addLimitOrder() is not matched")
    class MatchThenRestWindow {

        private Thread competing;

        /** Submits buy 5@10.00. Optionally a competing sell 5@9.90 arrives inside the window. */
        private Order runWindowScenario(boolean injectConcurrentSell) throws InterruptedException {
            Order buy = limit("CLIENT4:S", "B1", OrderSide.BUY, 5, "10.00");
            if (injectConcurrentSell) {
                book.afterMatch = () -> competing = concurrently(
                        () -> engine.newOrder(limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "9.90")));
            }
            engine.newOrder(buy);
            awaitThread(competing);
            return buy;
        }

        @Test
        void bookIsNotLeftCrossed() throws Exception {
            runWindowScenario(true);
            assertFalse(crossed(book), "book crossed: bid=" + book.bestBid() + " ask=" + book.bestAsk());  // actual: crossed
        }

        @Test
        void aggressorIsFilledAgainstConcurrentSell() throws Exception {
            Order buy = runWindowScenario(true);
            assertEquals(OrderStatus.FILLED, buy.getStatus(), "buy 10.00 should match sell 9.90");     // actual: NEW
        }

        @Test
        void concurrentSellIsNotLeftRestingAtCrossedPrice() throws Exception {
            runWindowScenario(true);
            assertNull(book.bestAsk(), "sell 9.90 should have been consumed, but rests at " + book.bestAsk());  // actual: 9.90
        }

        @Test
        void control_sequentialSubmission_matchesCorrectly() throws Exception {
            engine.newOrder(limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "9.90"));
            Order buy = runWindowScenario(false);
            assertEquals(OrderStatus.FILLED, buy.getStatus());
            assertFalse(crossed(book));
            assertNull(book.bestAsk());
        }

        @Test
        void control_noConcurrentOrder_restingBuyIsNotCrossed() throws Exception {
            Order buy = runWindowScenario(false);
            assertEquals(OrderStatus.NEW, buy.getStatus());
            assertEquals(0, book.bestBid().compareTo(new BigDecimal("10.00")));
            assertNull(book.bestAsk());
        }
    }

    // ── D5b: cancel checks status and then removes, with no lock in between ─

    @Nested
    @DisplayName("D5b – cancel and a crossing fill are serialised: never both a cancel and a fill")
    class CancelCheckThenAct {

        private Thread competing;
        private Order competingBuy;

        /** Rests sell 5@10.00 (S1). Optionally a competing buy 5@10.00 arrives inside the cancel window. */
        private List<Execution> runCancelScenario(boolean fillDuringCancel, Order sell) throws InterruptedException {
            engine.newOrder(sell);
            if (fillDuringCancel) {
                competingBuy = limit("CLIENT4:S", "B1", OrderSide.BUY, 5, "10.00");
                book.beforeRemoveLimit = () -> competing = concurrently(() -> engine.newOrder(competingBuy));
            }
            List<Execution> r = engine.cancelOrder("CLIENT2:S", "S1", null, SYM);
            awaitThread(competing);
            return r;
        }

        private static boolean hasCancel(List<Execution> r) {
            return r.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED);
        }

        @Test
        void cancelledOrderHasNoFills() throws Exception {
            Order sell = limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "10.00");
            List<Execution> r = runCancelScenario(true, sell);
            if (hasCancel(r))
                assertEquals(0, sell.getFilledQty(), "order reported CANCELLED but was also filled");  // actual: 5 filled + cancelled
        }

        @Test
        void filledOrderIsNotReportedCancelled() throws Exception {
            Order sell = limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "10.00");
            List<Execution> r = runCancelScenario(true, sell);
            if (!hasCancel(r))
                assertEquals(OrderStatus.FILLED, sell.getStatus(), "cancel rejected, so the order must be FILLED");
            else
                assertNotEquals(OrderStatus.FILLED, sell.getStatus());
        }

        @Test
        void competingBuyIsConsistentWithCancelOutcome() throws Exception {
            Order sell = limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "10.00");
            List<Execution> r = runCancelScenario(true, sell);
            if (hasCancel(r)) {
                // cancel won: the buy found no ask, so it rests as a bid
                assertEquals(OrderStatus.NEW, competingBuy.getStatus(), "buy should rest after cancel won");
                assertEquals(0, book.bestBid().compareTo(new BigDecimal("10.00")));
            } else {
                // fill won: the buy matched the sell
                assertEquals(OrderStatus.FILLED, competingBuy.getStatus(), "buy should fill the sell");
            }
        }

        @Test
        void control_cancelRestingOrder_succeeds() throws Exception {
            Order sell = limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "10.00");
            List<Execution> r = runCancelScenario(false, sell);
            assertEquals(OrderStatus.CANCELLED, sell.getStatus());
            assertEquals(1, r.size());
            assertEquals(Execution.ExecType.CANCELLED, r.get(0).getExecType());
        }

        @Test
        void control_cancelAlreadyFilledOrder_isRejected() throws Exception {
            Order sell = limit("CLIENT2:S", "S1", OrderSide.SELL, 5, "10.00");
            engine.newOrder(sell);
            engine.newOrder(limit("CLIENT4:S", "B1", OrderSide.BUY, 5, "10.00"));
            List<Execution> r = engine.cancelOrder("CLIENT2:S", "S1", null, SYM);
            assertTrue(r.isEmpty());
            assertEquals(OrderStatus.FILLED, sell.getStatus());
        }
    }
}
