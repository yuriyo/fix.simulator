package com.fix.simulator.admin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of per-symbol trading states.
 * Default state for any registered symbol is {@link InstrumentStatus#OPEN}.
 */
public class InstrumentStateManager {

    private final ConcurrentHashMap<String, InstrumentStatus> states = new ConcurrentHashMap<>();

    /** Returns the current state for {@code symbol}; defaults to {@link InstrumentStatus#OPEN}. */
    public InstrumentStatus getState(String symbol) {
        return states.getOrDefault(symbol, InstrumentStatus.OPEN);
    }

    /** Update the state for {@code symbol}. */
    public void setState(String symbol, InstrumentStatus status) {
        states.put(symbol, status);
    }

    /** Snapshot of all explicitly set states. Symbols not present are {@code OPEN}. */
    public Map<String, InstrumentStatus> snapshot() {
        return Map.copyOf(states);
    }

    /** Expose registered symbols set. */
    public Set<String> symbols() {
        return Set.copyOf(states.keySet());
    }
}
