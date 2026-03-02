package com.fix.simulator.handler;

import quickfix.Message;
import quickfix.SessionID;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.OrderCancelReject;
import quickfix.field.OrdRejReason;

/**
 * Abstraction over QuickFIX/J {@code Session.sendToTarget()} so handlers
 * remain testable without a live session.
 */
public interface ResponseSender {

    void send(SessionID sessionId, Message message);

    /**
     * Send an ExecutionReport with OrdStatus=Rejected.
     */
    default void sendReject(SessionID sessionId, String clOrdId, String origClOrdId,
                            String text, int rejReasonCode) {
        ExecutionReport rpt = new ExecutionReport();
        try {
            rpt.set(new quickfix.field.OrderID("NONE"));
            rpt.set(new quickfix.field.ExecID("REJ-" + System.nanoTime()));
            rpt.set(new quickfix.field.ExecType(quickfix.field.ExecType.REJECTED));
            rpt.set(new quickfix.field.OrdStatus(quickfix.field.OrdStatus.REJECTED));
            rpt.set(new quickfix.field.ClOrdID(clOrdId));
            if (origClOrdId != null)
                rpt.set(new quickfix.field.OrigClOrdID(origClOrdId));
            rpt.set(new quickfix.field.Symbol("N/A"));
            rpt.set(new quickfix.field.Side(quickfix.field.Side.UNDISCLOSED));
            rpt.set(new quickfix.field.LeavesQty(0));
            rpt.set(new quickfix.field.CumQty(0));
            rpt.set(new quickfix.field.AvgPx(0));
            rpt.set(new quickfix.field.OrdRejReason(rejReasonCode));
            rpt.set(new quickfix.field.Text(text));
            rpt.set(new quickfix.field.TransactTime());
        } catch (Exception ignored) {}
        send(sessionId, rpt);
    }
}
