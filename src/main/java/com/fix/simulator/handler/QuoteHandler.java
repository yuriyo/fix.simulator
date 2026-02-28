package com.fix.simulator.handler;

import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.admin.InstrumentStatus;
import com.fix.simulator.engine.Execution;
import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.util.ExecReportHelper;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.field.QuoteStatus;
import quickfix.fix44.MassQuote;
import quickfix.fix44.MassQuoteAcknowledgement;
import quickfix.fix44.Quote;
import quickfix.fix44.QuoteStatusReport;

import java.math.BigDecimal;
import java.util.List;

/**
 * Handles FIX 4.4 Quote (S) and MassQuote (i) messages.
 *
 * <p>Each leg is inserted as a resting limit order on both sides, updating
 * the market data snapshot. Any immediate fills are sent back as
 * ExecutionReports to the quoting session.
 */
public class QuoteHandler {

    private static final Logger log = LoggerFactory.getLogger(QuoteHandler.class);

    private final MatchingEngine     engine;
    private final InstrumentRegistry registry;
    private final ResponseSender     sender;

    /** Optional – injected after construction; null means no state enforcement. */
    private InstrumentStateManager stateManager;

    public void setStateManager(InstrumentStateManager stateManager) {
        this.stateManager = stateManager;
    }

    public QuoteHandler(MatchingEngine engine, InstrumentRegistry registry,
                        ResponseSender sender) {
        this.engine   = engine;
        this.registry = registry;
        this.sender   = sender;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  FIX 4.4 Quote (35=S)
    // ──────────────────────────────────────────────────────────────────────

    public void handleQuote(Quote msg, SessionID sessionId) {
        try {
            String quoteId = msg.getQuoteID().getValue();
            String symbol  = msg.isSetSymbol() ? msg.getSymbol().getValue() : null;

            if (symbol == null || !registry.isKnown(symbol)) {
                log.warn("[{}] Quote rejected – unknown symbol {}", sessionId, symbol);
                sendQuoteAck(sessionId, quoteId, QuoteStatus.REJECTED);
                return;
            }

            // HALT blocks quotes
            if (stateManager != null && stateManager.getState(symbol) == InstrumentStatus.HALT) {
                log.warn("[{}] Quote rejected – {} is HALTed", sessionId, symbol);
                sendQuoteAck(sessionId, quoteId, QuoteStatus.REJECTED);
                return;
            }

            BigDecimal bidPx = msg.isSetBidPx()  ? BigDecimal.valueOf(msg.getBidPx().getValue())  : null;
            BigDecimal askPx = msg.isSetOfferPx() ? BigDecimal.valueOf(msg.getOfferPx().getValue()) : null;
            long bidSz = msg.isSetBidSize()  ? (long) msg.getBidSize().getValue()  : 0L;
            long askSz = msg.isSetOfferSize() ? (long) msg.getOfferSize().getValue() : 0L;

            log.info("[{}] Quote id={} sym={} bid={}/{} ask={}/{}",
                    sessionId.getSenderCompID(), quoteId, symbol, bidPx, bidSz, askPx, askSz);

            List<Execution> fills = engine.onQuote(
                    sessionId.toString(), symbol, bidPx, bidSz, askPx, askSz, quoteId);

            sendQuoteAck(sessionId, quoteId, QuoteStatus.ACCEPTED);
            for (Execution exec : fills) {
                sender.send(sessionId, ExecReportHelper.build(exec));
            }

        } catch (Exception e) {
            log.error("Error handling Quote from {}: {}", sessionId, e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  FIX 4.4 MassQuote (35=i)
    // ──────────────────────────────────────────────────────────────────────

    public void handleMassQuote(MassQuote msg, SessionID sessionId) {
        try {
            String massQuoteId = msg.isSetQuoteID() ? msg.getQuoteID().getValue() : "";
            int numSets = msg.getNoQuoteSets().getValue();

            int accepted = 0;
            int rejected = 0;

            for (int s = 1; s <= numSets; s++) {
                MassQuote.NoQuoteSets setGrp = new MassQuote.NoQuoteSets();
                msg.getGroup(s, setGrp);
                String quoteSetId = setGrp.isSetQuoteSetID() ? setGrp.getQuoteSetID().getValue() : "";
                int numEntries = setGrp.getNoQuoteEntries().getValue();

                for (int e = 1; e <= numEntries; e++) {
                    MassQuote.NoQuoteSets.NoQuoteEntries entGrp = new MassQuote.NoQuoteSets.NoQuoteEntries();
                    setGrp.getGroup(e, entGrp);

                    String symbol = entGrp.isSetSymbol() ? entGrp.getSymbol().getValue() : null;
                    if (symbol == null || !registry.isKnown(symbol)) {
                        log.warn("[{}] MassQuote entry rejected – unknown symbol {}", sessionId, symbol);
                        rejected++;
                        continue;
                    }
                    // HALT check
                    if (stateManager != null && stateManager.getState(symbol) == InstrumentStatus.HALT) {
                        log.warn("[{}] MassQuote entry rejected – {} is HALTed", sessionId, symbol);
                        rejected++;
                        continue;
                    }

                    BigDecimal bidPx = entGrp.isSetBidPx()   ? BigDecimal.valueOf(entGrp.getBidPx().getValue())   : null;
                    BigDecimal askPx = entGrp.isSetOfferPx() ? BigDecimal.valueOf(entGrp.getOfferPx().getValue()) : null;
                    long bidSz = entGrp.isSetBidSize()   ? (long) entGrp.getBidSize().getValue()   : 0L;
                    long askSz = entGrp.isSetOfferSize() ? (long) entGrp.getOfferSize().getValue() : 0L;

                    String quoteEntryId = entGrp.isSetQuoteEntryID()
                            ? entGrp.getQuoteEntryID().getValue() : quoteSetId + "-" + e;

                    List<Execution> fills = engine.onQuote(
                            sessionId.toString(), symbol, bidPx, bidSz, askPx, askSz, quoteEntryId);
                    for (Execution exec : fills) {
                        sender.send(sessionId, ExecReportHelper.build(exec));
                    }
                    accepted++;
                }
            }

            sendMassQuoteAck(sessionId, massQuoteId,
                    rejected == 0 ? QuoteStatus.ACCEPTED : QuoteStatus.REJECTED);

        } catch (Exception e) {
            log.error("Error handling MassQuote from {}: {}", sessionId, e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Ack helpers
    // ──────────────────────────────────────────────────────────────────────

    private void sendQuoteAck(SessionID sessionId, String quoteId, int status) {
        try {
            QuoteStatusReport ack = new QuoteStatusReport();
            ack.set(new QuoteStatus(status));
            ack.set(new QuoteID(quoteId));
            sender.send(sessionId, ack);
        } catch (Exception e) {
            log.warn("Failed to send QuoteAck: {}", e.getMessage());
        }
    }

    private void sendMassQuoteAck(SessionID sessionId, String massQuoteId, int status) {
        try {
            MassQuoteAcknowledgement ack = new MassQuoteAcknowledgement(new QuoteStatus(status));
            ack.set(new QuoteID(massQuoteId));
            sender.send(sessionId, ack);
        } catch (Exception e) {
            log.warn("Failed to send MassQuoteAck: {}", e.getMessage());
        }
    }
}
