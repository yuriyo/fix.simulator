package com.fix.simulator.engine;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lifetime count of orders in each status. Updated on every status change, so it is not
 * limited by the retained order history. Lock-free.
 */
final class OrderStatusCounts {

    private final AtomicLongArray counts = new AtomicLongArray(OrderStatus.values().length);

    /** An order was accepted into the engine with its initial status. */
    void onRegister(OrderStatus initial) {
        counts.incrementAndGet(initial.ordinal());
    }

    /** An accepted order moved from one status to another. */
    void onChange(OrderStatus from, OrderStatus to) {
        counts.decrementAndGet(from.ordinal());
        counts.incrementAndGet(to.ordinal());
    }

    long get(OrderStatus status) {
        return counts.get(status.ordinal());
    }

    /** Every order accepted since start. */
    long total() {
        long sum = 0;
        for (int i = 0; i < counts.length(); i++) sum += counts.get(i);
        return sum;
    }

    /** NEW and PARTIALLY_FILLED. */
    long open() {
        return get(OrderStatus.NEW) + get(OrderStatus.PARTIALLY_FILLED);
    }
}
