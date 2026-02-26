package com.fix.simulator.util;

import com.fix.simulator.engine.*;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

import java.math.BigDecimal;

/**
 * Converts internal {@link Execution} records to QuickFIX/J FIX 4.4 messages.
 */
public class ExecReportHelper {

    private ExecReportHelper() {}

    // ──────────────────────────────────────────────────────────────────────
    //  ExecutionReport (MsgType=8)
    // ──────────────────────────────────────────────────────────────────────

    public static ExecutionReport build(Execution exec) {
        Order   order   = exec.getOrder();
        String  clOrdId = order.getClOrdId();

        ExecutionReport rpt = new ExecutionReport();

        // Required fields
        rpt.set(new OrderID(String.valueOf(order.getInternalId())));
        rpt.set(new ExecID(exec.getExecId()));
        rpt.set(new ExecType(toFIXExecType(exec.getExecType())));
        rpt.set(new OrdStatus(toFIXOrdStatus(order.getStatus())));
        rpt.set(new Symbol(order.getSymbol()));
        rpt.set(new Side(toFIXSide(order.getSide())));
        rpt.set(new LeavesQty(order.getOpenQty()));
        rpt.set(new CumQty(order.getFilledQty()));
        rpt.set(new AvgPx(avgPx(exec, order)));

        // Optional but useful fields
        rpt.set(new ClOrdID(clOrdId));
        if (order.getOrigClOrdId() != null)
            rpt.set(new OrigClOrdID(order.getOrigClOrdId()));
        rpt.set(new OrdType(toFIXOrdType(order.getType())));
        rpt.set(new quickfix.field.TimeInForce(toFIXTif(order.getTif())));

        if (order.getLimitPrice() != null)
            rpt.set(new Price(order.getLimitPrice().doubleValue()));
        if (order.getStopPrice() != null)
            rpt.set(new StopPx(order.getStopPrice().doubleValue()));

        // Fill-specific fields
        if (exec.getQty() > 0) {
            rpt.set(new LastQty(exec.getQty()));
            rpt.set(new LastPx(exec.getPrice() != null ? exec.getPrice().doubleValue() : 0));
        }

        rpt.set(new TransactTime());
        rpt.set(new OrderQty(order.getTotalQty()));

        return rpt;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  OrderCancelReject (MsgType=9)
    // ──────────────────────────────────────────────────────────────────────

    public static OrderCancelReject buildCancelReject(String clOrdId,
                                                       String origClOrdId,
                                                       String reason,
                                                       int cxlRejReasonCode) {
        OrderCancelReject msg = new OrderCancelReject();
        msg.set(new OrderID("NONE"));
        msg.set(new ClOrdID(clOrdId));
        if (origClOrdId != null) msg.set(new OrigClOrdID(origClOrdId));
        msg.set(new OrdStatus(OrdStatus.REJECTED));
        msg.set(new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));
        msg.set(new CxlRejReason(cxlRejReasonCode == 0 ? CxlRejReason.TOO_LATE_TO_CANCEL
                                                       : cxlRejReasonCode));
        msg.set(new Text(reason));
        msg.set(new TransactTime());
        return msg;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  FIX tag value converters
    // ──────────────────────────────────────────────────────────────────────

    public static char toFIXExecType(Execution.ExecType t) {
        return switch (t) {
            case NEW            -> ExecType.NEW;
            case PARTIAL_FILL   -> ExecType.PARTIAL_FILL;
            case FILL           -> ExecType.FILL;
            case CANCELLED      -> ExecType.CANCELED;
            case REPLACED       -> ExecType.REPLACED;
            case REJECTED       -> ExecType.REJECTED;
            case PENDING_CANCEL -> ExecType.PENDING_CANCEL;
            case PENDING_REPLACE-> ExecType.PENDING_REPLACE;
        };
    }

    public static char toFIXOrdStatus(OrderStatus s) {
        return switch (s) {
            case NEW              -> OrdStatus.NEW;
            case PARTIALLY_FILLED -> OrdStatus.PARTIALLY_FILLED;
            case FILLED           -> OrdStatus.FILLED;
            case CANCELLED        -> OrdStatus.CANCELED;
            case PENDING_CANCEL   -> OrdStatus.PENDING_CANCEL;
            case REJECTED         -> OrdStatus.REJECTED;
            case PENDING_REPLACE  -> OrdStatus.PENDING_REPLACE;
        };
    }

    public static char toFIXSide(OrderSide side) {
        return side == OrderSide.BUY ? Side.BUY : Side.SELL;
    }

    public static char toFIXOrdType(OrderType t) {
        return switch (t) {
            case LIMIT      -> OrdType.LIMIT;
            case MARKET     -> OrdType.MARKET;
            case STOP       -> OrdType.STOP_STOP_LOSS;
            case STOP_LIMIT -> OrdType.STOP_LIMIT;
        };
    }

    public static char toFIXTif(com.fix.simulator.engine.TimeInForce tif) {
        return switch (tif) {
            case DAY -> quickfix.field.TimeInForce.DAY;
            case GTC -> quickfix.field.TimeInForce.GOOD_TILL_CANCEL;
            case IOC -> quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL;
            case FOK -> quickfix.field.TimeInForce.FILL_OR_KILL;
        };
    }

    private static double avgPx(Execution exec, Order order) {
        if (exec.getPrice() != null && exec.getQty() > 0) return exec.getPrice().doubleValue();
        return 0.0;
    }
}
