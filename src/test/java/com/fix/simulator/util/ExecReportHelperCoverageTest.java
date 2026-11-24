package com.fix.simulator.util;

import com.fix.simulator.engine.Execution;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderSide;
import com.fix.simulator.engine.OrderType;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import quickfix.field.AvgPx;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.fix42.ExecutionReport;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Branch coverage for the optional-field and average-price paths of {@link ExecReportHelper}. */
@DisplayName("ExecReportHelper – optional fields and average price")
class ExecReportHelperCoverageTest {

    private static Order limitOrder(String origClOrdId) {
        return new Order("S", "C1", origClOrdId, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.DAY, 10, new BigDecimal("10.25"), null);
    }

    private static Order stopOrder() {
        return new Order("S", "C2", null, "AAPL", OrderSide.SELL, OrderType.STOP,
                TimeInForce.GTC, 10, null, new BigDecimal("9.00"));
    }

    @Nested
    @DisplayName("optional fields")
    class OptionalFields {

        @Test
        void replacementOrder_carriesOrigClOrdId_andLimitPrice() throws Exception {
            ExecutionReport r = ExecReportHelper.build(
                    new Execution(limitOrder("ORIG"), 0, null, Execution.ExecType.REPLACED));
            assertEquals("ORIG", r.getString(OrigClOrdID.FIELD));
            assertEquals(10.25, r.getDouble(Price.FIELD), 1e-9);
        }

        @Test
        void newOrder_withoutOrigClOrdId_stopOrder_omitsBothOptionalFields() throws Exception {
            ExecutionReport r = ExecReportHelper.build(
                    new Execution(stopOrder(), 0, null, Execution.ExecType.NEW));
            assertFalse(r.isSetField(OrigClOrdID.FIELD));
            assertFalse(r.isSetField(Price.FIELD));
        }
    }

    @Nested
    @DisplayName("average price")
    class AveragePrice {

        @Test
        void fillWithPrice_reportsPrice() throws Exception {
            ExecutionReport r = ExecReportHelper.build(
                    new Execution(limitOrder(null), 4, new BigDecimal("10.25"), Execution.ExecType.FILL));
            assertEquals(10.25, r.getDouble(AvgPx.FIELD), 1e-9);
        }

        @Test
        void fillWithoutPrice_reportsZero() throws Exception {
            ExecutionReport r = ExecReportHelper.build(
                    new Execution(limitOrder(null), 4, null, Execution.ExecType.FILL));
            assertEquals(0.0, r.getDouble(AvgPx.FIELD), 1e-9);
        }

        @Test
        void zeroQtyWithPrice_reportsZero() throws Exception {
            ExecutionReport r = ExecReportHelper.build(
                    new Execution(limitOrder(null), 0, new BigDecimal("10.25"), Execution.ExecType.NEW));
            assertEquals(0.0, r.getDouble(AvgPx.FIELD), 1e-9);
        }
    }
}
