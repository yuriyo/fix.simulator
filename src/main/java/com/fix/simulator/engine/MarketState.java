package com.fix.simulator.engine;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks NBBO (last trade price, bid, ask) per instrument.
 * Used to trigger STOP / STOP_LIMIT orders.
 */
public class MarketState {

    /** Snapshot of market data for a single symbol. */
    public static class Quote {
        public volatile BigDecimal lastPrice;
        public volatile BigDecimal bidPrice;
        public volatile BigDecimal askPrice;
        public volatile long       bidSize;
        public volatile long       askSize;

        public Quote(BigDecimal lastPrice, BigDecimal bidPrice, long bidSize,
                     BigDecimal askPrice, long askSize) {
            this.lastPrice = lastPrice;
            this.bidPrice  = bidPrice;
            this.bidSize   = bidSize;
            this.askPrice  = askPrice;
            this.askSize   = askSize;
        }
    }

    private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();

    /**
     * Update (or create) market data for a symbol.
     * This is called when a client sends a Quote message.
     */
    public void update(String symbol, BigDecimal bidPrice, long bidSize,
                       BigDecimal askPrice, long askSize) {
        quotes.merge(symbol, new Quote(bestMid(bidPrice, askPrice), bidPrice, bidSize, askPrice, askSize),
                (old, q) -> {
                    old.bidPrice = q.bidPrice;
                    old.bidSize  = q.bidSize;
                    old.askPrice = q.askPrice;
                    old.askSize  = q.askSize;
                    return old;
                });
    }

    /** Update last trade price (called by MatchingEngine after a fill). */
    public void updateLastPrice(String symbol, BigDecimal price) {
        quotes.compute(symbol, (k, old) -> {
            if (old == null) old = new Quote(price, price, 0, price, 0);
            old.lastPrice = price;
            return old;
        });
    }

    public Quote get(String symbol) {
        return quotes.get(symbol);
    }

    public BigDecimal getLastPrice(String symbol) {
        Quote q = quotes.get(symbol);
        return q != null ? q.lastPrice : null;
    }

    private BigDecimal bestMid(BigDecimal bid, BigDecimal ask) {
        if (bid == null && ask == null) return BigDecimal.ZERO;
        if (bid == null) return ask;
        if (ask == null) return bid;
        return bid.add(ask).divide(BigDecimal.valueOf(2));
    }
}
