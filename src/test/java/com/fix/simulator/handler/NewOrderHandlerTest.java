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
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NewOrderHandler – FIX message parsing and dispatch")
class NewOrderHandlerTest {

    @Mock MatchingEngine     engine;
    @Mock InstrumentRegistry registry;
    @Mock ResponseSender     sender;

    private NewOrderHandler handler;
    private SessionID        sessionId;

    @BeforeEach
    void setUp() {
        handler   = new NewOrderHandler(engine, registry, sender);
        sessionId = new SessionID("FIX.4.4", "CLIENT", "EXCHANGE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private NewOrderSingle buildLimit(String clOrdId, String symbol, char side,
                                      double qty, double price) throws Exception {
        NewOrderSingle msg = new NewOrderSingle();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.LIMIT));
        msg.set(new OrderQty(qty));
        msg.set(new Price(price));
        msg.set(new TransactTime());
        return msg;
    }

    private NewOrderSingle buildMarket(String clOrdId, String symbol,
                                       char side, double qty) throws Exception {
        NewOrderSingle msg = new NewOrderSingle();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.MARKET));
        msg.set(new OrderQty(qty));
        msg.set(new TransactTime());
        return msg;
    }

    private NewOrderSingle buildStop(String clOrdId, String symbol,
                                     char side, double qty, double stopPx) throws Exception {
        NewOrderSingle msg = new NewOrderSingle();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.STOP_STOP_LOSS));
        msg.set(new OrderQty(qty));
        msg.set(new StopPx(stopPx));
        msg.set(new TransactTime());
        return msg;
    }

    private NewOrderSingle buildStopLimit(String clOrdId, String symbol,
                                          char side, double qty,
                                          double price, double stopPx) throws Exception {
        NewOrderSingle msg = new NewOrderSingle();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.STOP_LIMIT));
        msg.set(new OrderQty(qty));
        msg.set(new Price(price));
        msg.set(new StopPx(stopPx));
        msg.set(new TransactTime());
        return msg;
    }

    // ── Validation rejections ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation – symbol / field checks")
    class Validation {

        @Test
        void unknownSymbol_sendsRejectAndDoesNotCallEngine() throws Exception {
            when(registry.isKnown("ZZZZ")).thenReturn(false);

            handler.handle(buildLimit("C1", "ZZZZ", Side.BUY, 100, 100.0), sessionId);

            verify(sender).sendReject(eq(sessionId), eq("C1"), isNull(), anyString(),
                    eq(OrdRejReason.UNKNOWN_SYMBOL));
            verify(engine, never()).newOrder(any());
        }

        @Test
        void limitOrder_missingPrice_sendsReject() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            // Build limit order without Price field
            NewOrderSingle msg = new NewOrderSingle();
            msg.set(new ClOrdID("C1"));
            msg.set(new Symbol("AAPL"));
            msg.set(new Side(Side.BUY));
            msg.set(new OrdType(OrdType.LIMIT));
            msg.set(new OrderQty(100));
            msg.set(new TransactTime());
            // deliberately NOT setting Price

            handler.handle(msg, sessionId);

            verify(sender).sendReject(eq(sessionId), eq("C1"), isNull(), anyString(),
                    eq(OrdRejReason.BROKER_EXCHANGE_OPTION));
            verify(engine, never()).newOrder(any());
        }

        @Test
        void stopOrder_missingStopPx_sendsReject() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            NewOrderSingle msg = new NewOrderSingle();
            msg.set(new ClOrdID("C1"));
            msg.set(new Symbol("AAPL"));
            msg.set(new Side(Side.BUY));
            msg.set(new OrdType(OrdType.STOP_STOP_LOSS));
            msg.set(new OrderQty(100));
            msg.set(new TransactTime());
            // no StopPx

            handler.handle(msg, sessionId);

            verify(sender).sendReject(eq(sessionId), eq("C1"), isNull(), anyString(),
                    eq(OrdRejReason.BROKER_EXCHANGE_OPTION));
            verify(engine, never()).newOrder(any());
        }

        @Test
        void stopLimitOrder_missingPrice_sendsReject() throws Exception {
            when(registry.isKnown("AAPL")).thenReturn(true);
            NewOrderSingle msg = new NewOrderSingle();
            msg.set(new ClOrdID("C1"));
            msg.set(new Symbol("AAPL"));
            msg.set(new Side(Side.BUY));
            msg.set(new OrdType(OrdType.STOP_LIMIT));
            msg.set(new OrderQty(100));
            msg.set(new StopPx(100.0));
            msg.set(new TransactTime());
            // no Price

            handler.handle(msg, sessionId);

            verify(sender).sendReject(any(), any(), any(), anyString(), anyInt());
            verify(engine, never()).newOrder(any());
        }
    }

    // ── Order dispatch ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Order dispatch to engine")
    class Dispatch {

        @BeforeEach
        void knownSymbol() {
            when(registry.isKnown("AAPL")).thenReturn(true);
        }

        @Test
        void limitBuyOrder_dispatchedWithCorrectFields() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildLimit("C1", "AAPL", Side.BUY, 100, 150.0), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());

            Order o = captor.getValue();
            assertEquals("C1",               o.getClOrdId());
            assertEquals("AAPL",             o.getSymbol());
            assertEquals(OrderSide.BUY,      o.getSide());
            assertEquals(OrderType.LIMIT,    o.getType());
            assertEquals(100,                o.getTotalQty());
            assertEquals(new BigDecimal("150.0"), o.getLimitPrice());
            assertNull(o.getStopPrice());
        }

        @Test
        void limitSellOrder_dispatchedWithSellSide() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildLimit("C2", "AAPL", Side.SELL, 50, 200.0), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(OrderSide.SELL, captor.getValue().getSide());
        }

        @Test
        void marketOrder_noLimitPrice() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildMarket("M1", "AAPL", Side.BUY, 100), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(OrderType.MARKET, captor.getValue().getType());
            assertNull(captor.getValue().getLimitPrice());
        }

        @Test
        void stopOrder_stopPxSet() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildStop("S1", "AAPL", Side.SELL, 100, 95.0), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(OrderType.STOP, captor.getValue().getType());
            assertEquals(new BigDecimal("95.0"), captor.getValue().getStopPrice());
        }

        @Test
        void stopLimitOrder_bothPricesSet() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildStopLimit("SL1", "AAPL", Side.BUY, 100, 101.0, 100.0), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(OrderType.STOP_LIMIT, captor.getValue().getType());
            assertEquals(new BigDecimal("101.0"), captor.getValue().getLimitPrice());
            assertEquals(new BigDecimal("100.0"), captor.getValue().getStopPrice());
        }

        @Test
        void iocOrder_tifParsedCorrectly() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());
            NewOrderSingle msg = buildLimit("I1", "AAPL", Side.BUY, 100, 150.0);
            msg.set(new quickfix.field.TimeInForce(quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL));

            handler.handle(msg, sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(TimeInForce.IOC, captor.getValue().getTif());
        }

        @Test
        void gtcOrder_tifParsedCorrectly() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());
            NewOrderSingle msg = buildLimit("G1", "AAPL", Side.BUY, 100, 150.0);
            msg.set(new quickfix.field.TimeInForce(quickfix.field.TimeInForce.GOOD_TILL_CANCEL));

            handler.handle(msg, sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(TimeInForce.GTC, captor.getValue().getTif());
        }

        @Test
        void fokOrder_tifParsedCorrectly() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());
            NewOrderSingle msg = buildLimit("FK1", "AAPL", Side.BUY, 100, 150.0);
            msg.set(new quickfix.field.TimeInForce(quickfix.field.TimeInForce.FILL_OR_KILL));

            handler.handle(msg, sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(TimeInForce.FOK, captor.getValue().getTif());
        }

        @Test
        void noTifField_defaultsToDay() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildLimit("D1", "AAPL", Side.BUY, 100, 150.0), sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).newOrder(captor.capture());
            assertEquals(TimeInForce.DAY, captor.getValue().getTif());
        }
    }

    // ── Response forwarding ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Execution response forwarding")
    class ResponseForwarding {

        @BeforeEach
        void knownSymbol() {
            when(registry.isKnown("AAPL")).thenReturn(true);
        }

        @Test
        void eachExecution_sentToSender() throws Exception {
            Order o = new Order("ses", "C1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("100"), null);
            Execution e1 = new Execution(o, 0, null, Execution.ExecType.NEW);
            Execution e2 = new Execution(o, 100, new BigDecimal("99"), Execution.ExecType.FILL);
            when(engine.newOrder(any())).thenReturn(List.of(e1, e2));

            handler.handle(buildLimit("C1", "AAPL", Side.BUY, 100, 100.0), sessionId);

            verify(sender, times(2)).send(eq(sessionId), any(ExecutionReport.class));
        }

        @Test
        void noExecutions_senderNotCalledForExecReports() throws Exception {
            when(engine.newOrder(any())).thenReturn(Collections.emptyList());

            handler.handle(buildLimit("C1", "AAPL", Side.BUY, 100, 100.0), sessionId);

            verify(sender, never()).send(any(), any());
        }
    }
}
