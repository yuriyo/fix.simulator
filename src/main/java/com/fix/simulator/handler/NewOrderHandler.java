package com.fix.simulator.handler;

import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.admin.InstrumentStatus;
import com.fix.simulator.engine.*;
import com.fix.simulator.util.ExecReportHelper;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdType;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Handles FIX 4.4 NewOrderSingle (D) messages.
 */
public class NewOrderHandler {

    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);

    private final MatchingEngine      engine;
    private final InstrumentRegistry  registry;
    private final ResponseSender      sender;

    /** Optional – injected after construction; null means no state enforcement. */
    private InstrumentStateManager stateManager;

    public void setStateManager(InstrumentStateManager stateManager) {
        this.stateManager = stateManager;
    }

    public NewOrderHandler(MatchingEngine engine, InstrumentRegistry registry,
                           ResponseSender sender) {
        this.engine   = engine;
        this.registry = registry;
        this.sender   = sender;
    }

    public void handle(NewOrderSingle msg, SessionID sessionId) {
        try {
            // ── Parse mandatory fields ──────────────────────────────────────
            String  clOrdId = msg.getClOrdID().getValue();
            String  symbol  = msg.getSymbol().getValue();
            char    sideCh  = msg.getSide().getValue();
            char    typeCh  = msg.getOrdType().getValue();
            double  qty     = msg.getOrderQty().getValue();

            // ── Validate ────────────────────────────────────────────────────
            if (!registry.isKnown(symbol)) {
                sender.sendReject(sessionId, clOrdId, null,
                        "Unknown symbol: " + symbol, OrdRejReason.UNKNOWN_SYMBOL);
                return;
            }

            // Instrument state check (HALT or QUOTES_ONLY blocks new orders)
            if (stateManager != null) {
                InstrumentStatus instrStatus = stateManager.getState(symbol);
                if (instrStatus == InstrumentStatus.HALT) {
                    sender.sendReject(sessionId, clOrdId, null,
                            "Trading halted for " + symbol, OrdRejReason.EXCHANGE_CLOSED);
                    return;
                }
                if (instrStatus == InstrumentStatus.QUOTES_ONLY) {
                    sender.sendReject(sessionId, clOrdId, null,
                            symbol + " is in QUOTES_ONLY mode", OrdRejReason.EXCHANGE_CLOSED);
                    return;
                }
            }

            OrderSide   side = parseSide(sideCh);
            OrderType   type = parseType(typeCh);
            TimeInForce tif  = parseTif(msg);

            BigDecimal limitPx = null;
            BigDecimal stopPx  = null;

            if (type == OrderType.LIMIT || type == OrderType.STOP_LIMIT) {
                if (!msg.isSetPrice()) {
                    sender.sendReject(sessionId, clOrdId, null,
                            "Limit/StopLimit requires Price (44)", OrdRejReason.BROKER_EXCHANGE_OPTION);
                    return;
                }
                limitPx = BigDecimal.valueOf(msg.getPrice().getValue());
            }
            if (type == OrderType.STOP || type == OrderType.STOP_LIMIT) {
                if (!msg.isSetStopPx()) {
                    sender.sendReject(sessionId, clOrdId, null,
                            "Stop/StopLimit requires StopPx (99)", OrdRejReason.BROKER_EXCHANGE_OPTION);
                    return;
                }
                stopPx = BigDecimal.valueOf(msg.getStopPx().getValue());
            }

            // ── Build and submit ────────────────────────────────────────────
            Order order = new Order(sessionId.toString(), clOrdId, null,
                    symbol, side, type, tif,
                    (long) qty, limitPx, stopPx);

            log.info("[{}] NewOrder {}", sessionId.getSenderCompID(), order);

            List<Execution> execs = engine.newOrder(order);
            for (Execution exec : execs) {
                sender.send(sessionId, ExecReportHelper.build(exec));
            }

        } catch (Exception e) {
            log.error("Error handling NewOrderSingle from {}: {}", sessionId, e.getMessage(), e);
        }
    }

    // ── Parser helpers ──────────────────────────────────────────────────────

    private OrderSide parseSide(char c) {
        return switch (c) {
            case Side.BUY  -> OrderSide.BUY;
            case Side.SELL -> OrderSide.SELL;
            default -> throw new IllegalArgumentException("Unsupported side: " + c);
        };
    }

    private OrderType parseType(char c) {
        return switch (c) {
            case OrdType.LIMIT           -> OrderType.LIMIT;
            case OrdType.MARKET          -> OrderType.MARKET;
            case OrdType.STOP_STOP_LOSS  -> OrderType.STOP;
            case OrdType.STOP_LIMIT      -> OrderType.STOP_LIMIT;
            default -> throw new IllegalArgumentException("Unsupported OrdType: " + c);
        };
    }

    private TimeInForce parseTif(NewOrderSingle msg) throws quickfix.FieldNotFound {
        if (!msg.isSetTimeInForce()) return TimeInForce.DAY;
        return switch (msg.getTimeInForce().getValue()) {
            case quickfix.field.TimeInForce.GOOD_TILL_CANCEL    -> TimeInForce.GTC;
            case quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL -> TimeInForce.IOC;
            case quickfix.field.TimeInForce.FILL_OR_KILL        -> TimeInForce.FOK;
            default                                             -> TimeInForce.DAY;
        };
    }
}
