package com.fix.simulator.defects;

import com.fix.simulator.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect regression tests D4: fills produced by a triggered stop order are written to the
 * execution log twice (once by the nested {@code afterFills} call, once by the outer one).
 *
 * <p>Assertions state the correct behaviour (one FILL record per fill execution).
 */
@DisplayName("Defect D4 – triggered-stop fills logged twice in execution log")
class StopExecutionLogDefectTest {

    private static final String SYM = "AAPL";

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
    }

    private Order order(String sess, String clOrdId, OrderSide side, OrderType type,
                        TimeInForce tif, long qty, String px, String stopPx) {
        return new Order(sess, clOrdId, null, SYM, side, type, tif, qty,
                px == null ? null : new BigDecimal(px),
                stopPx == null ? null : new BigDecimal(stopPx));
    }

    private Order limit(String sess, String clOrdId, OrderSide side, long qty, String px) {
        return order(sess, clOrdId, side, OrderType.LIMIT, TimeInForce.DAY, qty, px, null);
    }

    /** Number of fill-type execution-log records that refer to the given order. */
    private long fillRecords(Order o) {
        return engine.getExecutionLog(SYM, null).stream()
                .filter(m -> Long.valueOf(o.getInternalId()).equals(m.get("orderId")))
                .filter(m -> "FILL".equals(m.get("execType")) || "PARTIAL_FILL".equals(m.get("execType")))
                .count();
    }

    /**
     * Scenario: resting sell 5@10.00 (P1), resting sell 2@10.10 (P2), pending buy STOP 2 @10.00 (S),
     * then aggressor buy 5@10.00 (A). A fills P1 at 10.00, which triggers S; S then market-fills P2.
     */
    private record Scenario(Order p1, Order p2, Order stop, Order aggressor) {}

    private Scenario runStopScenario() {
        Order p1 = limit("CLIENT2:S", "P1", OrderSide.SELL, 5, "10.00");
        Order p2 = limit("CLIENT2:S", "P2", OrderSide.SELL, 2, "10.10");
        Order stop = order("CLIENT3:S", "S1", OrderSide.BUY, OrderType.STOP, TimeInForce.GTC, 2, null, "10.00");
        Order aggr = limit("CLIENT4:S", "A1", OrderSide.BUY, 5, "10.00");
        engine.newOrder(p1);
        engine.newOrder(p2);
        engine.newOrder(stop);
        engine.newOrder(aggr);
        assertTrue(stop.isTriggered(), "precondition: stop must have been triggered by the aggressor fill");
        return new Scenario(p1, p2, stop, aggr);
    }

    @Nested
    @DisplayName("D4 – duplicate fill records for triggered stop")
    class DuplicateRecords {

        @Test
        void stopOrderFill_isLoggedExactlyOnce() {
            Scenario s = runStopScenario();
            assertEquals(1, fillRecords(s.stop()), "stop fill logged " + fillRecords(s.stop()) + " times");   // actual: 2
        }

        @Test
        void passiveSideOfStopFill_isLoggedExactlyOnce() {
            Scenario s = runStopScenario();
            assertEquals(1, fillRecords(s.p2()), "passive P2 fill logged " + fillRecords(s.p2()) + " times"); // actual: 2
        }

        @Test
        void totalFillRecords_matchFillExecutions() {
            runStopScenario();
            // 4 fill executions: A vs P1, P1 side, S vs P2, P2 side
            long total = engine.getExecutionLog(SYM, null).stream()
                    .filter(m -> "FILL".equals(m.get("execType")) || "PARTIAL_FILL".equals(m.get("execType")))
                    .count();
            assertEquals(4, total, "expected 4 fill records, got " + total);   // actual: 6
        }

        @Test
        void control_aggressorFill_isLoggedExactlyOnce() {
            Scenario s = runStopScenario();
            assertEquals(1, fillRecords(s.aggressor()));
        }

        @Test
        void control_passiveFillWithoutStop_isLoggedExactlyOnce() {
            Order p3 = limit("CLIENT2:S", "P3", OrderSide.SELL, 3, "10.00");
            Order a2 = limit("CLIENT4:S", "A2", OrderSide.BUY, 3, "10.00");
            engine.newOrder(p3);
            engine.newOrder(a2);
            assertEquals(1, fillRecords(p3));
        }
    }

    @Nested
    @DisplayName("D4 – trade log for the same scenario")
    class TradeLogForScenario {

        @Test
        void control_oneTradePerAggressorFill() {
            runStopScenario();
            // A's fill and the stop's fill are each one trade record
            assertEquals(2, engine.getTradeLog(SYM).size());
        }

        /** Order of records is not asserted: the stop's trade is recorded before the triggering trade. */
        @Test
        void control_tradePricesAreCorrect() {
            runStopScenario();
            List<Map<String, Object>> t = engine.getTradeLog(SYM);
            Set<BigDecimal> prices = new HashSet<>();
            for (Map<String, Object> m : t) prices.add(new BigDecimal((String) m.get("price")));
            assertEquals(Set.of(new BigDecimal("10.00"), new BigDecimal("10.10")), prices);
        }
    }
}
