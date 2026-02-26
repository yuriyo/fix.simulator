package com.fix.simulator.engine;

/**
 * Supported FIX OrdType (tag 40) values.
 */
public enum OrderType {
    LIMIT,          // 2
    STOP,           // 3  – becomes market order when stop price is hit
    STOP_LIMIT,     // 4  – becomes limit order when stop price is hit
    MARKET          // 1  – used internally after stop trigger; not accepted directly
}
