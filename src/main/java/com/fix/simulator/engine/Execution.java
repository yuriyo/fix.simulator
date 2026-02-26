package com.fix.simulator.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single fill / partial fill resulting from matching.
 */
public class Execution {

    private static final AtomicLong SEQ = new AtomicLong(1);

    private final String execId;
    private final Order  order;
    private final long   qty;
    private final BigDecimal price;
    private final ExecType execType;
    private final Instant time;

    public enum ExecType {
        NEW,
        PARTIAL_FILL,
        FILL,
        CANCELLED,
        REPLACED,
        REJECTED,
        PENDING_CANCEL,
        PENDING_REPLACE
    }

    public Execution(Order order, long qty, BigDecimal price, ExecType execType) {
        this.execId   = "E" + SEQ.getAndIncrement();
        this.order    = order;
        this.qty      = qty;
        this.price    = price;
        this.execType = execType;
        this.time     = Instant.now();
    }

    public String   getExecId()   { return execId; }
    public Order    getOrder()    { return order; }
    public long     getQty()      { return qty; }
    public BigDecimal getPrice()  { return price; }
    public ExecType getExecType() { return execType; }
    public Instant  getTime()     { return time; }
}
