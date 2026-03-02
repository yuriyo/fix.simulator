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
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.OrderCancelReject;
import quickfix.fix42.OrderCancelReplaceRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelReplaceHandler – OrderCancelReplaceRequest (G) dispatch")
class CancelReplaceHandlerTest {

    @Mock MatchingEngine     engine;
    @Mock InstrumentRegistry registry;
    @Mock ResponseSender     sender;

    private CancelReplaceHandler handler;
    private SessionID            sessionId;

    @BeforeEach
    void setUp() {
        handler   = new CancelReplaceHandler(engine, registry, sender);
        sessionId = new SessionID("FIX.4.2", "CLIENT", "EXCHANGE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OrderCancelReplaceRequest buildLimitReplace(String clOrdId, String origClOrdId,
                                                         String symbol, char side,
                                                         double qty, double price) throws Exception {
        OrderCancelReplaceRequest msg = new OrderCancelReplaceRequest();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new OrigClOrdID(origClOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.LIMIT));
        msg.set(new OrderQty(qty));
        msg.set(new Price(price));
        msg.set(new TransactTime());
        return msg;
    }

    private OrderCancelReplaceRequest buildStopReplace(String clOrdId, String origClOrdId,
                                                        String symbol, char side,
                                                        double qty, double stopPx) throws Exception {
        OrderCancelReplaceRequest msg = new OrderCancelReplaceRequest();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new OrigClOrdID(origClOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(side));
        msg.set(new OrdType(OrdType.STOP_STOP_LOSS));
        msg.set(new OrderQty(qty));
        msg.set(new StopPx(stopPx));
        msg.set(new TransactTime());
        return msg;
    }

    // ── Replace not found → CancelReject ─────────────────────────────────────

    @Nested
    @DisplayName("Original order not found")
    class NotFound {

        @Test
        void engineReturnsEmpty_sendsCancelReject() throws Exception {
            when(engine.replaceOrder(anyString(), eq("ORIG"), any()))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildLimitReplace("NEW", "ORIG", "AAPL", Side.BUY, 100, 99.0),
                    sessionId);

            verify(sender).send(eq(sessionId), any(OrderCancelReject.class));
            verify(sender, never()).send(eq(sessionId), any(ExecutionReport.class));
        }
    }

    // ── Successful replace → ExecReports ─────────────────────────────────────

    @Nested
    @DisplayName("Successful replace")
    class SuccessfulReplace {

        @Test
        void executionsForwardedToSender() throws Exception {
            Order origOrder = new Order("sess", "ORIG", null, "AAPL",
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    100, new BigDecimal("99"), null);
            origOrder.cancel();
            Order newOrder = new Order("sess", "NEW", "ORIG", "AAPL",
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    200, new BigDecimal("100"), null);

            Execution replaced = new Execution(origOrder, 0, null, Execution.ExecType.REPLACED);
            Execution newAck   = new Execution(newOrder,  0, null, Execution.ExecType.NEW);

            when(engine.replaceOrder(anyString(), eq("ORIG"), any()))
                    .thenReturn(List.of(replaced, newAck));

            handler.handle(buildLimitReplace("NEW", "ORIG", "AAPL", Side.BUY, 200, 100.0),
                    sessionId);

            verify(sender, times(2)).send(eq(sessionId), any(ExecutionReport.class));
            verify(sender, never()).send(eq(sessionId), any(OrderCancelReject.class));
        }
    }

    // ── Replacement order fields ──────────────────────────────────────────────

    @Nested
    @DisplayName("Replacement order construction")
    class ReplacementOrderFields {

        @Test
        void limitReplace_correctOrderFields() throws Exception {
            when(engine.replaceOrder(anyString(), any(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildLimitReplace("B2", "B1", "MSFT", Side.SELL, 50, 75.5),
                    sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).replaceOrder(anyString(), eq("B1"), captor.capture());

            Order replacement = captor.getValue();
            assertEquals("B2",                      replacement.getClOrdId());
            assertEquals("B1",                      replacement.getOrigClOrdId());
            assertEquals("MSFT",                    replacement.getSymbol());
            assertEquals(OrderSide.SELL,             replacement.getSide());
            assertEquals(OrderType.LIMIT,            replacement.getType());
            assertEquals(50,                         replacement.getTotalQty());
            assertEquals(new BigDecimal("75.5"),     replacement.getLimitPrice());
        }

        @Test
        void stopReplace_stopPxSet_noLimitPrice() throws Exception {
            when(engine.replaceOrder(anyString(), any(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildStopReplace("S2", "S1", "AAPL", Side.SELL, 100, 95.0),
                    sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).replaceOrder(anyString(), eq("S1"), captor.capture());

            Order replacement = captor.getValue();
            assertEquals(OrderType.STOP,             replacement.getType());
            assertEquals(new BigDecimal("95.0"),     replacement.getStopPrice());
            assertNull(replacement.getLimitPrice());
        }

        @Test
        void iocTif_parsedCorrectly() throws Exception {
            when(engine.replaceOrder(anyString(), any(), any()))
                    .thenReturn(Collections.emptyList());

            OrderCancelReplaceRequest msg =
                    buildLimitReplace("B2", "B1", "AAPL", Side.BUY, 100, 100.0);
            msg.set(new quickfix.field.TimeInForce(quickfix.field.TimeInForce.IMMEDIATE_OR_CANCEL));

            handler.handle(msg, sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).replaceOrder(anyString(), eq("B1"), captor.capture());
            assertEquals(TimeInForce.IOC, captor.getValue().getTif());
        }

        @Test
        void noTifField_defaultsToDay() throws Exception {
            when(engine.replaceOrder(anyString(), any(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildLimitReplace("B2", "B1", "AAPL", Side.BUY, 100, 100.0),
                    sessionId);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(engine).replaceOrder(anyString(), eq("B1"), captor.capture());
            assertEquals(TimeInForce.DAY, captor.getValue().getTif());
        }
    }

    // ── Engine called with correct sessionId prefix ───────────────────────────

    @Test
    void engineSessionIdContainsSenderCompId() throws Exception {
        when(engine.replaceOrder(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        handler.handle(buildLimitReplace("B2", "B1", "AAPL", Side.BUY, 100, 100.0),
                sessionId);

        verify(engine).replaceOrder(contains("CLIENT"), any(), any());
    }
}
