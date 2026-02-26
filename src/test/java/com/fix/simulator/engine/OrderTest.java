package com.fix.simulator.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Order – state machine")
class OrderTest {

    private static Order limitBuy(long qty) {
        return new Order("sess", "C1", null, "AAPL",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                qty, new BigDecimal("100.00"), null);
    }

    @Test
    void initialState() {
        Order o = limitBuy(100);
        assertEquals(OrderStatus.NEW, o.getStatus());
        assertEquals(100, o.getOpenQty());
        assertEquals(0,   o.getFilledQty());
        assertEquals(100, o.getTotalQty());
        assertFalse(o.isTriggered());
        assertNotNull(o.getCreateTime());
        assertTrue(o.getInternalId() > 0);
    }

    @Test
    void partialFill() {
        Order o = limitBuy(100);
        o.fill(30);
        assertEquals(OrderStatus.PARTIALLY_FILLED, o.getStatus());
        assertEquals(70, o.getOpenQty());
        assertEquals(30, o.getFilledQty());
        assertEquals(100, o.getTotalQty());
    }

    @Test
    void completeFill() {
        Order o = limitBuy(100);
        o.fill(100);
        assertEquals(OrderStatus.FILLED, o.getStatus());
        assertEquals(0,   o.getOpenQty());
        assertEquals(100, o.getFilledQty());
        assertEquals(100, o.getTotalQty());
    }

    @Test
    void multistepFillReachesFilledAtEnd() {
        Order o = limitBuy(100);
        o.fill(40);
        assertEquals(OrderStatus.PARTIALLY_FILLED, o.getStatus());
        o.fill(60);
        assertEquals(OrderStatus.FILLED, o.getStatus());
        assertEquals(100, o.getFilledQty());
    }

    @Test
    void cancel() {
        Order o = limitBuy(100);
        o.fill(30);
        o.cancel();
        assertEquals(OrderStatus.CANCELLED, o.getStatus());
        assertEquals(0, o.getOpenQty());
        assertEquals(30, o.getFilledQty());
    }

    @Test
    void triggerSetsFlag() {
        Order o = new Order("sess", "C2", null, "AAPL",
                OrderSide.BUY, OrderType.STOP, TimeInForce.GTC,
                100, null, new BigDecimal("105.00"));
        assertFalse(o.isTriggered());
        o.trigger();
        assertTrue(o.isTriggered());
    }

    @Test
    void updateQtyChangesOpenQty() {
        Order o = limitBuy(100);
        o.updateQty(50);
        assertEquals(50, o.getOpenQty());
        assertEquals(50, o.getTotalQty()); // filled=0, open=50
    }

    @Test
    void setStatusDirectly() {
        Order o = limitBuy(100);
        o.setStatus(OrderStatus.PENDING_CANCEL);
        assertEquals(OrderStatus.PENDING_CANCEL, o.getStatus());
    }

    @Test
    void accessorsReturnConstructorValues() {
        Order o = new Order("sess:id", "CL-99", "CL-88", "MSFT",
                OrderSide.SELL, OrderType.STOP_LIMIT, TimeInForce.GTC,
                200, new BigDecimal("50.00"), new BigDecimal("49.00"));

        assertEquals("sess:id",              o.getSessionId());
        assertEquals("CL-99",               o.getClOrdId());
        assertEquals("CL-88",               o.getOrigClOrdId());
        assertEquals("MSFT",                o.getSymbol());
        assertEquals(OrderSide.SELL,        o.getSide());
        assertEquals(OrderType.STOP_LIMIT,  o.getType());
        assertEquals(TimeInForce.GTC,       o.getTif());
        assertEquals(new BigDecimal("50.00"), o.getLimitPrice());
        assertEquals(new BigDecimal("49.00"), o.getStopPrice());
    }

    @Test
    void toStringNotNull() {
        assertNotNull(limitBuy(100).toString());
    }

    @Test
    void uniqueInternalIds() {
        Order a = limitBuy(10);
        Order b = limitBuy(10);
        assertNotEquals(a.getInternalId(), b.getInternalId());
    }
}
