package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Branch and path coverage for {@link OrderBook}. */
@DisplayName("OrderBook – coverage of remaining branches")
class OrderBookCoverageTest {

    private static final String SYM = "AAPL";
    private OrderBook book;

    @BeforeEach
    void setUp() { book = new OrderBook(SYM); }

    private static Order lim(String sess, String c, OrderSide s, OrderType t, TimeInForce tif,
                             long q, String px, String stop) {
        return new Order(sess, c, null, SYM, s, t, tif, q,
                px == null ? null : new BigDecimal(px), stop == null ? null : new BigDecimal(stop));
    }

    private static Order limit(String c, OrderSide s, long q, String px) {
        return lim("S:" + c, c, s, OrderType.LIMIT, TimeInForce.GTC, q, px, null);
    }

    @Nested
    @DisplayName("lookup and indexing")
    class Lookup {

        @Test
        void symbolAndEmptyBestPrices() {
            assertEquals(SYM, book.getSymbol());
            assertNull(book.bestBid());
            assertNull(book.bestAsk());
        }

        @Test
        void findByClientKeyAndId_hitAndMiss() {
            Order o = limit("A1", OrderSide.BUY, 5, "10");
            book.addLimitOrder(o);
            assertSame(o, book.findByClientKey("S:A1", "A1"));
            assertNull(book.findByClientKey("S:A1", "NOPE"));
            assertSame(o, book.findById(o.getInternalId()));
            assertNull(book.findById(-1L));
        }

        @Test
        void deindex_removesBothKeys() {
            Order o = limit("A1", OrderSide.BUY, 5, "10");
            book.addLimitOrder(o);
            book.deindex(o);
            assertNull(book.findByClientKey("S:A1", "A1"));
            assertNull(book.findById(o.getInternalId()));
        }

        @Test
        void addLimitOrderWithoutPrice_throws() {
            Order noPx = lim("S", "X", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, 1, null, null);
            assertThrows(IllegalArgumentException.class, () -> book.addLimitOrder(noPx));
        }
    }

    @Nested
    @DisplayName("removal")
    class Removal {

        @Test
        void removeLimit_levelMissing_false() {
            assertFalse(book.removeLimit(limit("A1", OrderSide.BUY, 5, "10")));
        }

        @Test
        void removeLimit_orderAbsentFromExistingLevel_false_levelKept() {
            book.addLimitOrder(limit("A1", OrderSide.BUY, 5, "10"));
            assertFalse(book.removeLimit(limit("A2", OrderSide.BUY, 5, "10")));
            assertEquals(5L, book.getLevelQty(OrderSide.BUY, new BigDecimal("10")));
        }

        @Test
        void removeLimit_lastOrderAtLevel_levelDeleted() {
            Order o = limit("A1", OrderSide.SELL, 5, "10");
            book.addLimitOrder(o);
            assertTrue(book.removeLimit(o));
            assertNull(book.bestAsk());
        }

        @Test
        void removeStop_presentAndAbsent() {
            Order s = lim("S", "ST", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, "9");
            book.addStopOrder(s);
            assertTrue(book.removeStop(s));
            assertFalse(book.removeStop(s));
        }
    }

    @Nested
    @DisplayName("matching paths")
    class Matching {

        @Test
        void sellAggressor_matchesBidsAtOrBetterPrice() {
            book.addLimitOrder(limit("B1", OrderSide.BUY, 5, "10"));
            book.addLimitOrder(limit("B2", OrderSide.BUY, 5, "9"));
            // Sell 7 @ 9.5 hits the 10 bid for 5, then stops at the 9 bid (below its limit).
            Order sell = lim("S", "SA", OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, 7, "9.5", null);
            List<Execution> fills = book.match(sell);
            assertEquals(2, fills.size());                       // aggressor + passive execution
            assertEquals(5L, fills.get(0).getQty());
            assertEquals(2L, sell.getOpenQty());
            assertEquals(OrderStatus.PARTIALLY_FILLED, sell.getStatus());
            assertEquals(0, book.bestBid().compareTo(new BigDecimal("9")));
        }

        @Test
        void limitAggressorNotCrossing_noFills() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 5, "11"));
            Order buy = lim("S", "BA", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 5, "10", null);
            assertTrue(book.match(buy).isEmpty());
            assertEquals(0, buy.getFilledQty());
        }

        @Test
        void fokSell_sufficientLiquidity_filled() {
            book.addLimitOrder(limit("B1", OrderSide.BUY, 5, "10"));
            Order sell = lim("S", "FS", OrderSide.SELL, OrderType.LIMIT, TimeInForce.FOK, 5, "9", null);
            book.match(sell);
            assertEquals(OrderStatus.FILLED, sell.getStatus());
        }

        @Test
        void fokSell_insufficientLiquidity_cancelledUntouched() {
            book.addLimitOrder(limit("B1", OrderSide.BUY, 2, "10"));
            Order sell = lim("S", "FS", OrderSide.SELL, OrderType.LIMIT, TimeInForce.FOK, 5, "9", null);
            List<Execution> r = book.match(sell);
            assertEquals(OrderStatus.CANCELLED, sell.getStatus());
            assertEquals(Execution.ExecType.CANCELLED, r.get(0).getExecType());
            assertEquals(2L, book.getLevelQty(OrderSide.BUY, new BigDecimal("10")));
        }

        @Test
        void fokBuyLimit_levelsBeyondLimitNotCounted() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 3, "10"));
            book.addLimitOrder(limit("A2", OrderSide.SELL, 4, "11"));   // beyond limit 10.5
            Order buy = lim("S", "FB", OrderSide.BUY, OrderType.LIMIT, TimeInForce.FOK, 5, "10.5", null);
            book.match(buy);
            assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        }

        @Test
        void marketFokBuy_sumsAllLevels() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 3, "10"));
            book.addLimitOrder(limit("A2", OrderSide.SELL, 4, "50"));
            Order m = lim("S", "MF", OrderSide.BUY, OrderType.MARKET, TimeInForce.FOK, 7, null, null);
            book.match(m);
            assertEquals(OrderStatus.FILLED, m.getStatus());
        }

        @Test
        void iocRemainderCancelled() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 2, "10"));
            Order ioc = lim("S", "IO", OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC, 5, "10", null);
            List<Execution> r = book.match(ioc);
            assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
            assertEquals(Execution.ExecType.CANCELLED, r.get(r.size() - 1).getExecType());
        }

        @Test
        void marketOrderSweepsAllLevels() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 2, "10"));
            book.addLimitOrder(limit("A2", OrderSide.SELL, 2, "99"));
            Order m = lim("S", "MK", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, 4, null, null);
            book.match(m);
            assertEquals(4, m.getFilledQty());
            assertNull(book.bestAsk());
        }
    }

    @Nested
    @DisplayName("stop triggering")
    class StopTriggers {

        @Test
        void nullLastPrice_noTrigger() {
            book.addStopOrder(lim("S", "ST", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, "9"));
            assertTrue(book.checkStops(null).isEmpty());
        }

        @Test
        void buyStopTriggersOnRise_sellStopOnFall_otherStaysParked() {
            Order buy = lim("S", "B", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, "10");
            Order sell = lim("S", "S", OrderSide.SELL, OrderType.STOP, TimeInForce.GTC, 1, null, "8");
            Order parked = lim("S", "P", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, "20");
            book.addStopOrder(buy);
            book.addStopOrder(sell);
            book.addStopOrder(parked);
            List<Order> t = book.checkStops(new BigDecimal("10"));
            assertEquals(List.of(buy), t);
            assertTrue(buy.isTriggered());
            assertEquals(List.of(sell), book.checkStops(new BigDecimal("7")));
            assertFalse(parked.isTriggered());
        }

        @Test
        void stopWithoutStopPrice_neverTriggers() {
            book.addStopOrder(lim("S", "N", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, null));
            assertTrue(book.checkStops(new BigDecimal("100")).isEmpty());
        }
    }

    @Nested
    @DisplayName("snapshots and depth")
    class Snapshots {

        @Test
        void activeOrdersIncludeRestingAndPendingStops() {
            book.addLimitOrder(limit("A1", OrderSide.BUY, 1, "10"));
            book.addLimitOrder(limit("A2", OrderSide.SELL, 1, "11"));
            book.addStopOrder(lim("S", "ST", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 1, null, "9"));
            assertEquals(3, book.getActiveOrders().size());
        }

        @Test
        void levelsRespectDepth() {
            for (int i = 1; i <= 5; i++) book.addLimitOrder(limit("B" + i, OrderSide.BUY, i, String.valueOf(10 - i)));
            for (int i = 1; i <= 5; i++) book.addLimitOrder(limit("A" + i, OrderSide.SELL, i, String.valueOf(20 + i)));
            assertEquals(2, book.getBidLevels(2).size());
            assertEquals("9", book.getBidLevels(2).get(0).get("price"));
            assertEquals(2, book.getAskLevels(2).size());
            assertEquals(5, book.getBidLevels(50).size());
        }
    }

    @Nested
    @DisplayName("admin levels")
    class AdminLevels {

        @Test
        void setAdminLevel_replacesExistingLevelAndReturnsOrder() {
            book.addLimitOrder(limit("A1", OrderSide.BUY, 5, "10"));
            Order admin = book.setAdminLevel(OrderSide.BUY, new BigDecimal("10"), 42);
            assertNotNull(admin);
            assertEquals("ADMIN", admin.getSessionId());
            assertEquals(42L, book.getLevelQty(OrderSide.BUY, new BigDecimal("10")));
            assertNull(book.findByClientKey("S:A1", "A1"));
        }

        @Test
        void setAdminLevel_zeroQtyRemovesLevel() {
            book.addLimitOrder(limit("A1", OrderSide.SELL, 5, "10"));
            assertNull(book.setAdminLevel(OrderSide.SELL, new BigDecimal("10"), 0));
            assertNull(book.bestAsk());
        }

        @Test
        void setAdminLevel_zeroQtyOnEmptyLevel_noop() {
            assertNull(book.setAdminLevel(OrderSide.SELL, new BigDecimal("10"), -1));
        }

        @Test
        void getLevelQty_absentLevelIsZero() {
            assertEquals(0L, book.getLevelQty(OrderSide.BUY, new BigDecimal("1")));
        }
    }
}
