package com.fix.simulator.util;

import com.fix.simulator.engine.*;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import quickfix.FieldNotFound;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecReportHelper – FIX message construction")
class ExecReportHelperTest {

    private static Order limitBuyOrder(long qty) {
        Order o = new Order("ses:id", "CL1", null, "AAPL",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                qty, new BigDecimal("100.00"), null);
        return o;
    }

    private static Order limitSellOrder(long qty) {
        return new Order("ses:id", "CL2", null, "MSFT",
                OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                qty, new BigDecimal("50.00"), null);
    }

    private static Order stopLimitOrder() {
        return new Order("ses:id", "CL3", "CL2", "GOOG",
                OrderSide.BUY, OrderType.STOP_LIMIT, TimeInForce.GTC,
                100, new BigDecimal("200.00"), new BigDecimal("195.00"));
    }

    // ── ExecutionReport construction ─────────────────────────────────────────

    @Nested
    @DisplayName("ExecutionReport – build(Execution)")
    class BuildExecReport {

        @Test
        void newAck_requiredFieldsPresent() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);

            ExecutionReport rpt = ExecReportHelper.build(e);

            assertNotNull(rpt.getOrderID().getValue());
            assertNotNull(rpt.getExecID().getValue());
            assertEquals(ExecType.NEW,   rpt.getExecType().getValue());
            assertEquals(OrdStatus.NEW,  rpt.getOrdStatus().getValue());
            assertEquals("AAPL",         rpt.getSymbol().getValue());
            assertEquals(Side.BUY,       rpt.getSide().getValue());
            assertEquals(100.0,          rpt.getOrderQty().getValue(), 1e-9);
            assertEquals(100.0,          rpt.getLeavesQty().getValue(), 1e-9);
            assertEquals(0.0,            rpt.getCumQty().getValue(), 1e-9);
            assertEquals("CL1",          rpt.getClOrdID().getValue());
        }

        @Test
        void fillExec_setsLastQtyAndLastPx() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            o.fill(100);
            Execution e = new Execution(o, 100, new BigDecimal("99.50"), Execution.ExecType.FILL);

            ExecutionReport rpt = ExecReportHelper.build(e);

            assertEquals(ExecType.FILL,            rpt.getExecType().getValue());
            assertEquals(OrdStatus.FILLED,         rpt.getOrdStatus().getValue());
            assertEquals(100.0,                    rpt.getLastQty().getValue(), 1e-9);
            assertEquals(99.50,                    rpt.getLastPx().getValue(), 1e-9);
            assertEquals(100.0,                    rpt.getCumQty().getValue(), 1e-9);
            assertEquals(0.0,                      rpt.getLeavesQty().getValue(), 1e-9);
        }

        @Test
        void partialFillExec_correctQtys() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            o.fill(40);
            Execution e = new Execution(o, 40, new BigDecimal("99.00"), Execution.ExecType.PARTIAL_FILL);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertEquals(ExecType.PARTIAL_FILL, rpt.getExecType().getValue());
            assertEquals(OrdStatus.PARTIALLY_FILLED, rpt.getOrdStatus().getValue());
            assertEquals(60.0, rpt.getLeavesQty().getValue(), 1e-9);
            assertEquals(40.0, rpt.getCumQty().getValue(), 1e-9);
        }

        @Test
        void cancelledExec_noLastQtyLastPx() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            o.cancel();
            Execution e = new Execution(o, 0, null, Execution.ExecType.CANCELLED);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertEquals(ExecType.CANCELED,  rpt.getExecType().getValue());
            assertEquals(OrdStatus.CANCELED, rpt.getOrdStatus().getValue());
            assertFalse(rpt.isSetLastQty());
        }

        @Test
        void replacedExec_execTypeReplaced() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            o.cancel();
            Execution e = new Execution(o, 0, null, Execution.ExecType.REPLACED);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertEquals(ExecType.REPLACED, rpt.getExecType().getValue());
        }

        @Test
        void rejectedExec_statusRejected() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            o.setStatus(OrderStatus.REJECTED);
            Execution e = new Execution(o, 0, null, Execution.ExecType.REJECTED);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertEquals(ExecType.REJECTED,  rpt.getExecType().getValue());
            assertEquals(OrdStatus.REJECTED, rpt.getOrdStatus().getValue());
        }

        @Test
        void limitOrder_priceFieldSet() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertTrue(rpt.isSetPrice());
            assertEquals(100.0, rpt.getPrice().getValue(), 1e-9);
        }

        @Test
        void stopLimitOrder_priceAndStopPxSet() throws FieldNotFound {
            Order o = stopLimitOrder();
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertTrue(rpt.isSetPrice());
            assertTrue(rpt.isSetStopPx());
            assertEquals(200.0, rpt.getPrice().getValue(), 1e-9);
            assertEquals(195.0, rpt.getStopPx().getValue(), 1e-9);
        }

        @Test
        void origClOrdId_setWhenPresent() throws FieldNotFound {
            Order o = new Order("ses", "CL2NEW", "CL1", "AAPL",
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    100, new BigDecimal("100.00"), null);
            Execution e = new Execution(o, 0, null, Execution.ExecType.REPLACED);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertTrue(rpt.isSetOrigClOrdID());
            assertEquals("CL1", rpt.getOrigClOrdID().getValue());
        }

        @Test
        void noOrigClOrdId_notSet() throws FieldNotFound {
            Order o = limitBuyOrder(100);
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);

            ExecutionReport rpt = ExecReportHelper.build(e);
            assertFalse(rpt.isSetOrigClOrdID());
        }

        @Test
        void transactTimeAlwaysSet() {
            Order o = limitBuyOrder(100);
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);
            assertDoesNotThrow(() -> ExecReportHelper.build(e).getTransactTime());
        }

        @Test
        void sellSide_sideFieldIsSell() throws FieldNotFound {
            Order o = limitSellOrder(50);
            Execution e = new Execution(o, 0, null, Execution.ExecType.NEW);
            assertEquals(Side.SELL, ExecReportHelper.build(e).getSide().getValue());
        }
    }

    // ── OrderCancelReject construction ───────────────────────────────────────

    @Nested
    @DisplayName("buildCancelReject")
    class BuildCancelReject {

        @Test
        void basicCancelReject_mandatoryFields() throws FieldNotFound {
            OrderCancelReject msg = ExecReportHelper.buildCancelReject(
                    "CL2", "CL1", "Order not found", CxlRejReason.UNKNOWN_ORDER);

            assertEquals("NONE",       msg.getOrderID().getValue());
            assertEquals("CL2",        msg.getClOrdID().getValue());
            assertEquals("CL1",        msg.getOrigClOrdID().getValue());
            assertEquals(OrdStatus.REJECTED,  msg.getOrdStatus().getValue());
            assertEquals(CxlRejResponseTo.ORDER_CANCEL_REQUEST,
                    msg.getCxlRejResponseTo().getValue());
        }

        @Test
        void zeroReasonCode_usesTooLateToCancelFallback() throws FieldNotFound {
            OrderCancelReject msg = ExecReportHelper.buildCancelReject(
                    "CL2", "CL1", "Too late", 0);
            assertEquals(CxlRejReason.TOO_LATE_TO_CANCEL, msg.getCxlRejReason().getValue());
        }

        @Test
        void unknownOrderReasonCode() throws FieldNotFound {
            OrderCancelReject msg = ExecReportHelper.buildCancelReject(
                    "CL2", "CL1", "Not found", CxlRejReason.UNKNOWN_ORDER);
            assertEquals(CxlRejReason.UNKNOWN_ORDER, msg.getCxlRejReason().getValue());
        }

        @Test
        void textFieldSet() throws FieldNotFound {
            OrderCancelReject msg = ExecReportHelper.buildCancelReject(
                    "CL2", null, "Some reason", CxlRejReason.UNKNOWN_ORDER);
            assertEquals("Some reason", msg.getText().getValue());
        }
    }

    // ── Converter methods ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("toFIXExecType – all ExecType values")
    class ToFIXExecType {

        @Test void newMapsToFIXNew()           { assertEquals(ExecType.NEW,           ExecReportHelper.toFIXExecType(Execution.ExecType.NEW)); }
        @Test void partialFillMaps()           { assertEquals(ExecType.PARTIAL_FILL,  ExecReportHelper.toFIXExecType(Execution.ExecType.PARTIAL_FILL)); }
        @Test void fillMaps()                  { assertEquals(ExecType.FILL,           ExecReportHelper.toFIXExecType(Execution.ExecType.FILL)); }
        @Test void cancelledMapsToFIXCanceled(){ assertEquals(ExecType.CANCELED,       ExecReportHelper.toFIXExecType(Execution.ExecType.CANCELLED)); }
        @Test void replacedMaps()              { assertEquals(ExecType.REPLACED,       ExecReportHelper.toFIXExecType(Execution.ExecType.REPLACED)); }
        @Test void rejectedMaps()              { assertEquals(ExecType.REJECTED,       ExecReportHelper.toFIXExecType(Execution.ExecType.REJECTED)); }
        @Test void pendingCancelMaps()         { assertEquals(ExecType.PENDING_CANCEL, ExecReportHelper.toFIXExecType(Execution.ExecType.PENDING_CANCEL)); }
        @Test void pendingReplaceMaps()        { assertEquals(ExecType.PENDING_REPLACE,ExecReportHelper.toFIXExecType(Execution.ExecType.PENDING_REPLACE)); }
    }

    @Nested
    @DisplayName("toFIXOrdStatus – all OrderStatus values")
    class ToFIXOrdStatus {

        @Test void newMaps()             { assertEquals(OrdStatus.NEW,              ExecReportHelper.toFIXOrdStatus(OrderStatus.NEW)); }
        @Test void partiallyFilledMaps() { assertEquals(OrdStatus.PARTIALLY_FILLED, ExecReportHelper.toFIXOrdStatus(OrderStatus.PARTIALLY_FILLED)); }
        @Test void filledMaps()          { assertEquals(OrdStatus.FILLED,           ExecReportHelper.toFIXOrdStatus(OrderStatus.FILLED)); }
        @Test void cancelledMaps()       { assertEquals(OrdStatus.CANCELED,         ExecReportHelper.toFIXOrdStatus(OrderStatus.CANCELLED)); }
        @Test void pendingCancelMaps()   { assertEquals(OrdStatus.PENDING_CANCEL,   ExecReportHelper.toFIXOrdStatus(OrderStatus.PENDING_CANCEL)); }
        @Test void rejectedMaps()        { assertEquals(OrdStatus.REJECTED,         ExecReportHelper.toFIXOrdStatus(OrderStatus.REJECTED)); }
        @Test void pendingReplaceMaps()  { assertEquals(OrdStatus.PENDING_REPLACE,  ExecReportHelper.toFIXOrdStatus(OrderStatus.PENDING_REPLACE)); }
    }

    @Nested
    @DisplayName("toFIXSide")
    class ToFIXSide {

        @Test void buyMaps()  { assertEquals(Side.BUY,  ExecReportHelper.toFIXSide(OrderSide.BUY)); }
        @Test void sellMaps() { assertEquals(Side.SELL, ExecReportHelper.toFIXSide(OrderSide.SELL)); }
    }

    @Nested
    @DisplayName("toFIXOrdType – all OrderType values")
    class ToFIXOrdType {

        @Test void limitMaps()     { assertEquals(OrdType.LIMIT,      ExecReportHelper.toFIXOrdType(OrderType.LIMIT)); }
        @Test void marketMaps()    { assertEquals(OrdType.MARKET,     ExecReportHelper.toFIXOrdType(OrderType.MARKET)); }
        @Test void stopMaps()      { assertEquals(OrdType.STOP_STOP_LOSS, ExecReportHelper.toFIXOrdType(OrderType.STOP)); }
        @Test void stopLimitMaps() { assertEquals(OrdType.STOP_LIMIT, ExecReportHelper.toFIXOrdType(OrderType.STOP_LIMIT)); }
    }

    @Nested
    @DisplayName("toFIXTif – all TimeInForce values")
    class ToFIXTif {

        @Test void dayMaps() {
            assertEquals(quickfix.field.TimeInForce.DAY,
                    ExecReportHelper.toFIXTif(com.fix.simulator.engine.TimeInForce.DAY));
        }

        @Test void gtcMaps() {
            assertEquals(quickfix.field.TimeInForce.GOOD_TILL_CANCEL,
                    ExecReportHelper.toFIXTif(com.fix.simulator.engine.TimeInForce.GTC));
        }

        @Test void iocMaps() {
            assertEquals(quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL,
                    ExecReportHelper.toFIXTif(com.fix.simulator.engine.TimeInForce.IOC));
        }

        @Test void fokMaps() {
            assertEquals(quickfix.field.TimeInForce.FILL_OR_KILL,
                    ExecReportHelper.toFIXTif(com.fix.simulator.engine.TimeInForce.FOK));
        }
    }
}
