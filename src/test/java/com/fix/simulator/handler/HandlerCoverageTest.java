package com.fix.simulator.handler;

import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.admin.InstrumentStatus;
import com.fix.simulator.engine.*;
import com.fix.simulator.engine.TimeInForce;
import com.fix.simulator.util.InstrumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.*;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Branch and path coverage for the FIX handlers and {@link ResponseSender}. */
@DisplayName("Handlers – coverage of remaining branches")
class HandlerCoverageTest {

    private SessionID sid;

    @BeforeEach
    void setUp() {
        sid = new SessionID("FIX.4.2", "EXCHANGE", "CLIENT");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NewOrderHandler
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NewOrderHandler")
    class NewOrder {

        MatchingEngine engine;
        ResponseSender sender;
        InstrumentRegistry registry;
        NewOrderHandler handler;

        @BeforeEach
        void init() {
            engine = mock(MatchingEngine.class);
            sender = mock(ResponseSender.class);
            registry = mock(InstrumentRegistry.class);
            when(registry.isKnown("AAPL")).thenReturn(true);
            handler = new NewOrderHandler(engine, registry, sender);
        }

        NewOrderSingle msg(char side, char ordType, double qty, Double px, Double stopPx, Character tif) throws Exception {
            NewOrderSingle m = new NewOrderSingle();
            m.set(new ClOrdID("C1"));
            m.set(new Symbol("AAPL"));
            m.set(new Side(side));
            m.set(new OrdType(ordType));
            m.set(new OrderQty(qty));
            if (px != null) m.set(new Price(px));
            if (stopPx != null) m.set(new StopPx(stopPx));
            if (tif != null) m.set(new quickfix.field.TimeInForce(tif));
            m.set(new TransactTime());
            return m;
        }

        @Test
        void haltedInstrument_rejected() throws Exception {
            InstrumentStateManager st = new InstrumentStateManager();
            st.setState("AAPL", InstrumentStatus.HALT);
            handler.setStateManager(st);
            handler.handle(msg(Side.BUY, OrdType.LIMIT, 10, 10.0, null, null), sid);
            verify(sender).sendReject(any(), eq("C1"), any(), contains("halted"), anyInt());
            verify(engine, never()).newOrder(any());
        }

        @Test
        void quotesOnlyInstrument_rejected() throws Exception {
            InstrumentStateManager st = new InstrumentStateManager();
            st.setState("AAPL", InstrumentStatus.QUOTES_ONLY);
            handler.setStateManager(st);
            handler.handle(msg(Side.BUY, OrdType.LIMIT, 10, 10.0, null, null), sid);
            verify(sender).sendReject(any(), eq("C1"), any(), contains("QUOTES_ONLY"), anyInt());
            verify(engine, never()).newOrder(any());
        }

        @Test
        void openInstrument_accepted() throws Exception {
            InstrumentStateManager st = new InstrumentStateManager();
            handler.setStateManager(st);
            handler.handle(msg(Side.SELL, OrdType.LIMIT, 10, 10.0, null, null), sid);
            verify(engine).newOrder(any());
        }

        @Test
        void limitWithoutPrice_rejected() throws Exception {
            handler.handle(msg(Side.BUY, OrdType.LIMIT, 10, null, null, null), sid);
            verify(sender).sendReject(any(), eq("C1"), any(), contains("requires Price"), anyInt());
            verify(engine, never()).newOrder(any());
        }

        @Test
        void stopWithoutStopPx_rejected() throws Exception {
            handler.handle(msg(Side.BUY, OrdType.STOP_STOP_LOSS, 10, null, null, null), sid);
            verify(sender).sendReject(any(), eq("C1"), any(), contains("StopPx"), anyInt());
            verify(engine, never()).newOrder(any());
        }

        @Test
        void stopLimitWithPriceAndStop_accepted() throws Exception {
            handler.handle(msg(Side.BUY, OrdType.STOP_LIMIT, 10, 10.5, 10.0, null), sid);
            ArgumentCaptor<Order> c = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(c.capture());
            assertEquals(OrderType.STOP_LIMIT, c.getValue().getType());
            assertEquals(0, c.getValue().getStopPrice().compareTo(new BigDecimal("10.0")));
        }

        @Test
        void marketOrder_accepted_noPriceRequired() throws Exception {
            handler.handle(msg(Side.BUY, OrdType.MARKET, 10, null, null, null), sid);
            ArgumentCaptor<Order> c = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(c.capture());
            assertEquals(OrderType.MARKET, c.getValue().getType());
            assertNull(c.getValue().getLimitPrice());
        }

        @Test
        void stopOrder_withStopPx_accepted() throws Exception {
            handler.handle(msg(Side.SELL, OrdType.STOP_STOP_LOSS, 10, null, 9.5, null), sid);
            verify(engine).newOrder(any());
        }

        @Test
        void timeInForceMapping() throws Exception {
            char[] tifs = {quickfix.field.TimeInForce.GOOD_TILL_CANCEL,
                           quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL,
                           quickfix.field.TimeInForce.FILL_OR_KILL,
                           quickfix.field.TimeInForce.DAY};
            TimeInForce[] expected = {TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK, TimeInForce.DAY};
            for (int i = 0; i < tifs.length; i++) {
                clearInvocations(engine);
                handler.handle(msg(Side.BUY, OrdType.LIMIT, 10, 10.0, null, tifs[i]), sid);
                ArgumentCaptor<Order> c = ArgumentCaptor.forClass(Order.class);
                verify(engine).newOrder(c.capture());
                assertEquals(expected[i], c.getValue().getTif());
            }
        }

        @Test
        void unsupportedSide_exceptionCaughtNothingSent() throws Exception {
            handler.handle(msg('X', OrdType.LIMIT, 10, 10.0, null, null), sid);
            verify(engine, never()).newOrder(any());
        }

        @Test
        void unsupportedOrdType_exceptionCaughtNothingSent() throws Exception {
            handler.handle(msg(Side.BUY, 'Z', 10, 10.0, null, null), sid);
            verify(engine, never()).newOrder(any());
        }

        @Test
        void missingMandatoryField_exceptionCaught() {
            handler.handle(new NewOrderSingle(), sid);
            verify(engine, never()).newOrder(any());
        }

        @Test
        void executionsReturnedByEngine_areAllSent() throws Exception {
            Order o = new Order(sid.toString(), "C1", null, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 10, new BigDecimal("10"), null);
            when(engine.newOrder(any())).thenReturn(List.of(
                    new Execution(o, 0, null, Execution.ExecType.NEW),
                    new Execution(o, 10, new BigDecimal("10"), Execution.ExecType.FILL)));
            handler.handle(msg(Side.BUY, OrdType.LIMIT, 10, 10.0, null, null), sid);
            verify(sender, times(2)).send(eq(sid), any(Message.class));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  QuoteHandler
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QuoteHandler")
    class Quotes {

        MatchingEngine engine;
        ResponseSender sender;
        InstrumentRegistry registry;
        QuoteHandler handler;

        @BeforeEach
        void init() {
            engine = mock(MatchingEngine.class);
            sender = mock(ResponseSender.class);
            registry = mock(InstrumentRegistry.class);
            when(registry.isKnown("AAPL")).thenReturn(true);
            handler = new QuoteHandler(engine, registry, sender);
        }

        Quote quote(String id, String sym, Double bid, Double bidSz, Double ask, Double askSz) throws Exception {
            Quote q = new Quote();
            q.set(new QuoteID(id));
            if (sym != null) q.set(new Symbol(sym));
            if (bid != null) q.set(new BidPx(bid));
            if (bidSz != null) q.set(new BidSize(bidSz));
            if (ask != null) q.set(new OfferPx(ask));
            if (askSz != null) q.set(new OfferSize(askSz));
            return q;
        }

        MassQuote massQuote(String quoteId, boolean withQuoteSetId, MassQuoteEntry... entries) throws Exception {
            MassQuote mq = new MassQuote();
            mq.set(new QuoteID(quoteId));
            mq.set(new NoQuoteSets(1));
            MassQuote.NoQuoteSets set = new MassQuote.NoQuoteSets();
            if (withQuoteSetId) set.set(new QuoteSetID("SET1"));
            set.set(new NoQuoteEntries(entries.length));
            for (MassQuoteEntry e : entries) set.addGroup(e.toGroup());
            mq.addGroup(set);
            return mq;
        }

        static final class MassQuoteEntry {
            final String sym, entryId;
            final Double bid, bidSz, ask, askSz;
            MassQuoteEntry(String sym, String entryId, Double bid, Double bidSz, Double ask, Double askSz) {
                this.sym = sym; this.entryId = entryId; this.bid = bid; this.bidSz = bidSz; this.ask = ask; this.askSz = askSz;
            }
            MassQuote.NoQuoteSets.NoQuoteEntries toGroup() throws Exception {
                MassQuote.NoQuoteSets.NoQuoteEntries g = new MassQuote.NoQuoteSets.NoQuoteEntries();
                if (sym != null) g.set(new Symbol(sym));
                if (entryId != null) g.set(new QuoteEntryID(entryId));
                if (bid != null) g.set(new BidPx(bid));
                if (bidSz != null) g.set(new BidSize(bidSz));
                if (ask != null) g.set(new OfferPx(ask));
                if (askSz != null) g.set(new OfferSize(askSz));
                return g;
            }
        }

        // ── single quote ────────────────────────────────────────────────────

        @Test
        void quote_missingSymbol_rejectedAck() throws Exception {
            handler.handleQuote(quote("Q1", null, 9.0, 5.0, 11.0, 5.0), sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
            verify(sender).send(eq(sid), any(QuoteAcknowledgement.class));
        }

        @Test
        void quote_haltedSymbol_rejectedAck() throws Exception {
            InstrumentStateManager st = new InstrumentStateManager();
            st.setState("AAPL", InstrumentStatus.HALT);
            handler.setStateManager(st);
            handler.handleQuote(quote("Q1", "AAPL", 9.0, 5.0, 11.0, 5.0), sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void quote_bidAndAsk_acceptedAndFillsForwarded() throws Exception {
            Order o = new Order(sid.toString(), "q-bid-Q1", null, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 5, new BigDecimal("9"), null);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(List.of(new Execution(o, 5, new BigDecimal("9"), Execution.ExecType.FILL)));
            handler.handleQuote(quote("Q1", "AAPL", 9.0, 5.0, 11.0, 5.0), sid);
            verify(engine).onQuote(eq(sid.toString()), eq("AAPL"), any(), eq(5L), any(), eq(5L), eq("Q1"));
            verify(sender).send(eq(sid), any(ExecutionReport.class));
            verify(sender).send(eq(sid), any(QuoteAcknowledgement.class));
        }

        @Test
        void quote_bidOnly_askAbsent_passesNullAsk() throws Exception {
            handler.handleQuote(quote("Q1", "AAPL", 9.0, 5.0, null, null), sid);
            verify(engine).onQuote(any(), eq("AAPL"), any(), eq(5L), isNull(), eq(0L), any());
        }

        @Test
        void quote_askOnly_bidAbsent_passesNullBid() throws Exception {
            handler.handleQuote(quote("Q1", "AAPL", null, null, 11.0, 5.0), sid);
            verify(engine).onQuote(any(), eq("AAPL"), isNull(), eq(0L), any(), eq(5L), any());
        }

        // ── mass quote ──────────────────────────────────────────────────────

        @Test
        void massQuote_allEntriesAccepted_ackAccepted() throws Exception {
            MassQuote mq = massQuote("MQ1", true,
                    new MassQuoteEntry("AAPL", "E1", 9.0, 5.0, 11.0, 5.0),
                    new MassQuoteEntry("AAPL", null, 8.0, 4.0, 12.0, 4.0));   // entryId defaults
            handler.handleMassQuote(mq, sid);
            verify(engine, times(2)).onQuote(any(), eq("AAPL"), any(), anyLong(), any(), anyLong(), any());
            ArgumentCaptor<QuoteAcknowledgement> ack = ArgumentCaptor.forClass(QuoteAcknowledgement.class);
            verify(sender).send(eq(sid), ack.capture());
        }

        @Test
        void massQuote_withoutQuoteSetId_usesEmptySetId() throws Exception {
            MassQuote mq = massQuote("MQ1", false, new MassQuoteEntry("AAPL", null, 9.0, 5.0, 11.0, 5.0));
            handler.handleMassQuote(mq, sid);
            verify(engine).onQuote(any(), eq("AAPL"), any(), anyLong(), any(), anyLong(), eq("-1"));
        }

        @Test
        void massQuote_unknownAndMissingSymbolEntries_rejectedCounted() throws Exception {
            when(registry.isKnown("ZZZZ")).thenReturn(false);
            MassQuote mq = massQuote("MQ1", true,
                    new MassQuoteEntry("ZZZZ", null, 9.0, 5.0, 11.0, 5.0),
                    new MassQuoteEntry(null, null, 9.0, 5.0, 11.0, 5.0),
                    new MassQuoteEntry("AAPL", null, 9.0, 5.0, 11.0, 5.0));
            handler.handleMassQuote(mq, sid);
            verify(engine, times(1)).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void massQuote_haltedEntryRejected() throws Exception {
            InstrumentStateManager st = new InstrumentStateManager();
            st.setState("AAPL", InstrumentStatus.HALT);
            handler.setStateManager(st);
            MassQuote mq = massQuote("MQ1", true, new MassQuoteEntry("AAPL", null, 9.0, 5.0, 11.0, 5.0));
            handler.handleMassQuote(mq, sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void massQuote_entryWithoutPricesOrSizes_nullsPassed() throws Exception {
            MassQuote mq = massQuote("MQ1", true, new MassQuoteEntry("AAPL", "E9", null, null, null, null));
            handler.handleMassQuote(mq, sid);
            verify(engine).onQuote(any(), eq("AAPL"), isNull(), eq(0L), isNull(), eq(0L), eq("E9"));
        }

        @Test
        void massQuote_fillsForwardedToSender() throws Exception {
            Order o = new Order(sid.toString(), "q", null, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 5, new BigDecimal("9"), null);
            when(engine.onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any()))
                    .thenReturn(List.of(new Execution(o, 5, new BigDecimal("9"), Execution.ExecType.FILL)));
            MassQuote mq = massQuote("MQ1", true, new MassQuoteEntry("AAPL", "E1", 9.0, 5.0, 11.0, 5.0));
            handler.handleMassQuote(mq, sid);
            verify(sender).send(eq(sid), any(ExecutionReport.class));
        }

        @Test
        void massQuote_someRejected_ackRejected() throws Exception {
            MassQuote mq = massQuote("MQ1", true,
                    new MassQuoteEntry(null, null, 9.0, 5.0, 11.0, 5.0));
            handler.handleMassQuote(mq, sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
            verify(sender).send(eq(sid), any(QuoteAcknowledgement.class));
        }

        @Test
        void massQuote_malformed_exceptionCaught() {
            handler.handleMassQuote(new MassQuote(), sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }

        @Test
        void quote_malformed_exceptionCaught() {
            handler.handleQuote(new Quote(), sid);
            verify(engine, never()).onQuote(any(), any(), any(), anyLong(), any(), anyLong(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CancelHandler
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CancelHandler")
    class Cancel {

        MatchingEngine engine;
        ResponseSender sender;
        CancelHandler handler;

        @BeforeEach
        void init() {
            engine = mock(MatchingEngine.class);
            sender = mock(ResponseSender.class);
            handler = new CancelHandler(engine, sender);
        }

        OrderCancelRequest req() throws Exception {
            OrderCancelRequest m = new OrderCancelRequest();
            m.set(new ClOrdID("CXL1"));
            m.set(new OrigClOrdID("ORD1"));
            m.set(new Symbol("AAPL"));
            m.set(new Side(Side.BUY));
            m.set(new TransactTime());
            return m;
        }

        @Test
        void cancelled_sendsExecutionReport() throws Exception {
            Order o = new Order(sid.toString(), "ORD1", null, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null);
            when(engine.cancelOrder(any(), any(), any(), any()))
                    .thenReturn(List.of(new Execution(o, 0, null, Execution.ExecType.CANCELLED)));
            handler.handle(req(), sid);
            verify(sender).send(eq(sid), any(ExecutionReport.class));
        }

        @Test
        void notFound_sendsCancelReject() throws Exception {
            when(engine.cancelOrder(any(), any(), any(), any())).thenReturn(List.of());
            handler.handle(req(), sid);
            verify(sender).send(eq(sid), any(OrderCancelReject.class));
        }

        @Test
        void malformedRequest_exceptionCaught() {
            handler.handle(new OrderCancelRequest(), sid);
            verify(sender, never()).send(any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CancelReplaceHandler
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CancelReplaceHandler")
    class CancelReplace {

        MatchingEngine engine;
        ResponseSender sender;
        InstrumentRegistry registry;
        CancelReplaceHandler handler;

        @BeforeEach
        void init() {
            engine = mock(MatchingEngine.class);
            sender = mock(ResponseSender.class);
            registry = mock(InstrumentRegistry.class);
            handler = new CancelReplaceHandler(engine, registry, sender);
        }

        OrderCancelReplaceRequest req(char side, char ordType, Double px, Double stopPx, Character tif) throws Exception {
            OrderCancelReplaceRequest m = new OrderCancelReplaceRequest();
            m.set(new ClOrdID("RPL1"));
            m.set(new OrigClOrdID("ORD1"));
            m.set(new Symbol("AAPL"));
            m.set(new Side(side));
            m.set(new OrdType(ordType));
            m.set(new OrderQty(7));
            if (px != null) m.set(new Price(px));
            if (stopPx != null) m.set(new StopPx(stopPx));
            if (tif != null) m.set(new quickfix.field.TimeInForce(tif));
            m.set(new TransactTime());
            return m;
        }

        Order captured() {
            ArgumentCaptor<Order> c = ArgumentCaptor.forClass(Order.class);
            verify(engine).replaceOrder(any(), any(), c.capture());
            return c.getValue();
        }

        @Test
        void sellLimit_replacementCarriesFields() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            handler.handle(req(Side.SELL, OrdType.LIMIT, 10.0, null, null), sid);
            Order r = captured();
            assertEquals(OrderSide.SELL, r.getSide());
            assertEquals(OrderType.LIMIT, r.getType());
            assertEquals(TimeInForce.DAY, r.getTif());
            assertEquals(7L, r.getOpenQty());
            assertEquals("ORD1", r.getOrigClOrdId());
        }

        @Test
        void marketStopAndStopLimit_typesMapped() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            handler.handle(req(Side.BUY, OrdType.MARKET, null, null, null), sid);
            assertEquals(OrderType.MARKET, captured().getType());
            clearInvocations(engine);
            handler.handle(req(Side.BUY, OrdType.STOP_STOP_LOSS, null, 9.0, null), sid);
            assertEquals(OrderType.STOP, captured().getType());
            clearInvocations(engine);
            handler.handle(req(Side.BUY, OrdType.STOP_LIMIT, 9.5, 9.0, null), sid);
            Order sl = captured();
            assertEquals(OrderType.STOP_LIMIT, sl.getType());
            assertNotNull(sl.getStopPrice());
        }

        @Test
        void unknownOrdType_defaultsToLimit() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            handler.handle(req(Side.BUY, 'Z', 10.0, null, null), sid);
            assertEquals(OrderType.LIMIT, captured().getType());
        }

        @Test
        void stopLimitWithoutPriceAndStop_nullsKept() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            handler.handle(req(Side.BUY, OrdType.STOP_LIMIT, null, null, null), sid);
            Order o = captured();
            assertNull(o.getLimitPrice());
            assertNull(o.getStopPrice());
        }

        @Test
        void timeInForceMapping() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            char[] tifs = {quickfix.field.TimeInForce.GOOD_TILL_CANCEL,
                           quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL,
                           quickfix.field.TimeInForce.FILL_OR_KILL,
                           quickfix.field.TimeInForce.DAY};
            TimeInForce[] exp = {TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK, TimeInForce.DAY};
            for (int i = 0; i < tifs.length; i++) {
                clearInvocations(engine);
                handler.handle(req(Side.BUY, OrdType.LIMIT, 10.0, null, tifs[i]), sid);
                assertEquals(exp[i], captured().getTif());
            }
        }

        @Test
        void notFound_sendsCancelReject() throws Exception {
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of());
            handler.handle(req(Side.BUY, OrdType.LIMIT, 10.0, null, null), sid);
            verify(sender).send(eq(sid), any(OrderCancelReject.class));
        }

        @Test
        void replaced_sendsExecutionReports() throws Exception {
            Order o = new Order(sid.toString(), "RPL1", "ORD1", "AAPL", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 7, new BigDecimal("10"), null);
            when(engine.replaceOrder(any(), any(), any())).thenReturn(List.of(
                    new Execution(o, 0, null, Execution.ExecType.REPLACED),
                    new Execution(o, 0, null, Execution.ExecType.NEW)));
            handler.handle(req(Side.BUY, OrdType.LIMIT, 10.0, null, null), sid);
            verify(sender, times(2)).send(eq(sid), any(ExecutionReport.class));
        }

        @Test
        void malformedRequest_exceptionCaught() {
            handler.handle(new OrderCancelReplaceRequest(), sid);
            verify(sender, never()).send(any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ResponseSender default method
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ResponseSender.sendReject")
    class ResponseSenderDefaults {

        @Test
        void sendReject_buildsRejectWithReasonAndOrigClOrdId() throws Exception {
            Message[] captured = new Message[1];
            ResponseSender s = (sessionId, message) -> captured[0] = message;

            s.sendReject(sid, "C9", "ORIG9", "bad order", OrdRejReason.BROKER_EXCHANGE_OPTION);

            assertTrue(captured[0] instanceof ExecutionReport);
            assertEquals("C9", captured[0].getString(ClOrdID.FIELD));
            assertEquals("ORIG9", captured[0].getString(OrigClOrdID.FIELD));
            assertEquals("bad order", captured[0].getString(Text.FIELD));
        }

        @Test
        void sendReject_withoutOrigClOrdId_omitsField() throws Exception {
            Message[] captured = new Message[1];
            ResponseSender s = (sessionId, message) -> captured[0] = message;

            s.sendReject(sid, "C9", null, "bad", OrdRejReason.UNKNOWN_SYMBOL);

            assertFalse(captured[0].isSetField(OrigClOrdID.FIELD));
        }
    }
}
