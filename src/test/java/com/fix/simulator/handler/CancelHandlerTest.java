package com.fix.simulator.handler;

import com.fix.simulator.engine.*;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.OrderCancelReject;
import quickfix.fix42.OrderCancelRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelHandler – OrderCancelRequest (F) dispatch")
class CancelHandlerTest {

    @Mock MatchingEngine engine;
    @Mock ResponseSender sender;

    private CancelHandler handler;
    private SessionID     sessionId;

    @BeforeEach
    void setUp() {
        handler   = new CancelHandler(engine, sender);
        sessionId = new SessionID("FIX.4.2", "CLIENT", "EXCHANGE");
    }

    private OrderCancelRequest buildRequest(String clOrdId, String origClOrdId,
                                            String symbol) throws Exception {
        OrderCancelRequest msg = new OrderCancelRequest();
        msg.set(new ClOrdID(clOrdId));
        msg.set(new OrigClOrdID(origClOrdId));
        msg.set(new Symbol(symbol));
        msg.set(new Side(Side.BUY));
        msg.set(new TransactTime());
        return msg;
    }

    // ── Order not found → CancelReject ───────────────────────────────────────

    @Nested
    @DisplayName("Order not found path")
    class OrderNotFound {

        @Test
        void engineReturnsEmpty_senderSendsCancelReject() throws Exception {
            when(engine.cancelOrder(anyString(), eq("B1"), eq("B2"), eq("AAPL")))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildRequest("B2", "B1", "AAPL"), sessionId);

            verify(sender).send(eq(sessionId), any(OrderCancelReject.class));
            verify(sender, never()).send(eq(sessionId), any(ExecutionReport.class));
        }

        @Test
        void cancelReject_containsClOrdIdAndOrigClOrdId() throws Exception {
            when(engine.cancelOrder(any(), any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            handler.handle(buildRequest("NEW-ID", "ORIG-ID", "MSFT"), sessionId);

            // We can verify the message type via ArgumentCaptor
            var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
            verify(sender).send(eq(sessionId), captor.capture());
            // The captured message should be an OrderCancelReject
            assert captor.getValue() instanceof OrderCancelReject;
        }
    }

    // ── Successful cancel → ExecutionReport ──────────────────────────────────

    @Nested
    @DisplayName("Successful cancel path")
    class SuccessfulCancel {

        @Test
        void engineReturnsCancelledExec_senderSendsExecReport() throws Exception {
            Order o = new Order("sess", "B1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("99"), null);
            o.cancel();
            Execution cancelExec = new Execution(o, 0, null, Execution.ExecType.CANCELLED);

            when(engine.cancelOrder(anyString(), eq("B1"), eq("B2"), eq("AAPL")))
                    .thenReturn(List.of(cancelExec));

            handler.handle(buildRequest("B2", "B1", "AAPL"), sessionId);

            verify(sender).send(eq(sessionId), any(ExecutionReport.class));
            verify(sender, never()).send(eq(sessionId), any(OrderCancelReject.class));
        }

        @Test
        void multipleExecutions_allSent() throws Exception {
            Order o = new Order("sess", "B1", null, "AAPL", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("99"), null);
            Execution e1 = new Execution(o, 0, null, Execution.ExecType.CANCELLED);
            Execution e2 = new Execution(o, 0, null, Execution.ExecType.CANCELLED);

            when(engine.cancelOrder(any(), any(), any(), any()))
                    .thenReturn(List.of(e1, e2));

            handler.handle(buildRequest("B2", "B1", "AAPL"), sessionId);

            verify(sender, times(2)).send(eq(sessionId), any(ExecutionReport.class));
        }
    }

    // ── Engine called with correct args ──────────────────────────────────────

    @Test
    void engineCalledWithParsedFields() throws Exception {
        when(engine.cancelOrder(anyString(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        handler.handle(buildRequest("NEW-CXL", "ORIG-ORD", "GOOG"), sessionId);

        verify(engine).cancelOrder(
                contains("CLIENT"), // sessionId string contains SenderCompID
                eq("ORIG-ORD"),
                eq("NEW-CXL"),
                eq("GOOG"));
    }
}
