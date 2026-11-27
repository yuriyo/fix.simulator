package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifetime totals must count every order, trade and execution since start, even after the
 * retained history has evicted the oldest rows.
 */
@DisplayName("MatchingEngine – lifetime counters")
class LifetimeCountersTest {

    private static final String SYM  = "AAPL";
    private static final String MSFT = "MSFT";
    private static final String SESS = "FIX.4.2:EXCHANGE->CLIENT1";

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
        engine.registerSymbol(MSFT);
    }

    private Order order(String clOrdId, String sym, OrderSide side, TimeInForce tif, long qty, String px) {
        return new Order(SESS, clOrdId, null, sym, side, OrderType.LIMIT, tif, qty, new BigDecimal(px), null);
    }

    @Nested
    @DisplayName("order status counts")
    class OrderStatusCountsTests {

        @Test
        @DisplayName("a new GTC order counts as open")
        void restingOrderIsOpen() {
            engine.newOrder(order("R1", SYM, OrderSide.BUY, TimeInForce.DAY, 10, "10.00"));
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(1, s.orders());
            assertEquals(1, s.open());
            assertEquals(0, s.cancelled());
        }

        @Test
        @DisplayName("an IOC order with no liquidity is cancelled at once")
        void iocWithoutLiquidityIsCancelled() {
            engine.newOrder(order("I1", SYM, OrderSide.BUY, TimeInForce.IOC, 10, "10.00"));
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(1, s.orders());
            assertEquals(0, s.open());
            assertEquals(1, s.cancelled());
        }

        @Test
        @DisplayName("a full fill moves the resting order to FILLED and the aggressor too")
        void fullFillCountsBothOrders() {
            engine.newOrder(order("S1", SYM, OrderSide.SELL, TimeInForce.DAY, 10, "10.00"));
            engine.newOrder(order("B1", SYM, OrderSide.BUY,  TimeInForce.IOC, 10, "10.00"));
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(2, s.orders());
            assertEquals(2, s.filled());
            assertEquals(0, s.open());
            assertEquals(1, s.trades());
        }

        @Test
        @DisplayName("a partial fill keeps the resting order open")
        void partialFillStaysOpen() {
            engine.newOrder(order("S1", SYM, OrderSide.SELL, TimeInForce.DAY, 10, "10.00"));
            engine.newOrder(order("B1", SYM, OrderSide.BUY,  TimeInForce.IOC, 4, "10.00"));
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(1, s.open());   // the sell, partly filled
            assertEquals(1, s.filled()); // the buy, fully filled
        }

        @Test
        @DisplayName("a cancel moves the order to CANCELLED")
        void cancelCounts() {
            engine.newOrder(order("R1", SYM, OrderSide.BUY, TimeInForce.DAY, 10, "10.00"));
            engine.cancelOrder(SESS, "R1", "C1", SYM);
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(1, s.orders());
            assertEquals(0, s.open());
            assertEquals(1, s.cancelled());
        }

        @Test
        @DisplayName("an order for an unknown symbol is rejected and not counted")
        void unknownSymbolNotCounted() {
            engine.newOrder(order("X1", "NOPE", OrderSide.BUY, TimeInForce.DAY, 10, "10.00"));
            assertEquals(0, engine.getLifetimeStats().orders());
        }

        @Test
        @DisplayName("by-status map covers every status and sums to the total")
        void byStatusSumsToTotal() {
            engine.newOrder(order("R1", SYM, OrderSide.BUY, TimeInForce.DAY, 10, "10.00"));
            engine.newOrder(order("I1", SYM, OrderSide.BUY, TimeInForce.IOC, 10, "10.00"));
            var byStatus = engine.getLifetimeOrdersByStatus();
            assertEquals(OrderStatus.values().length, byStatus.size());
            assertEquals(1L, byStatus.get("NEW"));
            assertEquals(1L, byStatus.get("CANCELLED"));
            assertEquals(2L, byStatus.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    @Nested
    @DisplayName("trades and executions")
    class TradesAndExecutions {

        @Test
        @DisplayName("executions count every report, including acks")
        void executionsCountAcks() {
            engine.newOrder(order("R1", SYM, OrderSide.BUY, TimeInForce.DAY, 10, "10.00"));
            assertEquals(1, engine.getLifetimeStats().executions());   // NEW ack only
        }

        @Test
        @DisplayName("symbol totals track trade count and quantity per symbol")
        void symbolTotals() {
            engine.newOrder(order("S1", SYM, OrderSide.SELL, TimeInForce.DAY, 10, "10.00"));
            engine.newOrder(order("B1", SYM, OrderSide.BUY,  TimeInForce.IOC, 6, "10.00"));
            engine.newOrder(order("S2", SYM, OrderSide.SELL, TimeInForce.DAY, 5, "10.00"));
            engine.newOrder(order("B2", SYM, OrderSide.BUY,  TimeInForce.IOC, 5, "10.00"));
            // B1 trades 6 with S1; B2 trades 4 with S1's remainder and 1 with S2: three trades.
            MatchingEngine.SymbolTotals aapl = engine.getLifetimeSymbolTotals(SYM);
            assertEquals(3, aapl.trades());
            assertEquals(11, aapl.volume());
            assertEquals(new MatchingEngine.SymbolTotals(0, 0), engine.getLifetimeSymbolTotals(MSFT));
        }
    }

    @Nested
    @DisplayName("admin book levels")
    class AdminLevels {

        @Test
        @DisplayName("a level set from the admin UI is an open order in the totals")
        void levelCounts() {
            engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 100);
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(1, s.orders());
            assertEquals(1, s.open());
            assertEquals(1L, engine.getLifetimeOrdersByStatus().get("NEW"));
        }

        @Test
        @DisplayName("replacing a level counts the old order as cancelled")
        void replaceCounts() {
            engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 100);
            engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 50);
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(2, s.orders());
            assertEquals(1, s.open());
            assertEquals(1, s.cancelled());
        }

        @Test
        @DisplayName("removing a level (qty 0) leaves nothing open")
        void removeCounts() {
            engine.setAdminBookLevel(SYM, OrderSide.SELL, new BigDecimal("11.00"), 100);
            engine.setAdminBookLevel(SYM, OrderSide.SELL, new BigDecimal("11.00"), 0);
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(0, s.open());
            assertEquals(1, s.cancelled());
        }

        @Test
        @DisplayName("cancelling an admin level from the Orders tab is counted")
        void cancelCounts() {
            engine.setAdminBookLevel(MSFT, OrderSide.BUY, new BigDecimal("9.00"), 100);
            Order level = engine.getAllActiveOrders(MSFT).get(0);
            engine.cancelOrder("ADMIN", level.getClOrdId(), "ADMIN-CXL", MSFT);
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(0, s.open());
            assertEquals(1, s.cancelled());
        }
    }

    @Nested
    @DisplayName("beyond the retained history cap")
    class BeyondRetention {

        @Test
        @DisplayName("lifetime orders keep counting after the order history has evicted rows")
        void countsSurviveEviction() {
            int n = MatchingEngine.ORDER_HISTORY_CAP + 100;
            for (int i = 0; i < n; i++) {
                engine.newOrder(order("I" + i, SYM, OrderSide.BUY, TimeInForce.IOC, 1, "1.00"));
            }
            MatchingEngine.LifetimeStats s = engine.getLifetimeStats();
            assertEquals(n, s.orders());
            assertEquals(n, s.cancelled());
            assertEquals(0, s.open());
            // The retained window is capped, the lifetime count is not.
            long retained = engine.getCompletedOrdersPage(null, 0, 1).total();
            assertTrue(retained <= MatchingEngine.ORDER_HISTORY_CAP, "retained=" + retained);
        }
    }
}
