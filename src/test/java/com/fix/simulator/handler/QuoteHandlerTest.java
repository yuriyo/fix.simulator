package com.fix.simulator.handler;

import com.fix.simulator.engine.*;
import com.fix.simulator.engine.TimeInForce;
import com.fix.simulator.util.InstrumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.MassQuote;
import quickfix.fix42.Quote;
import quickfix.fix42.QuoteAcknowledgement;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteHandler – Quote (S) and MassQuote (i) handling")
class QuoteHandlerTest {

    @Mock MatchingEngine     engine;
    @Mock InstrumentRegistry registry;
    @Mock ResponseSender     sender;

    private QuoteHandler handler;
    private SessionID    sessionId;

    @BeforeEach
    void setUp() {
        handler   = new QuoteHandler(engine, registry, sender);
        sessionId = new SessionID("FIX.4.2", "MM", "EXCHANGE");
    }

    // ── Quote (35=S) helpers ──────────────────────────────────────────────────

    private Quote buildQuote(String quoteId, String symbol,
                             Double bidPx, Double bidSz,
                             Double askPx, Double askSz) throws Exception {
        Quote q = new Quote();
        q.set(new QuoteID(quoteId));
        q.set(new Symbol(symbol));
        if (bidPx  != null) q.set(new BidPx(bidPx));
        if (bidSz  != null) q.set(new BidSize(bidSz));
        if (askPx  != null) q.set(new OfferPx(askPx));
        if (askSz  != null) q.set(new OfferSize(askSz));
        return q;
    }

    // ── Quote tests ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Single Quote (35=S)")
    class SingleQuote {

        @Test
        void knownSymbol_accepted_ackSent() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handleQuote(buildQuote("Q1", "AAPL", 99.0, 100.0, 101.0, 100.0), sessionId);

            verify(sender).send(eq(sessionId), any(QuoteAcknowledgement.class));
            // verify no exec reports sent (no fills)
            verify(sender, never()).send(eq(sessionId), any(ExecutionReport.class));
        }

        @Test
        void unknownSymbol_rejectedAckSent_engineNotCalled() throws Exception {
            when(registry.isKnown("ZZZZ")).thenReturn(false);

            handler.handleQuote(buildQuote("Q1", "ZZZZ", 99.0, 100.0, 101.0, 100.0), sessionId);

            verify(sender).send(eq(sessionId), any(QuoteAcknowledgement.class));
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void quoteWithFills_execReportsSentPerFill() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);

            Order o = new Order("sess", "C1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 50, new BigDecimal("100"), null);
            o.fill(50);
            Execution fillExec = new Execution(o, 50, new BigDecimal("99"), Execution.ExecType.FILL);

            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(List.of(fillExec));

            handler.handleQuote(buildQuote("Q1", "AAPL", 99.0, 50.0, null, 0.0), sessionId);

            // quoteAck + 1 execReport
            verify(sender).send(eq(sessionId), any(QuoteAcknowledgement.class));
            verify(sender).send(eq(sessionId), any(ExecutionReport.class));
        }

        @Test
        void engineCalledWithParsedBidAsk() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handleQuote(buildQuote("Q-TEST", "AAPL", 98.5, 200.0, 100.5, 300.0), sessionId);

            verify(engine).onQuote(
                    anyString(), eq("AAPL"),
                    eq(new BigDecimal(98.5)), eq(200L),
                    eq(new BigDecimal(100.5)), eq(300L),
                    eq("Q-TEST"));
        }

        @Test
        void nullSymbolInQuote_rejectedOrHandledGracefully() throws Exception {
            // Do NOT stub isKnown(null) – the handler may throw FieldNotFound before
            // ever reaching the registry, making such a stub unnecessary (Mockito strict).
            Quote q = new Quote();
            q.set(new QuoteID("Q1"));
            // no Symbol field set

            assertDoesNotThrow(() -> handler.handleQuote(q, sessionId));
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void multipleFills_allExecReportsSent() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);

            Order o = new Order("s", "C1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("100"), null);
            Execution e1 = new Execution(o, 50, new BigDecimal("99"), Execution.ExecType.PARTIAL_FILL);
            Execution e2 = new Execution(o, 50, new BigDecimal("99"), Execution.ExecType.FILL);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(List.of(e1, e2));

            handler.handleQuote(buildQuote("Q1", "AAPL", 99.0, 100.0, null, 0.0), sessionId);

            verify(sender, times(2)).send(eq(sessionId), any(ExecutionReport.class));
        }
    }

    // ── MassQuote (35=i) ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("MassQuote (35=i)")
    class MassQuoteTests {

        private MassQuote buildSingleEntryMassQuote(String quoteId, String symbol,
                                                    double bidPx, double bidSz,
                                                    double askPx, double askSz) throws Exception {
            MassQuote mq = new MassQuote();
            mq.set(new QuoteID(quoteId));

            MassQuote.NoQuoteSets setGrp = new MassQuote.NoQuoteSets();
            setGrp.set(new QuoteSetID("SET1"));
            setGrp.set(new NoQuoteEntries(1));

            MassQuote.NoQuoteSets.NoQuoteEntries entry = new MassQuote.NoQuoteSets.NoQuoteEntries();
            entry.set(new QuoteEntryID("E1"));
            entry.set(new Symbol(symbol));
            entry.set(new BidPx(bidPx));
            entry.set(new BidSize(bidSz));
            entry.set(new OfferPx(askPx));
            entry.set(new OfferSize(askSz));

            setGrp.addGroup(entry);
            mq.addGroup(setGrp);
            mq.set(new NoQuoteSets(1));

            return mq;
        }

        @Test
        void massQuote_allKnownSymbols_ackSent() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handleMassQuote(
                    buildSingleEntryMassQuote("MQ1", "AAPL", 99.0, 100.0, 101.0, 100.0),
                    sessionId);

            // ack from sendMassQuoteAck
            verify(sender).send(eq(sessionId), any(Message.class));
        }

        @Test
        void massQuote_unknownSymbolEntry_engineNotCalledForIt() throws Exception {
            when(registry.isKnown("UNKNOWN")).thenReturn(false);

            // This will fail on the unknown entry but still send an ack
            handler.handleMassQuote(
                    buildSingleEntryMassQuote("MQ1", "UNKNOWN", 99.0, 100.0, 101.0, 100.0),
                    sessionId);

            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void massQuote_withFills_execReportsSent() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);

            Order o = new Order("s", "C1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("100"), null);
            o.fill(100);
            Execution fill = new Execution(o, 100, new BigDecimal("99"), Execution.ExecType.FILL);

            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(List.of(fill));

            handler.handleMassQuote(
                    buildSingleEntryMassQuote("MQ1", "AAPL", 99.0, 100.0, 101.0, 100.0),
                    sessionId);

            verify(sender).send(eq(sessionId), any(ExecutionReport.class));
        }

        @Test
        void massQuote_engineCalledWithQuoteEntryId() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handleMassQuote(
                    buildSingleEntryMassQuote("MQ1", "AAPL", 99.0, 100.0, 101.0, 100.0),
                    sessionId);

            // Production code uses BigDecimal.valueOf(double) which preserves the double's
            // String representation (e.g. 99.0 → "99.0", scale=1).  Match with valueOf.
            verify(engine).onQuote(any(), eq("AAPL"),
                    eq(BigDecimal.valueOf(99.0)), eq(100L),
                    eq(BigDecimal.valueOf(101.0)), eq(100L),
                    eq("E1")); // QuoteEntryID
        }
    }
}
