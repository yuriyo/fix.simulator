package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarketState – NBBO tracking and last-price updates")
class MarketStateTest {

    private MarketState state;

    @BeforeEach
    void setUp() {
        state = new MarketState();
    }

    // ── getLastPrice ────────────────────────────────────────────────────────

    @Test
    void getLastPriceBeforeAnyUpdate_returnsNull() {
        assertNull(state.getLastPrice("AAPL"));
    }

    @Test
    void getBeforeAnyUpdate_returnsNull() {
        assertNull(state.get("AAPL"));
    }

    // ── update (quote) ──────────────────────────────────────────────────────

    @Test
    void updateCreatesEntry() {
        state.update("AAPL", new BigDecimal("99.00"), 100, new BigDecimal("101.00"), 200);

        MarketState.Quote q = state.get("AAPL");
        assertNotNull(q);
        assertEquals(new BigDecimal("99.00"),  q.bidPrice);
        assertEquals(100,                      q.bidSize);
        assertEquals(new BigDecimal("101.00"), q.askPrice);
        assertEquals(200,                      q.askSize);
    }

    @Test
    void updateSetsLastPriceToMid() {
        state.update("AAPL", new BigDecimal("99.00"), 100, new BigDecimal("101.00"), 200);
        // mid = (99 + 101) / 2 = 100
        assertEquals(new BigDecimal("100.00"), state.getLastPrice("AAPL"));
    }

    @Test
    void updateMergesIntoExistingEntry() {
        state.update("AAPL", new BigDecimal("99.00"), 100, new BigDecimal("101.00"), 200);
        state.update("AAPL", new BigDecimal("98.00"), 50,  new BigDecimal("102.00"), 300);

        MarketState.Quote q = state.get("AAPL");
        assertEquals(new BigDecimal("98.00"),  q.bidPrice);
        assertEquals(50,                       q.bidSize);
        assertEquals(new BigDecimal("102.00"), q.askPrice);
        assertEquals(300,                      q.askSize);
    }

    @Test
    void updateWithNullBid_useAskAsLastPrice() {
        state.update("AAPL", null, 0, new BigDecimal("101.00"), 200);
        assertEquals(new BigDecimal("101.00"), state.getLastPrice("AAPL"));
    }

    @Test
    void updateWithNullAsk_useBidAsLastPrice() {
        state.update("AAPL", new BigDecimal("99.00"), 100, null, 0);
        assertEquals(new BigDecimal("99.00"), state.getLastPrice("AAPL"));
    }

    @Test
    void updateWithBothNull_lastPriceIsZero() {
        state.update("AAPL", null, 0, null, 0);
        assertEquals(BigDecimal.ZERO, state.getLastPrice("AAPL"));
    }

    @Test
    void updateDoesNotOverwriteLastPriceSetByTrade() {
        // Trade fills last price to 100.50; next quote should see bid/ask updated
        // but previous lastPrice is not overwritten by update (merge only updates bid/ask fields)
        state.updateLastPrice("AAPL", new BigDecimal("100.50"));
        state.update("AAPL", new BigDecimal("99.00"), 100, new BigDecimal("101.00"), 200);
        // merge keeps existing lastPrice unless update explicitly modifies it
        // In current implementation the merge lambda doesn't change old.lastPrice
        assertEquals(new BigDecimal("100.50"), state.getLastPrice("AAPL"));
    }

    // ── updateLastPrice ─────────────────────────────────────────────────────

    @Test
    void updateLastPriceCreatesEntryIfAbsent() {
        state.updateLastPrice("AAPL", new BigDecimal("150.00"));
        assertEquals(new BigDecimal("150.00"), state.getLastPrice("AAPL"));
    }

    @Test
    void updateLastPriceOverwritesPrevious() {
        state.updateLastPrice("AAPL", new BigDecimal("100.00"));
        state.updateLastPrice("AAPL", new BigDecimal("101.50"));
        assertEquals(new BigDecimal("101.50"), state.getLastPrice("AAPL"));
    }

    @Test
    void isolationBetweenSymbols() {
        state.updateLastPrice("AAPL", new BigDecimal("150.00"));
        state.updateLastPrice("MSFT", new BigDecimal("300.00"));
        assertEquals(new BigDecimal("150.00"), state.getLastPrice("AAPL"));
        assertEquals(new BigDecimal("300.00"), state.getLastPrice("MSFT"));
        assertNull(state.getLastPrice("GOOG"));
    }
}
