package com.fix.simulator.engine;

/** FIX TimeInForce (tag 59). */
public enum TimeInForce {
    DAY,    // 0
    GTC,    // 1 – Good Till Cancel
    IOC,    // 3 – Immediate or Cancel
    FOK     // 4 – Fill or Kill
}
