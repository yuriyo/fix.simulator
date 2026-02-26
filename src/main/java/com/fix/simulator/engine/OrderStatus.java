package com.fix.simulator.engine;

/** FIX OrdStatus (tag 39). */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    PENDING_CANCEL,
    REJECTED,
    PENDING_REPLACE
}
