package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offset / limit paging over the retained trade, execution and order logs. Newest first. */
@DisplayName("MatchingEngine – paged reads")
class PagingTest {

    private static final String AAPL = "AAPL";
    private static final String MSFT = "MSFT";

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(AAPL);
        engine.registerSymbol(MSFT);
    }

    /** Submit an IOC order that is cancelled at once (no liquidity), so it leaves NEW and CANCELLED execs. */
    private void iocCancelled(String clOrdId, String sym, String sess) {
        engine.newOrder(new Order(sess, clOrdId, null, sym, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.IOC, 1, new BigDecimal("1.00"), null));
    }

    private void restingSell(String clOrdId, String sym, long qty, String px, String sess) {
        engine.newOrder(new Order(sess, clOrdId, null, sym, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null));
    }

    private void aggressorBuy(String clOrdId, String sym, long qty, String px, String sess) {
        engine.newOrder(new Order(sess, clOrdId, null, sym, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.IOC, qty, new BigDecimal(px), null));
    }

    @Nested
    @DisplayName("argument handling")
    class Arguments {

        @Test
        @DisplayName("offset past the end returns total but no rows")
        void offsetPastEnd() {
            iocCancelled("I1", AAPL, "S:A");
            Page<Map<String, Object>> p = engine.getExecutionsPage(null, null, 50, 10);
            assertEquals(2, p.total());   // NEW + CANCELLED for one order
            assertTrue(p.rows().isEmpty());
            assertEquals(50, p.offset());
            assertEquals(10, p.limit());
        }

        @Test
        @DisplayName("an empty log gives total 0 and no rows")
        void emptyLog() {
            Page<Map<String, Object>> p = engine.getTradesPage(null, 0, 25);
            assertEquals(0, p.total());
            assertTrue(p.rows().isEmpty());
        }
    }

    @Nested
    @DisplayName("executions")
    class Executions {

        @Test
        @DisplayName("first page holds the newest rows and the next page continues the sequence")
        void newestFirstAndNextPage() {
            for (int i = 1; i <= 5; i++) iocCancelled("I" + i, AAPL, "S:A");   // 10 executions
            Page<Map<String, Object>> first = engine.getExecutionsPage(null, null, 0, 4);
            Page<Map<String, Object>> second = engine.getExecutionsPage(null, null, 4, 4);

            assertEquals(10, first.total());
            assertEquals(4, first.rows().size());
            assertEquals(4, second.rows().size());
            // Newest order (I5) is first: its last execution is the CANCELLED report.
            assertEquals("I5", first.rows().get(0).get("clOrdId"));
            // No row appears on both pages.
            assertNotEquals(first.rows().get(3).get("execId"), second.rows().get(0).get("execId"));
        }

        @Test
        @DisplayName("symbol filter counts only that symbol")
        void symbolFilter() {
            iocCancelled("A1", AAPL, "S:A");
            iocCancelled("M1", MSFT, "S:A");
            iocCancelled("M2", MSFT, "S:A");
            assertEquals(2, engine.getExecutionsPage(AAPL, null, 0, 10).total());
            assertEquals(4, engine.getExecutionsPage(MSFT, null, 0, 10).total());
        }

        @Test
        @DisplayName("client filter matches the session-id substring")
        void clientFilter() {
            iocCancelled("A1", AAPL, "FIX.4.2:EXCHANGE->CLIENT1");
            iocCancelled("A2", AAPL, "FIX.4.2:EXCHANGE->CLIENT2");
            assertEquals(2, engine.getExecutionsPage(null, "CLIENT1", 0, 10).total());
            assertEquals(0, engine.getExecutionsPage(null, "CLIENT9", 0, 10).total());
        }

        @Test
        @DisplayName("blank symbol and client mean no filter")
        void blankFiltersIgnored() {
            iocCancelled("A1", AAPL, "S:A");
            assertEquals(2, engine.getExecutionsPage("", "", 0, 10).total());
        }
    }

    @Nested
    @DisplayName("trades")
    class Trades {

        @Test
        @DisplayName("newest trade first, with symbol filter")
        void tradesNewestFirst() {
            restingSell("S1", AAPL, 10, "10.00", "S:A");
            aggressorBuy("B1", AAPL, 1, "10.00", "S:B");   // trade qty 1
            restingSell("S2", AAPL, 10, "11.00", "S:A");
            aggressorBuy("B2", AAPL, 2, "11.00", "S:B");   // trade qty 2
            restingSell("S3", MSFT, 10, "5.00", "S:A");
            aggressorBuy("B3", MSFT, 3, "5.00", "S:B");    // trade qty 3

            Page<Map<String, Object>> all = engine.getTradesPage(null, 0, 10);
            assertEquals(3, all.total());
            assertEquals(3, ((Number) all.rows().get(0).get("qty")).intValue());   // newest: MSFT

            Page<Map<String, Object>> aapl = engine.getTradesPage(AAPL, 0, 1);
            assertEquals(2, aapl.total());
            assertEquals(2, ((Number) aapl.rows().get(0).get("qty")).intValue());  // newest AAPL
        }
    }

    @Nested
    @DisplayName("orders")
    class Orders {

        @Test
        @DisplayName("active orders are paged newest first")
        void activeOrdersNewestFirst() {
            for (int i = 1; i <= 3; i++) {
                engine.newOrder(new Order("S:A", "R" + i, null, AAPL, OrderSide.BUY,
                        OrderType.LIMIT, TimeInForce.DAY, 1, new BigDecimal("9.00"), null));
            }
            Page<Order> p = engine.getActiveOrdersPage(null, 0, 2);
            assertEquals(3, p.total());
            assertEquals(2, p.rows().size());
            assertEquals("R3", p.rows().get(0).getClOrdId());
            assertEquals("R2", p.rows().get(1).getClOrdId());
            assertEquals("R1", engine.getActiveOrdersPage(null, 2, 2).rows().get(0).getClOrdId());
        }

        @Test
        @DisplayName("completed orders are paged newest first")
        void completedOrdersNewestFirst() {
            iocCancelled("I1", AAPL, "S:A");
            iocCancelled("I2", AAPL, "S:A");
            Page<Order> p = engine.getCompletedOrdersPage(AAPL, 0, 1);
            assertEquals(2, p.total());
            assertEquals("I2", p.rows().get(0).getClOrdId());
        }

        @Test
        @DisplayName("active page with symbol filter")
        void activeOrdersSymbolFilter() {
            engine.newOrder(new Order("S:A", "A1", null, AAPL, OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 1, new BigDecimal("9.00"), null));
            engine.newOrder(new Order("S:A", "M1", null, MSFT, OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 1, new BigDecimal("9.00"), null));
            Page<Order> p = engine.getActiveOrdersPage(MSFT, 0, 10);
            assertEquals(1, p.total());
            assertEquals("M1", p.rows().get(0).getClOrdId());
        }
    }

    @Test
    @DisplayName("pageOf keeps the window and counts every match")
    void pageOfWindow() {
        List<Integer> rows = List.of(1, 2, 3, 4, 5, 6);
        Page<Integer> p = MatchingEngine.pageOf(rows.iterator(), x -> x % 2 == 0, x -> x * 10, 1, 2);
        assertEquals(3, p.total());          // 2, 4, 6 match
        assertEquals(List.of(40, 60), p.rows());
    }
}
