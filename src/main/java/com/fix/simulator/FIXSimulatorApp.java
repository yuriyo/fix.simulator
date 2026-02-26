package com.fix.simulator;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.handler.*;
import com.fix.simulator.util.ExecReportHelper;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.MsgType;
import quickfix.fix44.MassQuote;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.Quote;

/**
 * QuickFIX/J {@link Application} implementation.
 *
 * <p>All session callbacks are handled here. Incoming application messages
 * are dispatched to the appropriate {@link com.fix.simulator.handler} class.
 *
 * <p><b>Session restore / sequence numbers:</b> QuickFIX/J persists compIDs
 * and sequence numbers to the store configured in {@code server.cfg}
 * (default: {@code FileStore} in {@code ./store/}).  On reconnect the
 * counterparty initiates resend and QFJ replays stored messages automatically.
 * Set {@code ResetOnLogon=N} (default in config) to keep sequence state across
 * connections.
 */
public class FIXSimulatorApp implements Application {

    private static final Logger log = LoggerFactory.getLogger(FIXSimulatorApp.class);

    // ── Dependencies ────────────────────────────────────────────────────────
    private final MatchingEngine     engine;
    private final InstrumentRegistry registry;

    // ── Handlers ────────────────────────────────────────────────────────────
    private final NewOrderHandler        newOrderHandler;
    private final CancelHandler          cancelHandler;
    private final CancelReplaceHandler   cancelReplaceHandler;
    private final QuoteHandler           quoteHandler;

    public FIXSimulatorApp(MatchingEngine engine, InstrumentRegistry registry) {
        this.engine   = engine;
        this.registry = registry;

        // Sender that delegates to quickfix.Session.sendToTarget
        ResponseSender sender = (sessionId, message) -> {
            try {
                Session.sendToTarget(message, sessionId);
            } catch (SessionNotFound e) {
                log.warn("Session not found when sending to {}: {}", sessionId, e.getMessage());
            }
        };

        this.newOrderHandler      = new NewOrderHandler(engine, registry, sender);
        this.cancelHandler        = new CancelHandler(engine, sender);
        this.cancelReplaceHandler = new CancelReplaceHandler(engine, registry, sender);
        this.quoteHandler         = new QuoteHandler(engine, registry, sender);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  QuickFIX/J Application lifecycle
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Logon:  {} (compId={})", sessionId, sessionId.getSenderCompID());
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // No-op: admin messages (heartbeat, logon, etc.) are handled by QFJ
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        // No-op: admin messages handled by QFJ
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        // Optional: log outbound
        if (log.isTraceEnabled())
            log.trace("OUT [{}]: {}", sessionId.getSenderCompID(), message);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Inbound application messages
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

        if (log.isDebugEnabled())
            log.debug("IN  [{}]: {}", sessionId.getSenderCompID(), message.getHeader().getString(MsgType.FIELD));

        crack(message, sessionId);
    }

    /**
     * Dispatch via QuickFIX/J message cracker.
     */
    private void crack(Message message, SessionID sessionId) throws UnsupportedMessageType, FieldNotFound {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            switch (msgType) {
                case MsgType.ORDER_SINGLE              -> newOrderHandler.handle((NewOrderSingle)             message, sessionId);
                case MsgType.ORDER_CANCEL_REQUEST      -> cancelHandler.handle((OrderCancelRequest)           message, sessionId);
                case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> cancelReplaceHandler.handle((OrderCancelReplaceRequest) message, sessionId);
                case MsgType.QUOTE                     -> quoteHandler.handleQuote((Quote)                    message, sessionId);
                case MsgType.MASS_QUOTE               -> quoteHandler.handleMassQuote((MassQuote)             message, sessionId);
                default -> {
                    log.warn("Unhandled message type: {}", msgType);
                    throw new UnsupportedMessageType();
                }
            }
        } catch (UnsupportedMessageType | FieldNotFound e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error dispatching message: {}", e.getMessage(), e);
        }
    }
}
