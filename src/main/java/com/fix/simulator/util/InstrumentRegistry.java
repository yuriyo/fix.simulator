package com.fix.simulator.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads tradeable instruments from a YAML config file.
 *
 * <p>Expected file format:
 * <pre>
 * instruments:
 *   - symbol: AAPL
 *     description: "Apple Inc."
 *     lotSize: 1
 *     tickSize: "0.01"
 *   - symbol: MSFT
 *     ...
 * </pre>
 */
public class InstrumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstrumentRegistry.class);

    public record Instrument(String symbol, String description, int lotSize, String tickSize) {}

    private final Map<String, Instrument> instruments = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public void load(String path) throws IOException {
        try (InputStream in = new FileInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("instruments");
            if (list == null) {
                log.warn("No 'instruments' key found in {}", path);
                return;
            }
            for (Map<String, Object> entry : list) {
                String sym  = (String) entry.get("symbol");
                String desc = (String) entry.getOrDefault("description", "");
                int lot     = (int)    entry.getOrDefault("lotSize", 1);
                String tick = String.valueOf(entry.getOrDefault("tickSize", "0.01"));
                instruments.put(sym, new Instrument(sym, desc, lot, tick));
                log.info("Registered instrument: {}", sym);
            }
        }
    }

    public boolean isKnown(String symbol) { return instruments.containsKey(symbol); }

    /** Instrument definition (lot size, tick size) or {@code null} if the symbol is unknown. */
    public Instrument get(String symbol) { return instruments.get(symbol); }

    public Collection<Instrument> all() { return instruments.values(); }

    public Set<String> symbols() { return instruments.keySet(); }
}
