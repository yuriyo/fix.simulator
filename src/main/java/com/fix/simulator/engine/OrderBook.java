package com.fix.simulator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central limit order book for a single instrument.
 *
 * <p>Thread model: all public methods that mutate state are {@code synchronized}
 * on {@code this}. This gives simple correctness; throughput is per-symbol
 * so contention is minimal at realistic rates (2–10K msg/s total across many symbols).
 *
 * <p>Buy side: TreeMap descending by price  (best bid = first entry).
 * Sell side: TreeMap ascending  by price  (best ask = first entry).
 * Within each price level orders are kept in FIFO arrival order.
 */
public class OrderBook {

    private static final Logger log = LoggerFactory.getLogger(OrderBook.class);

    private final String symbol;

    // Price → FIFO queue of resting orders
    private final TreeMap<BigDecimal, ArrayDeque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder()); // highest bid first
    private final TreeMap<BigDecimal, ArrayDeque<Order>> asks =
            new TreeMap<>();                           // lowest ask first

    // Lookup by internal order id and by (sessionId, clOrdId)
    private final Map<Long,   Order> byInternalId = new HashMap<>();
    private final Map<String, Order> byClientKey  = new HashMap<>(); // sessionId+":"+clOrdId

    // Pending STOP and STOP_LIMIT orders (held until price trigger)
    private final List<Order> pendingStops = new ArrayList<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() { return symbol; }

    // ──────────────────────────────────────────────────────────────────────
    //  Order registration (called BEFORE matching; adds to resting book)
    // ──────────────────────────────────────────────────────────────────────

    /** Add a resting limit order to the book. Does NOT run matching. */
    public synchronized void addLimitOrder(Order order) {
        if (order.getLimitPrice() == null)
            throw new IllegalArgumentException("Limit order requires a price: " + order);
        TreeMap<BigDecimal, ArrayDeque<Order>> side = sideMap(order.getSide());
        side.computeIfAbsent(order.getLimitPrice(), k -> new ArrayDeque<>()).addLast(order);
        index(order);
    }

    /** Park a STOP / STOP_LIMIT order; it will be triggered by the engine later. */
    public synchronized void addStopOrder(Order order) {
        pendingStops.add(order);
        index(order);
    }

    /** Remove a resting limit order from the book (cancel / replace). */
    public synchronized boolean removeLimit(Order order) {
        TreeMap<BigDecimal, ArrayDeque<Order>> side = sideMap(order.getSide());
        ArrayDeque<Order> level = side.get(order.getLimitPrice());
        if (level == null) return false;
        boolean removed = level.remove(order);
        if (level.isEmpty()) side.remove(order.getLimitPrice());
        return removed;
    }

    /** Remove a stop order from the pending list. */
    public synchronized boolean removeStop(Order order) {
        return pendingStops.remove(order);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Lookup
    // ──────────────────────────────────────────────────────────────────────

    public synchronized Order findByClientKey(String sessionId, String clOrdId) {
        return byClientKey.get(sessionId + ":" + clOrdId);
    }

    public synchronized Order findById(long internalId) {
        return byInternalId.get(internalId);
    }

    public synchronized void deindex(Order order) {
        byInternalId.remove(order.getInternalId());
        byClientKey.remove(order.getSessionId() + ":" + order.getClOrdId());
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Matching  (Price-Time FIFO)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Attempt to match {@code aggressor} against the resting book.
     *
     * @return list of Execution objects (may be empty). The caller is responsible for
     *         sending execution reports back to both sides.
     */
    public synchronized List<Execution> match(Order aggressor) {
        List<Execution> fills = new ArrayList<>();

        TreeMap<BigDecimal, ArrayDeque<Order>> restingSide =
                (aggressor.getSide() == OrderSide.BUY) ? asks : bids;

        // FOK: all-or-nothing – cancel immediately if the full qty cannot be filled
        if (aggressor.getTif() == TimeInForce.FOK) {
            if (availableLiquidityFor(aggressor) < aggressor.getOpenQty()) {
                aggressor.cancel();
                return List.of(new Execution(aggressor, 0, null, Execution.ExecType.CANCELLED));
            }
        }

        long remaining = aggressor.getOpenQty();

        while (remaining > 0 && !restingSide.isEmpty()) {
            Map.Entry<BigDecimal, ArrayDeque<Order>> bestLevel = restingSide.firstEntry();
            BigDecimal bestPx = bestLevel.getKey();

            // Price check for limit orders
            if (aggressor.getType() == OrderType.LIMIT || aggressor.getType() == OrderType.STOP_LIMIT) {
                if (!priceMatches(aggressor.getSide(), aggressor.getLimitPrice(), bestPx))
                    break; // no match
            }
            // MARKET (or triggered STOP) orders match any price

            ArrayDeque<Order> queue = bestLevel.getValue();
            while (!queue.isEmpty() && remaining > 0) {
                Order passive = queue.peekFirst();
                long fillQty = Math.min(remaining, passive.getOpenQty());
                BigDecimal fillPx = bestPx; // price improvement is passive side's price

                // Fill both sides
                aggressor.fill(fillQty);
                passive.fill(fillQty);
                remaining = aggressor.getOpenQty();

                fills.add(new Execution(aggressor, fillQty, fillPx, execType(aggressor)));
                fills.add(new Execution(passive,   fillQty, fillPx, execType(passive)));

                if (passive.getOpenQty() == 0) {
                    queue.pollFirst();
                    deindex(passive);
                }
            }
            if (queue.isEmpty()) restingSide.remove(bestPx);
        }

        // IOC: cancel unfilled remainder
        if (aggressor.getTif() == TimeInForce.IOC && aggressor.getOpenQty() > 0) {
            aggressor.cancel();
            fills.add(new Execution(aggressor, 0, null, Execution.ExecType.CANCELLED));
        }

        return fills;
    }

    /**
     * Scan pending stops against the new last trade price.
     * Returns newly triggered orders (caller must then re-add them to the
     * book as limit/market orders and run matching).
     */
    public synchronized List<Order> checkStops(BigDecimal lastPrice) {
        if (lastPrice == null) return Collections.emptyList();
        List<Order> triggered = new ArrayList<>();
        Iterator<Order> it = pendingStops.iterator();
        while (it.hasNext()) {
            Order s = it.next();
            if (isStopTriggered(s, lastPrice)) {
                s.trigger();
                it.remove();
                triggered.add(s);
            }
        }
        return triggered;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Book snapshot (for logging / debugging)
    // ──────────────────────────────────────────────────────────────────────

    public synchronized BigDecimal bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public synchronized BigDecimal bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private TreeMap<BigDecimal, ArrayDeque<Order>> sideMap(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    private void index(Order order) {
        byInternalId.put(order.getInternalId(), order);
        byClientKey.put(order.getSessionId() + ":" + order.getClOrdId(), order);
    }

    private boolean priceMatches(OrderSide aggressorSide, BigDecimal limit, BigDecimal restingPx) {
        if (aggressorSide == OrderSide.BUY)
            return limit.compareTo(restingPx) >= 0; // buyer willing to pay at least restingPx
        else
            return limit.compareTo(restingPx) <= 0; // seller willing to receive at most restingPx
    }

    private boolean isStopTriggered(Order stop, BigDecimal lastPrice) {
        BigDecimal stopPx = stop.getStopPrice();
        if (stopPx == null) return false;
        if (stop.getSide() == OrderSide.BUY)
            return lastPrice.compareTo(stopPx) >= 0; // buy stop triggered on price rise
        else
            return lastPrice.compareTo(stopPx) <= 0; // sell stop triggered on price fall
    }

    private Execution.ExecType execType(Order order) {
        return order.getOpenQty() == 0
                ? Execution.ExecType.FILL
                : Execution.ExecType.PARTIAL_FILL;
    }

    /**
     * Dry-run: count marketable shares on the opposite side without modifying any state.
     * Used for the FOK all-or-nothing pre-check.
     */
    private long availableLiquidityFor(Order aggressor) {
        TreeMap<BigDecimal, ArrayDeque<Order>> restingSide =
                (aggressor.getSide() == OrderSide.BUY) ? asks : bids;
        long available = 0;
        for (Map.Entry<BigDecimal, ArrayDeque<Order>> entry : restingSide.entrySet()) {
            BigDecimal px = entry.getKey();
            if (aggressor.getType() == OrderType.LIMIT || aggressor.getType() == OrderType.STOP_LIMIT) {
                if (!priceMatches(aggressor.getSide(), aggressor.getLimitPrice(), px))
                    break; // levels are sorted; no further levels can match
            }
            for (Order o : entry.getValue()) {
                available += o.getOpenQty();
            }
        }
        return available;
    }
}
