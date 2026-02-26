package com.fix.simulator.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InstrumentRegistry – YAML loading and lookup")
class InstrumentRegistryTest {

    @TempDir
    Path tmp;

    private Path writeYaml(String content) throws IOException {
        Path p = tmp.resolve("instruments.yml");
        Files.writeString(p, content);
        return p;
    }

    // ── load and lookup ───────────────────────────────────────────────────────

    @Test
    void loadValidYaml_registersSymbols() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                    description: "Apple Inc."
                    lotSize: 1
                    tickSize: "0.01"
                  - symbol: MSFT
                    description: "Microsoft"
                    lotSize: 10
                    tickSize: "0.05"
                """);

        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        assertTrue(reg.isKnown("AAPL"));
        assertTrue(reg.isKnown("MSFT"));
    }

    @Test
    void isKnown_falseForUnregisteredSymbol() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        assertFalse(reg.isKnown("GOOG"));
    }

    @Test
    void symbolsCaseSensitive() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        assertFalse(reg.isKnown("aapl"));
        assertTrue(reg.isKnown("AAPL"));
    }

    @Test
    void allReturnsAllInstruments() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                  - symbol: MSFT
                  - symbol: GOOG
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        Collection<InstrumentRegistry.Instrument> all = reg.all();
        assertEquals(3, all.size());
    }

    @Test
    void symbolsReturnsKeySet() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                  - symbol: MSFT
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        assertTrue(reg.symbols().contains("AAPL"));
        assertTrue(reg.symbols().contains("MSFT"));
        assertEquals(2, reg.symbols().size());
    }

    @Test
    void instrumentFieldsParsedCorrectly() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                    description: "Apple"
                    lotSize: 5
                    tickSize: "0.05"
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        InstrumentRegistry.Instrument inst = reg.all().iterator().next();
        assertEquals("AAPL",  inst.symbol());
        assertEquals("Apple", inst.description());
        assertEquals(5,       inst.lotSize());
        assertEquals("0.05",  inst.tickSize());
    }

    @Test
    void defaultFieldsAppliedWhenMissing() throws IOException {
        Path f = writeYaml("""
                instruments:
                  - symbol: AAPL
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());

        InstrumentRegistry.Instrument inst = reg.all().iterator().next();
        assertEquals(1,      inst.lotSize());
        assertEquals("0.01", inst.tickSize());
        assertEquals("",     inst.description());
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void emptyYaml_noInstrumentsKey_loads() throws IOException {
        Path f = writeYaml("{}");
        InstrumentRegistry reg = new InstrumentRegistry();
        assertDoesNotThrow(() -> reg.load(f.toString()));
        assertTrue(reg.symbols().isEmpty());
    }

    @Test
    void emptyInstrumentsList_loadsCleanly() throws IOException {
        Path f = writeYaml("""
                instruments: []
                """);
        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f.toString());
        assertTrue(reg.symbols().isEmpty());
    }

    @Test
    void load_missingFile_throwsIOException() {
        InstrumentRegistry reg = new InstrumentRegistry();
        assertThrows(IOException.class, () -> reg.load("/nonexistent/path/instruments.yml"));
    }

    @Test
    void multipleLoads_symbolsAccumulate() throws IOException {
        Path f1 = tmp.resolve("f1.yml");
        Path f2 = tmp.resolve("f2.yml");
        Files.writeString(f1, "instruments:\n  - symbol: AAPL\n");
        Files.writeString(f2, "instruments:\n  - symbol: MSFT\n");

        InstrumentRegistry reg = new InstrumentRegistry();
        reg.load(f1.toString());
        reg.load(f2.toString());

        assertTrue(reg.isKnown("AAPL"));
        assertTrue(reg.isKnown("MSFT"));
    }
}
