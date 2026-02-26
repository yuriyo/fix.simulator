package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderBook – price-time FIFO matching")
class OrderBookTest {

    private static final String SYM  = "AAPL";
    private static final String SESS = "sess1";

    private OrderBook book;
    private int clientSeq = 0;

    @BeforeEach
    void setUp() {
        book = new OrderBook(SYM);
        clientSeq = 0;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Order limitBuy(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null);
    }

    private Order limitSell(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null);
    }

    private Order iocBuy(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.IOC, qty, new BigDecimal(px), null);
    }

    private Order iocSell(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.IOC, qty, new BigDecimal(px), null);
    }

    private Order fokBuy(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.FOK, qty, new BigDecimal(px), null);
    }

    private Order fokSell(String clOrdId, long qty, String px) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.FOK, qty, new BigDecimal(px), null);
    }

    private Order mktFokBuy(String clOrdId, long qty) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.MARKET, TimeInForce.FOK, qty, null, null);
    }

    private Order mktFokSell(String clOrdId, long qty) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.MARKET, TimeInForce.FOK, qty, null, null);
    }

    private Order mktBuy(String clOrdId, long qty) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.MARKET, TimeInForce.DAY, qty, null, null);
    }

    private Order mktSell(String clOrdId, long qty) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.MARKET, TimeInForce.DAY, qty, null, null);
    }

    private Order stopBuy(String clOrdId, long qty, String stopPx) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.BUY,
                OrderType.STOP, TimeInForce.GTC, qty, null, new BigDecimal(stopPx));
    }

    private Order stopSell(String clOrdId, long qty, String stopPx) {
        return new Order(SESS, clOrdId, null, SYM, OrderSide.SELL,
                OrderType.STOP, TimeInForce.GTC, qty, null, new BigDecimal(stopPx));
    }

    // ── Limit order registration ─────────────────────────────────────────────

    @Nested
    @DisplayName("Registration and lookup")
    class Registration {

        @Test
        void addAndFindByClientKey() {
            Order o = limitBuy("C1", 100, "100.00");
            book.addLimitOrder(o);
            assertSame(o, book.findByClientKey(SESS, "C1"));
        }

        @Test
        void addAndFindById() {
            Order o = limitBuy("C1", 100, "100.00");
            book.addLimitOrder(o);
            assertSame(o, book.findById(o.getInternalId()));
        }

        @Test
        void findUnknownReturnsNull() {
            assertNull(book.findByClientKey(SESS, "NONE"));
            assertNull(book.findById(Long.MAX_VALUE));
        }

        @Test
        void deindexRemovesFromLookup() {
            Order o = limitBuy("C1", 100, "100.00");
            book.addLimitOrder(o);
            book.deindex(o);
            assertNull(book.findByClientKey(SESS, "C1"));
            assertNull(book.findById(o.getInternalId()));
        }

        @Test
        void removeLimit_removesFromBookAndReturnsTrue() {
            Order o = limitBuy("C1", 100, "100.00");
            book.addLimitOrder(o);
            assertTrue(book.removeLimit(o));
            // bestBid should now be null
            assertNull(book.bestBid());
        }

        @Test
        void removeLimit_unknownReturnsFalse() {
            Order o = limitBuy("C1", 100, "100.00");
            assertFalse(book.removeLimit(o));
        }

        @Test
        void addAndRemoveStop() {
            Order stop = stopBuy("S1", 100, "105.00");
            book.addStopOrder(stop);
            assertSame(stop, book.findByClientKey(SESS, "S1"));
            assertTrue(book.removeStop(stop));
        }

        @Test
        void removeStop_unknownReturnsFalse() {
            Order stop = stopBuy("S1", 100, "105.00");
            assertFalse(book.removeStop(stop));
        }

        @Test
        void addLimitOrder_requiresPrice() {
            Order o = mktBuy("M1", 100);
            assertThrows(IllegalArgumentException.class, () -> book.addLimitOrder(o));
        }
    }

    // ── bestBid / bestAsk ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Best bid/ask")
    class BestBidAsk {

        @Test
        void emptyBookReturnsNull() {
            assertNull(book.bestBid());
            assertNull(book.bestAsk());
        }

        @Test
        void bestBidIsHighestBuy() {
            book.addLimitOrder(limitBuy("B1", 100, "99.00"));
            book.addLimitOrder(limitBuy("B2", 100, "101.00"));
            book.addLimitOrder(limitBuy("B3", 100, "100.00"));
            assertEquals(new BigDecimal("101.00"), book.bestBid());
        }

        @Test
        void bestAskIsLowestSell() {
            book.addLimitOrder(limitSell("S1", 100, "102.00"));
            book.addLimitOrder(limitSell("S2", 100, "101.00"));
            book.addLimitOrder(limitSell("S3", 100, "103.00"));
            assertEquals(new BigDecimal("101.00"), book.bestAsk());
        }

        @Test
        void bestBidClearedAfterFullRemoval() {
            Order o = limitBuy("B1", 100, "100.00");
            book.addLimitOrder(o);
            book.removeLimit(o);
            assertNull(book.bestBid());
        }
    }

    // ── Match – full fills ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Matching – full fills")
    class FullFills {

        @Test
        void buyAggressorMatchesSell_exactQty() {
            Order sell = limitSell("S1", 100, "99.00");
            book.addLimitOrder(sell);

            Order buy  = limitBuy("B1", 100, "100.00");
            List<Execution> fills = book.match(buy);

            assertEquals(2, fills.size());
            assertFillExec(fills.get(0), buy,  100, "99.00", Execution.ExecType.FILL);
            assertFillExec(fills.get(1), sell, 100, "99.00", Execution.ExecType.FILL);
            assertEquals(0, buy.getOpenQty());
            assertEquals(0, sell.getOpenQty());
        }

        @Test
        void sellAggressorMatchesBuy_exactQty() {
            Order buy  = limitBuy("B1", 100, "100.00");
            book.addLimitOrder(buy);

            Order sell = limitSell("S1", 100, "99.00");
            List<Execution> fills = book.match(sell);

            assertEquals(2, fills.size());
            assertFillExec(fills.get(0), sell, 100, "100.00", Execution.ExecType.FILL);
            assertFillExec(fills.get(1), buy,  100, "100.00", Execution.ExecType.FILL);
        }

        @Test
        void fillPriceIsPassiveSidePrice() {
            // passive SELL at 99, aggressive BUY at 101 → fill at 99 (seller's price)
            book.addLimitOrder(limitSell("S1", 100, "99.00"));
            Order buy = limitBuy("B1", 100, "101.00");
            List<Execution> fills = book.match(buy);

            assertEquals("99.00", fills.get(0).getPrice().toPlainString());
        }

        @Test
        void marketBuyMatchesAnySell() {
            book.addLimitOrder(limitSell("S1", 100, "999.00")); // very high ask
            Order mkt = mktBuy("M1", 100);
            List<Execution> fills = book.match(mkt);
            assertEquals(2, fills.size());
            assertEquals(0, mkt.getOpenQty());
        }

        @Test
        void marketSellMatchesAnyBuy() {
            book.addLimitOrder(limitBuy("B1", 100, "1.00")); // very low bid
            Order mkt = mktSell("M1", 100);
            List<Execution> fills = book.match(mkt);
            assertEquals(2, fills.size());
            assertEquals(0, mkt.getOpenQty());
        }
    }

    // ── Match – partial fills ────────────────────────────────────────────────

    @Nested
    @DisplayName("Matching – partial fills")
    class PartialFills {

        @Test
        void aggressorPartiallyFillsPassive() {
            // BUY 50, SELL resting 100 → BUY fully filled, SELL partially filled
            Order sell = limitSell("S1", 100, "99.00");
            book.addLimitOrder(sell);

            Order buy = limitBuy("B1", 50, "100.00");
            List<Execution> fills = book.match(buy);

            assertEquals(2, fills.size());
            assertFillExec(fills.get(0), buy,  50, "99.00", Execution.ExecType.FILL);
            assertFillExec(fills.get(1), sell, 50, "99.00", Execution.ExecType.PARTIAL_FILL);
            assertEquals(0,  buy.getOpenQty());
            assertEquals(50, sell.getOpenQty());
        }

        @Test
        void passiveFullyFillsAggressorPartially() {
            // BUY 200, SELL resting 100 → SELL fully filled, BUY partial
            Order sell = limitSell("S1", 100, "99.00");
            book.addLimitOrder(sell);

            Order buy = limitBuy("B1", 200, "100.00");
            List<Execution> fills = book.match(buy);

            assertEquals(2, fills.size());
            assertFillExec(fills.get(0), buy,  100, "99.00", Execution.ExecType.PARTIAL_FILL);
            assertFillExec(fills.get(1), sell, 100, "99.00", Execution.ExecType.FILL);
            assertEquals(100, buy.getOpenQty());
        }

        @Test
        void aggressorMatchesMultipleLevels() {
            // Two sell levels, buy sweeps both
            book.addLimitOrder(limitSell("S1", 50, "99.00"));
            book.addLimitOrder(limitSell("S2", 50, "100.00"));

            Order buy = limitBuy("B1", 100, "101.00");
            List<Execution> fills = book.match(buy);

            // 2 fills per level = 4 total
            assertEquals(4, fills.size());
            assertEquals(0, buy.getOpenQty());
        }
    }

    // ── Match – no match ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Matching – no match conditions")
    class NoMatch {

        @Test
        void limitBuyPriceTooLow() {
            book.addLimitOrder(limitSell("S1", 100, "102.00"));
            Order buy = limitBuy("B1", 100, "101.00");
            List<Execution> fills = book.match(buy);
            assertTrue(fills.isEmpty());
            assertEquals(100, buy.getOpenQty());
        }

        @Test
        void limitSellPriceTooHigh() {
            book.addLimitOrder(limitBuy("B1", 100, "99.00"));
            Order sell = limitSell("S1", 100, "100.00");
            List<Execution> fills = book.match(sell);
            assertTrue(fills.isEmpty());
            assertEquals(100, sell.getOpenQty());
        }

        @Test
        void emptyOppositeBook() {
            Order buy = limitBuy("B1", 100, "100.00");
            List<Execution> fills = book.match(buy);
            assertTrue(fills.isEmpty());
        }

        @Test
        void marketOrderEmptyBook_noFills() {
            Order mkt = mktBuy("M1", 100);
            List<Execution> fills = book.match(mkt);
            assertTrue(fills.isEmpty());
            assertEquals(100, mkt.getOpenQty()); // not cancelled by match()
        }
    }

    // ── FIFO ordering ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIFO ordering within a price level")
    class FifoOrdering {

        @Test
        void firstAddedIsMatchedFirst() {
            Order sell1 = limitSell("S1", 50, "100.00"); // arrived first
            Order sell2 = limitSell("S2", 50, "100.00"); // arrived second
            book.addLimitOrder(sell1);
            book.addLimitOrder(sell2);

            Order buy = limitBuy("B1", 50, "100.00");
            List<Execution> fills = book.match(buy);

            // sell1 should be matched (it's first in the queue)
            assertEquals(2, fills.size());
            assertSame(sell1, fills.get(1).getOrder());
            assertEquals(0, sell1.getOpenQty());
            assertEquals(50, sell2.getOpenQty()); // sell2 untouched
        }

        @Test
        void precedingOrderFullyFilled_nextInQueue_matched() {
            Order sell1 = limitSell("S1", 30, "100.00");
            Order sell2 = limitSell("S2", 70, "100.00");
            book.addLimitOrder(sell1);
            book.addLimitOrder(sell2);

            Order buy = limitBuy("B1", 100, "100.00");
            List<Execution> fills = book.match(buy);

            // sell1 fills 30, sell2 fills 70
            assertEquals(4, fills.size());
            assertEquals(0, buy.getOpenQty());
            assertEquals(0, sell1.getOpenQty());
            assertEquals(0, sell2.getOpenQty());
        }
    }

    // ── IOC ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IOC – immediate-or-cancel")
    class IocBehaviour {

        @Test
        void iocFullFill_noCancelExecution() {
            book.addLimitOrder(limitSell("S1", 100, "99.00"));
            Order ioc = iocBuy("I1", 100, "100.00");
            List<Execution> fills = book.match(ioc);

            // 2 fill executions, no cancel
            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void iocPartialFill_remainderCancelled() {
            book.addLimitOrder(limitSell("S1", 50, "99.00")); // only 50 available
            Order ioc = iocBuy("I1", 100, "100.00");
            List<Execution> fills = book.match(ioc);

            // 2 fill execs + 1 cancel exec
            assertEquals(3, fills.size());
            assertEquals(Execution.ExecType.CANCELLED,
                    fills.get(2).getExecType());
            assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
            assertEquals(0, ioc.getOpenQty());
        }

        @Test
        void iocNoFill_immediatelyCancelled() {
            Order ioc = iocBuy("I1", 100, "90.00");  // no asks at all
            List<Execution> fills = book.match(ioc);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
        }

        @Test
        void iocSell_remainderCancelled() {
            book.addLimitOrder(limitBuy("B1", 40, "100.00"));
            Order ioc = iocSell("I1", 100, "99.00");
            List<Execution> fills = book.match(ioc);

            long cancelCount = fills.stream()
                    .filter(e -> e.getExecType() == Execution.ExecType.CANCELLED).count();
            assertEquals(1, cancelCount);
            assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
        }
    }

    // ── FOK ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FOK – fill-or-kill (all-or-nothing)")
    class FokBehaviour {

        @Test
        void fokBuy_emptyBook_cancelledWithNoFills() {
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
            assertEquals(OrderStatus.CANCELLED, fok.getStatus());
        }

        @Test
        void fokBuy_exactLiquidity_fullFill() {
            book.addLimitOrder(limitSell("S1", 100, "99.00"));
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> fills = book.match(fok);

            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(OrderStatus.FILLED, fok.getStatus());
        }

        @Test
        void fokBuy_partialLiquidity_cancelledWithNoFills() {
            // Only 50 available, FOK wants 100 → cancel without touching the book
            book.addLimitOrder(limitSell("S1", 50, "99.00"));
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
            // Passive sell must be completely untouched
            assertEquals(50, book.findByClientKey(SESS, "S1").getOpenQty());
        }

        @Test
        void fokBuy_acrossMultipleLevels_sufficientLiquidity_fills() {
            book.addLimitOrder(limitSell("S1", 60, "99.00"));
            book.addLimitOrder(limitSell("S2", 40, "100.00"));
            Order fok = fokBuy("F1", 100, "101.00");
            List<Execution> fills = book.match(fok);

            // 2 execs per level × 2 levels = 4, no cancel
            assertEquals(4, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(0, fok.getOpenQty());
        }

        @Test
        void fokBuy_acrossMultipleLevels_insufficientLiquidity_cancelled() {
            book.addLimitOrder(limitSell("S1", 60, "99.00"));
            book.addLimitOrder(limitSell("S2", 30, "100.00")); // 90 total, want 100
            Order fok = fokBuy("F1", 100, "101.00");
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
            // Both passive levels must be untouched
            assertEquals(60, book.findByClientKey(SESS, "S1").getOpenQty());
            assertEquals(30, book.findByClientKey(SESS, "S2").getOpenQty());
        }

        @Test
        void fokSell_sufficientLiquidity_fills() {
            book.addLimitOrder(limitBuy("B1", 100, "101.00"));
            Order fok = fokSell("F1", 100, "99.00");
            List<Execution> fills = book.match(fok);

            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void fokSell_insufficientLiquidity_cancelled() {
            book.addLimitOrder(limitBuy("B1", 80, "101.00")); // only 80, want 100
            Order fok = fokSell("F1", 100, "99.00");
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
        }

        @Test
        void fokBuy_limitPriceMismatch_counted_cancelled() {
            // Sell at 102, FOK limit buy at 101 → 0 marketable liquidity → cancel
            book.addLimitOrder(limitSell("S1", 100, "102.00"));
            Order fok = fokBuy("F1", 100, "101.00");
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
            // Passive sell must be untouched
            assertEquals(100, book.findByClientKey(SESS, "S1").getOpenQty());
        }

        @Test
        void fokMarketBuy_sufficientLiquidity_fills() {
            book.addLimitOrder(limitSell("S1", 100, "500.00")); // MARKET matches any price
            Order fok = mktFokBuy("F1", 100);
            List<Execution> fills = book.match(fok);

            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void fokMarketBuy_insufficientLiquidity_cancelled() {
            book.addLimitOrder(limitSell("S1", 50, "99.00")); // only 50, want 100
            Order fok = mktFokBuy("F1", 100);
            List<Execution> fills = book.match(fok);

            assertEquals(1, fills.size());
            assertEquals(Execution.ExecType.CANCELLED, fills.get(0).getExecType());
            assertEquals(50, book.findByClientKey(SESS, "S1").getOpenQty());
        }

        @Test
        void fokMarketSell_sufficientLiquidity_fills() {
            book.addLimitOrder(limitBuy("B1", 100, "1.00")); // MARKET matches any price
            Order fok = mktFokSell("F1", 100);
            List<Execution> fills = book.match(fok);

            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }
    }

    // ── STOP trigger ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stop order triggering via checkStops")
    class StopTrigger {

        @Test
        void buystop_triggeredWhenPriceAtOrAbove() {
            Order stop = stopBuy("ST1", 100, "105.00");
            book.addStopOrder(stop);

            List<Order> triggered = book.checkStops(new BigDecimal("105.00"));
            assertEquals(1, triggered.size());
            assertSame(stop, triggered.get(0));
            assertTrue(stop.isTriggered());
        }

        @Test
        void buyStop_triggeredAboveStopPrice() {
            Order stop = stopBuy("ST1", 100, "105.00");
            book.addStopOrder(stop);
            List<Order> triggered = book.checkStops(new BigDecimal("106.00"));
            assertEquals(1, triggered.size());
        }

        @Test
        void buyStop_notTriggeredBelowStopPrice() {
            Order stop = stopBuy("ST1", 100, "105.00");
            book.addStopOrder(stop);
            List<Order> triggered = book.checkStops(new BigDecimal("104.99"));
            assertTrue(triggered.isEmpty());
        }

        @Test
        void sellStop_triggeredAtOrBelow() {
            Order stop = stopSell("ST1", 100, "95.00");
            book.addStopOrder(stop);
            List<Order> triggered = book.checkStops(new BigDecimal("95.00"));
            assertEquals(1, triggered.size());
        }

        @Test
        void sellStop_notTriggeredAboveStopPrice() {
            Order stop = stopSell("ST1", 100, "95.00");
            book.addStopOrder(stop);
            List<Order> triggered = book.checkStops(new BigDecimal("95.01"));
            assertTrue(triggered.isEmpty());
        }

        @Test
        void multipleStops_triggeredSelectively() {
            book.addStopOrder(stopBuy("ST1", 50,  "100.00"));
            book.addStopOrder(stopBuy("ST2", 50,  "110.00"));
            book.addStopOrder(stopSell("ST3", 50, "90.00"));

            List<Order> triggered = book.checkStops(new BigDecimal("105.00"));
            // ST1 triggered (105 >= 100), ST2 not (105 < 110), ST3 not (105 > 90)
            assertEquals(1, triggered.size());
            assertEquals("ST1", triggered.get(0).getClOrdId());
        }

        @Test
        void checkStops_nullPrice_nothingTriggered() {
            book.addStopOrder(stopBuy("ST1", 100, "100.00"));
            assertTrue(book.checkStops(null).isEmpty());
        }

        @Test
        void triggeredStop_removedFromPendingList() {
            Order stop = stopBuy("ST1", 100, "100.00");
            book.addStopOrder(stop);
            book.checkStops(new BigDecimal("100.00"));
            // second check should return nothing (already removed)
            List<Order> again = book.checkStops(new BigDecimal("100.00"));
            assertTrue(again.isEmpty());
        }
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrency – synchronized operations")
    class ConcurrencyTest {

        @Test
        void concurrentAddAndMatch_noDeadlock() throws InterruptedException {
            int threads = 8;
            int ordersPer = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ordersPer; i++) {
                            String id = "T" + tid + "O" + i;
                            if (tid % 2 == 0) {
                                book.addLimitOrder(new Order(SESS, id, null, SYM,
                                        OrderSide.SELL, OrderType.LIMIT, TimeInForce.DAY,
                                        10, new BigDecimal("100.00"), null));
                            } else {
                                Order buy = new Order(SESS, id, null, SYM,
                                        OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                                        10, new BigDecimal("100.00"), null);
                                book.match(buy);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();
            pool.shutdown();

            assertEquals(0, errors.get(), "No exceptions expected during concurrent access");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertFillExec(Execution e, Order expectedOrder,
                                       long qty, String price,
                                       Execution.ExecType type) {
        assertSame(expectedOrder, e.getOrder());
        assertEquals(qty,                     e.getQty());
        assertEquals(new BigDecimal(price),   e.getPrice());
        assertEquals(type,                    e.getExecType());
    }
}
