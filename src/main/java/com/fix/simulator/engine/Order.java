package com.fix.simulator.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single order in the book.
 * Immutable identity fields (clOrdId, symbol, side, type); mutable state (qty, status).
 */
public class Order {

    private static final AtomicLong SEQ = new AtomicLong(1);

    // Immutable identity
    private final long internalId;           // server-assigned unique id
    private final String sessionId;          // QuickFIX SessionID.toString()
    private final String clOrdId;            // client's ClOrdID (tag 11)
    private final String origClOrdId;        // chain: previous ClOrdID (for replaces)
    private final String symbol;             // tag 55
    private final OrderSide side;            // tag 54
    private final OrderType type;            // tag 40
    private final TimeInForce tif;           // tag 59
    private final BigDecimal limitPrice;     // tag 44  (null for MARKET/STOP)
    private final BigDecimal stopPrice;      // tag 99  (STOP / STOP_LIMIT)
    private final Instant createTime;

    // Mutable state (guarded by OrderBook lock)
    private volatile long openQty;
    private volatile long filledQty;
    private volatile OrderStatus status;
    private volatile boolean triggered;      // for STOP / STOP_LIMIT

    /** Receives every status change. Set by the engine so it can keep lifetime counts. */
    public interface StatusObserver {
        void onChange(OrderStatus from, OrderStatus to);
    }
    private volatile StatusObserver observer;

    public Order(String sessionId, String clOrdId, String origClOrdId,
                 String symbol, OrderSide side, OrderType type, TimeInForce tif,
                 long qty, BigDecimal limitPrice, BigDecimal stopPrice) {
        this.internalId  = SEQ.getAndIncrement();
        this.sessionId   = sessionId;
        this.clOrdId     = clOrdId;
        this.origClOrdId = origClOrdId;
        this.symbol      = symbol;
        this.side        = side;
        this.type        = type;
        this.tif         = tif;
        this.openQty     = qty;
        this.filledQty   = 0;
        this.limitPrice  = limitPrice;
        this.stopPrice   = stopPrice;
        this.status      = OrderStatus.NEW;
        this.triggered   = false;
        this.createTime  = Instant.now();
    }

    // ---- accessors ----

    public long getInternalId()      { return internalId; }
    public String getSessionId()     { return sessionId; }
    public String getClOrdId()       { return clOrdId; }
    public String getOrigClOrdId()   { return origClOrdId; }
    public String getSymbol()        { return symbol; }
    public OrderSide getSide()       { return side; }
    public OrderType getType()       { return type; }
    public TimeInForce getTif()      { return tif; }
    public BigDecimal getLimitPrice(){ return limitPrice; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public Instant getCreateTime()   { return createTime; }

    public long getOpenQty()         { return openQty; }
    public long getFilledQty()       { return filledQty; }
    public long getTotalQty()        { return openQty + filledQty; }
    public OrderStatus getStatus()   { return status; }
    public boolean isTriggered()     { return triggered; }

    // ---- mutable operations ----

    public void setStatusObserver(StatusObserver observer) {
        this.observer = observer;
    }

    public void fill(long qty) {
        this.openQty   -= qty;
        this.filledQty += qty;
        changeStatus((openQty == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
    }

    public void cancel() {
        changeStatus(OrderStatus.CANCELLED);
        this.openQty = 0;
    }

    /** Set the status and tell the observer about a real change. */
    private void changeStatus(OrderStatus next) {
        OrderStatus prev = this.status;
        this.status = next;
        StatusObserver o = this.observer;
        if (o != null && prev != next) o.onChange(prev, next);
    }

    public void trigger() {
        this.triggered = true;
    }

    public void updateQty(long newOpenQty) {
        this.openQty = newOpenQty;
    }

    public void setStatus(OrderStatus status) {
        changeStatus(status);
    }

    @Override
    public String toString() {
        return String.format("Order{id=%d, clOrdId=%s, sym=%s, %s %s qty=%d/%d px=%s stop=%s tif=%s status=%s}",
                internalId, clOrdId, symbol, side, type, openQty, filledQty,
                limitPrice, stopPrice, tif, status);
    }
}
