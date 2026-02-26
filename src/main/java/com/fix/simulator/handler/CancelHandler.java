package com.fix.simulator.handler;

import com.fix.simulator.engine.*;
import com.fix.simulator.util.ExecReportHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.OrderCancelRequest;

import java.util.List;

/**
 * Handles FIX 4.4 OrderCancelRequest (F) messages.
 */
public class CancelHandler {

    private static final Logger log = LoggerFactory.getLogger(CancelHandler.class);

    private final MatchingEngine engine;
    private final ResponseSender sender;

    public CancelHandler(MatchingEngine engine, ResponseSender sender) {
        this.engine = engine;
        this.sender = sender;
    }

    public void handle(OrderCancelRequest msg, SessionID sessionId) {
        try {
            String clOrdId     = msg.getClOrdID().getValue();
            String origClOrdId = msg.getOrigClOrdID().getValue();
            String symbol      = msg.getSymbol().getValue();

            log.info("[{}] CancelRequest clOrdId={} orig={}", sessionId.getSenderCompID(),
                    clOrdId, origClOrdId);

            List<Execution> execs = engine.cancelOrder(
                    sessionId.toString(), origClOrdId, clOrdId, symbol);

            if (execs.isEmpty()) {
                // Not found or already done → OrderCancelReject
                sender.send(sessionId, ExecReportHelper.buildCancelReject(
                        clOrdId, origClOrdId,
                        "Order not found or already completed",
                        CxlRejReason.UNKNOWN_ORDER));
            } else {
                for (Execution exec : execs) {
                    sender.send(sessionId, ExecReportHelper.build(exec));
                }
            }

        } catch (Exception e) {
            log.error("Error handling OrderCancelRequest from {}: {}", sessionId, e.getMessage(), e);
        }
    }
}
