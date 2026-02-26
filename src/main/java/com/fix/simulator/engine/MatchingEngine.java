package com.fix.simulator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central matching engine.
 *
 * <p>Holds one {@link OrderBook} per symbol and a shared {@link MarketState}.
 * All incoming order operations (new / cancel-replace / cancel) flow through here.
 *
 * <p>The engine is stateless regarding FIX sessions – it works with plain {@link Order}
 * objects and returns {@link Execution} lists that callers translate to FIX messages.
 */
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
    private final MarketState marketState = new MarketState();

    // ──────────────────────────────────────────────────────────────────────
    //  Symbol management
    // ──────────────────────────────────────────────────────────────────────

    public void registerSymbol(String symbol) {
        books.putIfAbsent(symbol, new OrderBook(symbol));
        log.info("Registered symbol: {}", symbol);
    }

    public boolean hasSymbol(String symbol) {
        return books.containsKey(symbol);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  New order
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Process a new order.
     *
     * @return list of executions (NEW ack + fills, or REJECT).
     */
    public List<Execution> newOrder(Order order) {
        OrderBook book = books.get(order.getSymbol());
        if (book == null) {
            order.setStatus(OrderStatus.REJECTED);
            return List.of(new Execution(order, 0, null, Execution.ExecType.REJECTED));
        }

        List<Execution> results = new ArrayList<>();

        // Ack the new order
        results.add(new Execution(order, 0, null, Execution.ExecType.NEW));

        switch (order.getType()) {
            case LIMIT -> results.addAll(processLimit(book, order));
            case MARKET -> results.addAll(processMarket(book, order));
            case STOP, STOP_LIMIT -> processStop(book, order, results);
            default -> {
                order.setStatus(OrderStatus.REJECTED);
                results.add(new Execution(order, 0, null, Execution.ExecType.REJECTED));
            }
        }

        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Cancel
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Cancel an existing order.
     *
     * @return execution list (CANCELLED or REJECTED).
     */
    public List<Execution> cancelOrder(String sessionId, String origClOrdId,
                                       String newClOrdId, String symbol) {
        OrderBook book = books.get(symbol);
        if (book == null)
            return reject(origClOrdId, "Unknown symbol");

        Order order = book.findByClientKey(sessionId, origClOrdId);
        if (order == null)
            return reject(origClOrdId, "Order not found");

        if (order.getStatus() == OrderStatus.FILLED ||
                order.getStatus() == OrderStatus.CANCELLED) {
            return reject(origClOrdId, "Order already done");
        }

        // Remove from book
        if (order.getType() == OrderType.LIMIT) {
            book.removeLimit(order);
        } else {
            book.removeStop(order);
        }

        book.deindex(order);
        order.cancel();

        return List.of(new Execution(order, 0, null, Execution.ExecType.CANCELLED));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Cancel-Replace (Modify)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Replace an existing order with new parameters.
     * Only qty and limit price changes are supported. Side/symbol cannot change.
     *
     * @return execution list (REPLACED ack + any fills from the remainder).
     */
    public List<Execution> replaceOrder(String sessionId, String origClOrdId,
                                        Order replacement) {
        OrderBook book = books.get(replacement.getSymbol());
        if (book == null)
            return reject(origClOrdId, "Unknown symbol");

        Order existing = book.findByClientKey(sessionId, origClOrdId);
        if (existing == null)
            return reject(origClOrdId, "Original order not found");

        if (existing.getStatus() == OrderStatus.FILLED ||
                existing.getStatus() == OrderStatus.CANCELLED) {
            return reject(origClOrdId, "Cannot replace completed order");
        }

        // Remove the old order
        if (existing.getType() == OrderType.LIMIT) {
            book.removeLimit(existing);
        } else {
            book.removeStop(existing);
        }
        book.deindex(existing);
        existing.cancel(); // mark old order cancelled

        List<Execution> results = new ArrayList<>();
        // Ack REPLACE on existing order
        results.add(new Execution(existing, 0, null, Execution.ExecType.REPLACED));

        // Process the replacement order as new
        results.addAll(newOrder(replacement));
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Quote (client-driven market data)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Update market state from a client Quote message.
     * The quote is inserted as resting limit orders on both sides so it
     * participates in matching.
     *
     * @return execution list from any immediate matches (unlikely for quotes, but possible).
     */
    public List<Execution> onQuote(String sessionId, String symbol,
                                   BigDecimal bidPrice, long bidSize,
                                   BigDecimal askPrice, long askSize,
                                   String quoteId) {
        // Update NBBO market state
        marketState.update(symbol, bidPrice, bidSize, askPrice, askSize);

        OrderBook book = books.get(symbol);
        if (book == null) return Collections.emptyList();

        List<Execution> fills = new ArrayList<>();

        // Insert bid as a resting BUY limit order
        if (bidPrice != null && bidSize > 0) {
            Order bid = new Order(sessionId, "q-bid-" + quoteId, null, symbol,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                    bidSize, bidPrice, null);
            book.addLimitOrder(bid);
            fills.addAll(book.match(bid));
            if (bid.getOpenQty() > 0)
                book.addLimitOrder(bid); // re-add any remainder
        }

        // Insert ask as a resting SELL limit order
        if (askPrice != null && askSize > 0) {
            Order ask = new Order(sessionId, "q-ask-" + quoteId, null, symbol,
                    OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    askSize, askPrice, null);
            book.addLimitOrder(ask);
            fills.addAll(book.match(ask));
            if (ask.getOpenQty() > 0)
                book.addLimitOrder(ask);
        }

        return fills;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Getters
    // ──────────────────────────────────────────────────────────────────────

    public MarketState getMarketState() { return marketState; }

    public OrderBook getBook(String symbol) { return books.get(symbol); }

    // ──────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private List<Execution> processLimit(OrderBook book, Order order) {
        List<Execution> fills = new ArrayList<>();

        // Try to match immediately
        fills.addAll(book.match(order));

        // If it's IOC and there's leftover – already cancelled inside match()
        // If it's canceled post-match, do not add to book
        if (order.getStatus() != OrderStatus.CANCELLED && order.getOpenQty() > 0) {
            book.addLimitOrder(order);
        }

        afterFills(book, order.getSymbol(), fills);
        return fills;
    }

    private List<Execution> processMarket(OrderBook book, Order order) {
        List<Execution> fills = new ArrayList<>(book.match(order));
        if (order.getOpenQty() > 0) {
            // Cancel unfillable remainder of a market order
            order.cancel();
            fills.add(new Execution(order, 0, null, Execution.ExecType.CANCELLED));
        }
        afterFills(book, order.getSymbol(), fills);
        return fills;
    }

    private void processStop(OrderBook book, Order order, List<Execution> results) {
        BigDecimal lastPrice = marketState.getLastPrice(order.getSymbol());

        // Check whether stop is already triggered at receipt time
        boolean triggered = isImmediatelyTriggered(order, lastPrice);
        if (triggered) {
            order.trigger();
            results.addAll(processTrigger(book, order));
        } else {
            book.addStopOrder(order);
        }
    }

    /**
     * Called after fills to update last price and re-check pending stops.
     */
    private void afterFills(OrderBook book, String symbol, List<Execution> fills) {
        // Find last actual fill price
        BigDecimal lastFillPx = null;
        for (Execution e : fills) {
            if (e.getPrice() != null) lastFillPx = e.getPrice();
        }
        if (lastFillPx != null) {
            marketState.updateLastPrice(symbol, lastFillPx);
            // Trigger any stops
            List<Order> triggered = book.checkStops(lastFillPx);
            for (Order stop : triggered) {
                fills.addAll(processTrigger(book, stop));
            }
        }
    }

    /**
     * Process a triggered STOP or STOP_LIMIT order by converting it to a
     * MARKET or LIMIT order and running matching.
     */
    private List<Execution> processTrigger(OrderBook book, Order stop) {
        List<Execution> results = new ArrayList<>();
        if (stop.getType() == OrderType.STOP) {
            // Becomes a market order – match immediately
            results.addAll(processMarket(book, stop));
        } else { // STOP_LIMIT
            // Becomes a limit order at limitPrice
            results.addAll(processLimit(book, stop));
        }
        return results;
    }

    private boolean isImmediatelyTriggered(Order stop, BigDecimal lastPrice) {
        if (lastPrice == null) return false;
        BigDecimal stopPx = stop.getStopPrice();
        if (stopPx == null) return false;
        if (stop.getSide() == OrderSide.BUY)
            return lastPrice.compareTo(stopPx) >= 0;
        else
            return lastPrice.compareTo(stopPx) <= 0;
    }

    private List<Execution> reject(String clOrdId, String reason) {
        log.warn("Rejecting order {}: {}", clOrdId, reason);
        // We return an empty list; caller creates OrderCancelReject
        return Collections.emptyList();
    }
}
