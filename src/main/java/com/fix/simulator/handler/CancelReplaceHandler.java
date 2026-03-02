package com.fix.simulator.handler;

import com.fix.simulator.engine.*;
import com.fix.simulator.util.ExecReportHelper;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;
import quickfix.field.CxlRejReason;
import quickfix.field.OrdType;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.fix42.OrderCancelReplaceRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Handles FIX 4.4 OrderCancelReplaceRequest (G) messages.
 *
 * <p>Supported modifications: order qty and limit price.
 * Symbol / side / type changes are rejected.
 */
public class CancelReplaceHandler {

    private static final Logger log = LoggerFactory.getLogger(CancelReplaceHandler.class);

    private final MatchingEngine     engine;
    private final InstrumentRegistry registry;
    private final ResponseSender     sender;

    public CancelReplaceHandler(MatchingEngine engine, InstrumentRegistry registry,
                                ResponseSender sender) {
        this.engine   = engine;
        this.registry = registry;
        this.sender   = sender;
    }

    public void handle(OrderCancelReplaceRequest msg, SessionID sessionId) {
        try {
            String clOrdId     = msg.getClOrdID().getValue();
            String origClOrdId = msg.getOrigClOrdID().getValue();
            String symbol      = msg.getSymbol().getValue();
            char   sideCh      = msg.getSide().getValue();
            char   typeCh      = msg.getOrdType().getValue();
            double qty         = msg.getOrderQty().getValue();

            log.info("[{}] CancelReplace clOrdId={} orig={} sym={} qty={}",
                    sessionId.getSenderCompID(), clOrdId, origClOrdId, symbol, qty);

            OrderSide  side = parseSide(sideCh);
            OrderType  type = parseOrdType(typeCh);
            TimeInForce tif = parseTif(msg);

            BigDecimal limitPx = null;
            BigDecimal stopPx  = null;

            if (type == OrderType.LIMIT || type == OrderType.STOP_LIMIT) {
                limitPx = msg.isSetPrice()
                        ? BigDecimal.valueOf(msg.getPrice().getValue()) : null;
            }
            if (type == OrderType.STOP || type == OrderType.STOP_LIMIT) {
                stopPx = msg.isSetStopPx()
                        ? BigDecimal.valueOf(msg.getStopPx().getValue()) : null;
            }

            // Build the replacement order (carries origClOrdId for lineage)
            Order replacement = new Order(sessionId.toString(), clOrdId, origClOrdId,
                    symbol, side, type, tif,
                    (long) qty, limitPx, stopPx);

            List<Execution> execs = engine.replaceOrder(
                    sessionId.toString(), origClOrdId, replacement);

            if (execs.isEmpty()) {
                sender.send(sessionId, ExecReportHelper.buildCancelReject(
                        clOrdId, origClOrdId,
                        "Original order not found or already completed",
                        CxlRejReason.UNKNOWN_ORDER));
            } else {
                for (Execution exec : execs) {
                    sender.send(sessionId, ExecReportHelper.build(exec));
                }
            }

        } catch (Exception e) {
            log.error("Error handling CancelReplaceRequest from {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private OrderSide parseSide(char c) {
        return c == Side.BUY ? OrderSide.BUY : OrderSide.SELL;
    }

    private OrderType parseOrdType(char c) {
        return switch (c) {
            case OrdType.LIMIT          -> OrderType.LIMIT;
            case OrdType.MARKET         -> OrderType.MARKET;
            case OrdType.STOP_STOP_LOSS -> OrderType.STOP;
            case OrdType.STOP_LIMIT     -> OrderType.STOP_LIMIT;
            default                     -> OrderType.LIMIT;
        };
    }

    private TimeInForce parseTif(OrderCancelReplaceRequest msg) throws quickfix.FieldNotFound {
        if (!msg.isSetTimeInForce()) return TimeInForce.DAY;
        return switch (msg.getTimeInForce().getValue()) {
            case quickfix.field.TimeInForce.GOOD_TILL_CANCEL    -> TimeInForce.GTC;
            case quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL -> TimeInForce.IOC;
            case quickfix.field.TimeInForce.FILL_OR_KILL        -> TimeInForce.FOK;
            default                                             -> TimeInForce.DAY;
        };
    }
}
