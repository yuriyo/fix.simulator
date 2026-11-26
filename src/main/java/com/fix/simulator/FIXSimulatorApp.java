package com.fix.simulator;

import com.fix.simulator.admin.ClientRegistry;
import com.fix.simulator.admin.EventBus;
import com.fix.simulator.admin.FIXMessageLog;
import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.handler.*;
import com.fix.simulator.util.ExecReportHelper;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.MsgType;
import quickfix.fix42.MarketDataRequest;
import quickfix.fix42.MassQuote;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelReplaceRequest;
import quickfix.fix42.OrderCancelRequest;
import quickfix.fix42.Quote;

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
    private final MarketDataHandler      marketDataHandler;

    // ── Admin (optional – injected via setters; null until wired) ────────────
    private ClientRegistry         clientRegistry;
    private EventBus               eventBus;    private FIXMessageLog          messageLog;
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wire in the admin subsystems.
     * Called from {@code Main} after construction so that existing test code
     * that uses the two-arg constructor remains unmodified.
     */
    public void setClientRegistry(ClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void setMessageLog(FIXMessageLog messageLog) {
        this.messageLog = messageLog;
    }

    /** Delegates to the handlers' own setters. */
    public void setInstrumentStateManager(InstrumentStateManager stateManager) {
        newOrderHandler.setStateManager(stateManager);
        quoteHandler.setStateManager(stateManager);
    }

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
        this.marketDataHandler    = new MarketDataHandler(engine, sender);
    }

    /**
     * Start the timer that sends incremental top-of-book updates to market data subscribers.
     * Called once from {@code Main}; tests call {@link MarketDataHandler#publishChanges()} directly.
     */
    public void startMarketDataPublisher() {
        java.util.concurrent.ScheduledExecutorService timer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "market-data-publisher");
                    t.setDaemon(true);
                    return t;
                });
        timer.scheduleAtFixedRate(() -> {
            try {
                marketDataHandler.publishChanges();
            } catch (RuntimeException e) {
                log.warn("Market data publish failed: {}", e.getMessage());
            }
        }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        if (clientRegistry != null) clientRegistry.onConnect(sessionId);
        if (eventBus != null)
            eventBus.publish(java.util.Map.of(
                    "type", "session_connect",
                    "compId", sessionId.getSenderCompID(),
                    "sessionId", sessionId.toString()));
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
        marketDataHandler.onSessionLogout(sessionId);
        if (clientRegistry != null) clientRegistry.onDisconnect(sessionId);
        if (eventBus != null)
            eventBus.publish(java.util.Map.of(
                    "type", "session_disconnect",
                    "compId", sessionId.getSenderCompID(),
                    "sessionId", sessionId.toString()));
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // No-op: admin messages (heartbeat, logon, etc.) are handled by QFJ
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        // Block denied clients at Logon time
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if (quickfix.field.MsgType.LOGON.equals(msgType) && clientRegistry != null) {
                String compId = sessionId.getSenderCompID();
                if (!clientRegistry.isAllowed(compId)) {
                    log.warn("Rejecting logon from blocked/unlisted compId: {}", compId);
                    throw new RejectLogon("Access denied for compId: " + compId);
                }
            }
        } catch (RejectLogon rl) {
            throw rl;
        } catch (Exception ignored) {
            // Non-logon admin messages: pass through
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        if (log.isTraceEnabled())
            log.trace("OUT [{}]: {}", sessionId.getSenderCompID(), message);
        if (messageLog != null)
            messageLog.record(sessionId.toString(), sessionId.getTargetCompID(),
                              FIXMessageLog.Direction.OUT, message);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Inbound application messages
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

        if (log.isDebugEnabled())
            log.debug("IN  [{}]: {}", sessionId.getSenderCompID(), message.getHeader().getString(MsgType.FIELD));

        if (messageLog != null)
            messageLog.record(sessionId.toString(), sessionId.getTargetCompID(),
                              FIXMessageLog.Direction.IN, message);

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
                case MsgType.MARKET_DATA_REQUEST      -> marketDataHandler.handle((MarketDataRequest)        message, sessionId);
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
