package com.fix.simulator.defects;

import com.fix.simulator.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect regression tests D6 in {@link MatchingEngine}:
 * <ul>
 *   <li>{@code getTradedSymbols()} iterates the trade log without holding its lock, so a trade
 *       added during the iteration throws {@link ConcurrentModificationException}. The
 *       concurrent add is simulated deterministically by a list that appends during iteration.</li>
 *   <li>Order, trade and execution histories are never pruned. The retention cap used below
 *       (10,000 trades / completed orders, 100,000 execution records) is an assumed bound
 *       chosen for this test, since the code has no cap at all.</li>
 * </ul>
 */
@DisplayName("Defect D6 – unsynchronised iteration and unbounded history")
class ResourceDefectsTest {

    private static final String SYM = "AAPL";
    private static final int    ASSUMED_TRADE_CAP     = 10_000;
    private static final int    ASSUMED_EXEC_CAP      = 100_000;

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
    }

    /**
     * Trade log with a concurrent writer. During the first iteration step a second thread appends a
     * trade, the way production writers do: under the list's monitor. The reader gets a window of
     * {@link #WINDOW_MS} to see whether the writer gets in while iteration is in progress.
     */
    static class ConcurrentWriterList extends LinkedList<Map<String, Object>> {
        boolean armed = true;
        Thread writer;

        @Override
        public Iterator<Map<String, Object>> iterator() {
            Iterator<Map<String, Object>> it = super.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public Map<String, Object> next() {
                    Map<String, Object> m = it.next();
                    if (armed) {
                        armed = false;
                        writer = new Thread(() -> {
                            Map<String, Object> extra = new LinkedHashMap<>();
                            extra.put("symbol", "MSFT");
                            synchronized (ConcurrentWriterList.this) { ConcurrentWriterList.this.add(extra); }
                        }, "concurrent-trade-writer");
                        writer.start();
                        try { writer.join(WINDOW_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    return m;
                }
            };
        }
    }

    private static final long WINDOW_MS = 500;

    private void roundTrip(long i) {
        String px = "10.00";
        engine.newOrder(new Order("CLIENT2:S", "S" + i, null, SYM, OrderSide.SELL, OrderType.LIMIT,
                TimeInForce.DAY, 1, new BigDecimal(px), null));
        engine.newOrder(new Order("CLIENT3:S", "B" + i, null, SYM, OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.DAY, 1, new BigDecimal(px), null));
    }

    @Nested
    @DisplayName("D6a – getTradedSymbols() unsynchronised iteration")
    class UnsynchronisedIteration {

        @Test
        void tradedSymbols_survivesConcurrentTradeDuringIteration() throws Exception {
            roundTrip(1);
            roundTrip(2);   // two trades so the iterator has a second step after the concurrent add
            ConcurrentWriterList trap = new ConcurrentWriterList();
            trap.addAll(engine.getTradeLog(null));
            Field f = MatchingEngine.class.getDeclaredField("tradeLog");
            f.setAccessible(true);
            f.set(engine, trap);

            assertDoesNotThrow(() -> engine.getTradedSymbols(),
                    "getTradedSymbols must tolerate a concurrent trade append");   // actual: CME
            assertNotNull(trap.writer, "writer thread should have started during iteration");
            trap.writer.join(5_000);
        }

        @Test
        void control_tradedSymbols_noConcurrentWriter_returnsSymbol() {
            roundTrip(1);
            assertEquals(Set.of(SYM), engine.getTradedSymbols());
        }

        @Test
        void control_tradedSymbols_noTrades_isEmpty() {
            assertTrue(engine.getTradedSymbols().isEmpty());
        }
    }

    @Nested
    @DisplayName("D6b – history is never pruned")
    class UnboundedHistory {

        @Test
        void tradeLog_isBounded() {
            for (long i = 0; i < 30_000; i++) roundTrip(i);
            int size = engine.getTradeLog(null).size();
            assertTrue(size <= ASSUMED_TRADE_CAP, "trade log holds " + size + " records, no cap");   // actual: 30000
        }

        @Test
        void completedOrders_isBounded() {
            for (long i = 0; i < 30_000; i++) roundTrip(i);
            int size = engine.getCompletedOrders(null).size();
            assertTrue(size <= ASSUMED_TRADE_CAP, "completed-order register holds " + size + " orders");   // actual: 60000
        }

        @Test
        void executionLog_isBounded() {
            for (long i = 0; i < 30_000; i++) roundTrip(i);
            int size = engine.getExecutionLog(null, null).size();
            assertTrue(size <= ASSUMED_EXEC_CAP, "execution log holds " + size + " records, no cap");   // actual: 120000
        }

        @Test
        void control_smallHistory_isFullyRetained() {
            for (long i = 0; i < 50; i++) roundTrip(i);
            assertEquals(50, engine.getTradeLog(null).size());
            assertEquals(100, engine.getCompletedOrders(null).size());
            // per round trip: sell NEW + buy NEW + aggressor FILL + passive FILL = 4 records
            assertEquals(200, engine.getExecutionLog(null, null).size());
        }
    }
}
