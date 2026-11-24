package com.fix.simulator.defects;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.handler.NewOrderHandler;
import com.fix.simulator.handler.ResponseSender;
import com.fix.simulator.util.InstrumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Defect regression tests D7: {@link NewOrderHandler} performs no quantity, lot-size or
 * tick-size validation. It also truncates fractional quantities with {@code (long) qty}.
 *
 * <p>Correct behaviour: invalid orders are rejected with {@code sendReject} and never reach
 * the engine. Each defect test therefore asserts "rejected, engine never called".
 */
@DisplayName("Defect D7 – missing quantity / lot / tick validation in NewOrderHandler")
class NewOrderValidationDefectsTest {

    @TempDir Path tmp;

    private MatchingEngine     engine;
    private ResponseSender     sender;
    private NewOrderHandler    handler;
    private SessionID          sessionId;

    @BeforeEach
    void setUp() throws Exception {
        Path yml = tmp.resolve("instruments.yml");
        Files.writeString(yml, """
                instruments:
                  - symbol: AAPL
                    lotSize: 1
                    tickSize: "0.01"
                  - symbol: LOT10
                    lotSize: 10
                    tickSize: "0.05"
                """);
        InstrumentRegistry registry = new InstrumentRegistry();
        registry.load(yml.toString());

        engine    = mock(MatchingEngine.class);
        sender    = mock(ResponseSender.class);
        handler   = new NewOrderHandler(engine, registry, sender);
        sessionId = new SessionID("FIX.4.2", "CLIENT", "EXCHANGE");
    }

    private NewOrderSingle buy(String symbol, double qty, double px) throws Exception {
        NewOrderSingle msg = new NewOrderSingle();
        msg.set(new ClOrdID("C" + System.nanoTime()));
        msg.set(new Symbol(symbol));
        msg.set(new Side(Side.BUY));
        msg.set(new OrdType(OrdType.LIMIT));
        msg.set(new OrderQty(qty));
        msg.set(new Price(px));
        msg.set(new TransactTime());
        return msg;
    }

    private void assertRejectedAndNotEngaged(NewOrderSingle msg) throws Exception {
        handler.handle(msg, sessionId);
        verify(sender, atLeastOnce()).sendReject(any(), any(), any(), any(), anyInt());
        verify(engine, never()).newOrder(any());
    }

    private void assertAccepted(NewOrderSingle msg) throws Exception {
        handler.handle(msg, sessionId);
        verify(engine, times(1)).newOrder(any());
    }

    @Nested
    @DisplayName("D7a – quantity validation")
    class Quantity {

        @Test
        void zeroQuantity_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("AAPL", 0, 10.00));           // actual: engine called
        }

        @Test
        void negativeQuantity_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("AAPL", -5, 10.00));          // actual: engine called
        }

        @Test
        void fractionalQuantity_isRejectedNotTruncated() throws Exception {
            assertRejectedAndNotEngaged(buy("AAPL", 10.5, 10.00));        // actual: 10.5 -> 10 accepted
        }

        @Test
        void control_validWholeQuantity_isAccepted() throws Exception {
            assertAccepted(buy("AAPL", 10, 10.00));
        }
    }

    @Nested
    @DisplayName("D7b – lot-size validation (lotSize from instruments.yml is ignored)")
    class LotSize {

        @Test
        void quantityNotMultipleOfLot_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("LOT10", 7, 10.00));          // actual: accepted
        }

        @Test
        void quantityBelowLot_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("LOT10", 5, 10.00));          // actual: accepted
        }

        @Test
        void control_quantityMultipleOfLot_isAccepted() throws Exception {
            assertAccepted(buy("LOT10", 20, 10.00));
        }
    }

    @Nested
    @DisplayName("D7c – tick-size validation (tickSize from instruments.yml is ignored)")
    class TickSize {

        @Test
        void priceOffTick_halfCent_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("AAPL", 10, 10.005));         // actual: accepted
        }

        @Test
        void priceOffTick_multiTickMismatch_isRejected() throws Exception {
            assertRejectedAndNotEngaged(buy("LOT10", 10, 10.02));         // tick 0.05; actual: accepted
        }

        @Test
        void control_priceOnTick_isAccepted() throws Exception {
            assertAccepted(buy("AAPL", 10, 10.00));
        }

        @Test
        void control_priceOnCoarseTick_isAccepted() throws Exception {
            assertAccepted(buy("LOT10", 10, 10.15));
        }
    }
}
