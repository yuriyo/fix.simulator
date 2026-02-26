package com.fix.simulator.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Execution – construction and accessors")
class ExecutionTest {

    private Order order() {
        return new Order("sess", "C1", null, "AAPL",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                100, new BigDecimal("100.00"), null);
    }

    @Test
    void construction() {
        Order o = order();
        Execution e = new Execution(o, 50, new BigDecimal("99.50"), Execution.ExecType.PARTIAL_FILL);

        assertNotNull(e.getExecId());
        assertTrue(e.getExecId().startsWith("E"));
        assertSame(o, e.getOrder());
        assertEquals(50, e.getQty());
        assertEquals(new BigDecimal("99.50"), e.getPrice());
        assertEquals(Execution.ExecType.PARTIAL_FILL, e.getExecType());
        assertNotNull(e.getTime());
    }

    @Test
    void nullPriceAllowed() {
        Execution e = new Execution(order(), 0, null, Execution.ExecType.NEW);
        assertNull(e.getPrice());
        assertEquals(0, e.getQty());
    }

    @Test
    void uniqueExecIds() {
        Order o = order();
        Execution e1 = new Execution(o, 10, BigDecimal.TEN, Execution.ExecType.FILL);
        Execution e2 = new Execution(o, 10, BigDecimal.TEN, Execution.ExecType.FILL);
        assertNotEquals(e1.getExecId(), e2.getExecId());
    }

    @Test
    void allExecTypesReachable() {
        for (Execution.ExecType t : Execution.ExecType.values()) {
            Execution e = new Execution(order(), 0, null, t);
            assertEquals(t, e.getExecType());
        }
    }
}
