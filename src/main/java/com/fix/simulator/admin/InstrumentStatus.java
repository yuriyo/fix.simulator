package com.fix.simulator.admin;

/**
 * Trading status for a symbol, controllable via the admin REST API.
 *
 * <ul>
 *   <li>{@link #OPEN}        – Normal trading; all order types accepted.</li>
 *   <li>{@link #HALT}        – Trading suspended; all new FIX orders (NewOrderSingle,
 *                              Quote, MassQuote) are rejected.  Resting orders are
 *                              preserved in the book.</li>
 *   <li>{@link #QUOTES_ONLY} – Only {@code Quote (S)} and {@code MassQuote (i)} are
 *                              accepted; regular {@code NewOrderSingle (D)} orders are
 *                              rejected.</li>
 * </ul>
 */
public enum InstrumentStatus {
    OPEN,
    HALT,
    QUOTES_ONLY
}
