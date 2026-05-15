package com.fix.simulator.engine;

import com.fix.simulator.admin.EventBus;
import com.fix.simulator.admin.InstrumentStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    // ── Admin / monitoring (optional – injected via setters after construction) ──
    private volatile EventBus               eventBus;
    private volatile InstrumentStateManager stateManager;

    /** Master order register: every order ever submitted, keyed by internalId. */
    private final ConcurrentHashMap<Long, Order> allOrders = new ConcurrentHashMap<>();

    /** Trade log: one record per matched aggressor fill. */
    private final List<Map<String, Object>> tradeLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Execution log: every execution report (NEW, FILL, PARTIAL_FILL, CANCELLED, etc.). */
    private final List<Map<String, Object>> executionLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    // ──────────────────────────────────────────────────────────────────────
    //  Admin wiring (optional setters – no constructor change keeps tests green)
    // ──────────────────────────────────────────────────────────────────────

    public void setEventBus(EventBus eventBus)                 { this.eventBus     = eventBus; }
    public void setStateManager(InstrumentStateManager sm)     { this.stateManager = sm; }

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
            Execution rej = new Execution(order, 0, null, Execution.ExecType.REJECTED);
            logExecution(rej);
            return List.of(rej);
        }

        // Register in master order map
        allOrders.put(order.getInternalId(), order);

        List<Execution> results = new ArrayList<>();

        // Ack the new order
        Execution newExec = new Execution(order, 0, null, Execution.ExecType.NEW);
        logExecution(newExec);
        results.add(newExec);

        switch (order.getType()) {
            case LIMIT -> results.addAll(processLimit(book, order));
            case MARKET -> results.addAll(processMarket(book, order));
            case STOP, STOP_LIMIT -> processStop(book, order, results);
            default -> {
                order.setStatus(OrderStatus.REJECTED);
                Execution rej = new Execution(order, 0, null, Execution.ExecType.REJECTED);
                logExecution(rej);
                results.add(rej);
            }
        }

        publishOrderEvent(order);
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

        publishOrderEvent(order);
        Execution cancelExec = new Execution(order, 0, null, Execution.ExecType.CANCELLED);
        logExecution(cancelExec);

        // Publish book snapshot so the UI reflects the removed order
        EventBus bus = this.eventBus;
        if (bus != null) {
            OrderBook cancelBook = books.get(symbol);
            if (cancelBook != null) {
                Map<String, Object> bookEvt = new LinkedHashMap<>();
                bookEvt.put("type",   "book_update");
                bookEvt.put("symbol", symbol);
                bookEvt.put("bids",   cancelBook.getBidLevels(100));
                bookEvt.put("asks",   cancelBook.getAskLevels(100));
                bus.publish(bookEvt);
            }
        }

        return List.of(cancelExec);
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
        Execution replacedExec = new Execution(existing, 0, null, Execution.ExecType.REPLACED);
        logExecution(replacedExec);
        results.add(replacedExec);

        // Process the replacement order as new (newOrder logs its own executions)
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

    /** All registered symbols. */
    public Set<String> getSymbols() { return Collections.unmodifiableSet(books.keySet()); }

    /** Find any order (active or historical) by its internal server-assigned id. */
    public Order findOrderById(long id) { return allOrders.get(id); }

    /** Trade log (one record per aggressor fill). Filterable by symbol. */
    public List<Map<String, Object>> getTradeLog(String symbol) {
        if (symbol == null || symbol.isEmpty()) return Collections.unmodifiableList(tradeLog);
        return tradeLog.stream()
                .filter(t -> symbol.equals(t.get("symbol")))
                .collect(Collectors.toList());
    }

    /** Symbols that have at least one completed trade. */
    public Set<String> getTradedSymbols() {
        Set<String> s = new java.util.LinkedHashSet<>();
        for (Map<String, Object> t : tradeLog) s.add((String) t.get("symbol"));
        return Collections.unmodifiableSet(s);
    }

    /** Execution log – all execution reports. Filterable by symbol and clientId substring. */
    public List<Map<String, Object>> getExecutionLog(String symbol, String clientId) {
        return executionLog.stream()
                .filter(e -> symbol == null || symbol.isEmpty()   || symbol.equals(e.get("symbol")))
                .filter(e -> clientId == null || clientId.isEmpty() ||
                             ((String) e.getOrDefault("sessionId", "")).contains(clientId))
                .collect(Collectors.toList());
    }

    /** All resting (active) orders across every symbol. */
    public List<Order> getAllActiveOrders() {
        List<Order> result = new ArrayList<>();
        for (OrderBook book : books.values()) result.addAll(book.getActiveOrders());
        return result;
    }

    /** Resting orders for a specific symbol. */
    public List<Order> getAllActiveOrders(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? Collections.emptyList() : book.getActiveOrders();
    }

    /**
     * Orders that have reached a terminal state (FILLED, CANCELLED, REJECTED)
     * for a given symbol (or all symbols when {@code symbol} is {@code null}).
     */
    public List<Order> getCompletedOrders(String symbol) {
        return allOrders.values().stream()
                .filter(o -> symbol == null || o.getSymbol().equals(symbol))
                .filter(o -> o.getStatus() == OrderStatus.FILLED
                          || o.getStatus() == OrderStatus.CANCELLED
                          || o.getStatus() == OrderStatus.REJECTED)
                .collect(Collectors.toList());
    }

    /**
     * Aggregate order statistics for a single FIX session.
     *
     * @param sessionId  the QuickFIX session-id string
     * @return map with keys: orders, open, cancelled, filled
     */
    public Map<String, Integer> getOrderStats(String sessionId) {
        int orders = 0, open = 0, cancelled = 0, filled = 0;
        for (Order o : allOrders.values()) {
            if (!sessionId.equals(o.getSessionId())) continue;
            orders++;
            OrderStatus st = o.getStatus();
            if (st == OrderStatus.NEW || st == OrderStatus.PARTIALLY_FILLED) open++;
            else if (st == OrderStatus.CANCELLED) cancelled++;
            else if (st == OrderStatus.FILLED)    filled++;
        }
        return Map.of("orders", orders, "open", open, "cancelled", cancelled, "filled", filled);
    }

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

        afterFills(book, order, fills);
        return fills;
    }

    private List<Execution> processMarket(OrderBook book, Order order) {
        List<Execution> fills = new ArrayList<>(book.match(order));
        if (order.getOpenQty() > 0) {
            // Cancel unfillable remainder of a market order
            order.cancel();
            Execution cancelExec = new Execution(order, 0, null, Execution.ExecType.CANCELLED);
            logExecution(cancelExec);
            fills.add(cancelExec);
        }
        afterFills(book, order, fills);
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
     * Called after fills to update last price, re-check pending stops,
     * log executions and trades, and publish WebSocket events.
     *
     * @param aggressor the incoming order that caused the match
     * @param fills     all executions produced by matching
     */
    private void afterFills(OrderBook book, Order aggressor, List<Execution> fills) {
        String symbol = aggressor.getSymbol();

        // Find last fill price from aggressor fills only
        BigDecimal lastFillPx = null;
        for (Execution e : fills) {
            if (e.getOrder() == aggressor && e.getPrice() != null) lastFillPx = e.getPrice();
        }
        if (lastFillPx != null) {
            marketState.updateLastPrice(symbol, lastFillPx);
            // Trigger any stops
            List<Order> triggered = book.checkStops(lastFillPx);
            for (Order stop : triggered) {
                fills.addAll(processTrigger(book, stop));
            }
        }

        // Log executions and publish WS events
        EventBus bus = this.eventBus;
        boolean hasFills = false;
        for (Execution e : fills) {
            if (e.getPrice() != null && e.getQty() > 0
                    && (e.getExecType() == Execution.ExecType.FILL
                     || e.getExecType() == Execution.ExecType.PARTIAL_FILL)) {
                // Log every fill execution (both aggressor and passive sides)
                logExecution(e);
                hasFills = true;

                // Publish one trade event per aggressor fill (not per passive)
                if (e.getOrder() == aggressor) {
                    Map<String, Object> tradeMap = new LinkedHashMap<>();
                    tradeMap.put("time",         e.getTime().toString());
                    tradeMap.put("symbol",       symbol);
                    tradeMap.put("price",        e.getPrice().toPlainString());
                    tradeMap.put("qty",          e.getQty());
                    tradeMap.put("aggressorSide", aggressor.getSide().name());
                    tradeLog.add(tradeMap);

                    if (bus != null) {
                        Map<String, Object> wsMsg = new LinkedHashMap<>(tradeMap);
                        wsMsg.put("type", "trade");
                        bus.publish(wsMsg);
                    }
                }
            }
        }
        // Book-update snapshot (100 levels each side).
        // Always publish so resting orders (no fill) still appear in the book view.
        if (bus != null) {
            Map<String, Object> bookEvt = new LinkedHashMap<>();
            bookEvt.put("type",   "book_update");
            bookEvt.put("symbol", symbol);
            bookEvt.put("bids",   book.getBidLevels(100));
            bookEvt.put("asks",   book.getAskLevels(100));
            bus.publish(bookEvt);
        }
    }

    /** Append a single execution to the execution log. */
    private void logExecution(Execution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId",    e.getExecId());
        m.put("time",      e.getTime().toString());
        m.put("symbol",    e.getOrder().getSymbol());
        m.put("execType",  e.getExecType().name());
        m.put("side",      e.getOrder().getSide() != null ? e.getOrder().getSide().name() : null);
        m.put("price",     e.getPrice() != null ? e.getPrice().toPlainString() : null);
        m.put("qty",       e.getQty());
        m.put("clOrdId",   e.getOrder().getClOrdId());
        m.put("sessionId", e.getOrder().getSessionId());
        m.put("orderId",   e.getOrder().getInternalId());
        executionLog.add(m);
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

    private void publishOrderEvent(Order order) {
        EventBus bus = this.eventBus;
        if (bus == null) return;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type",      "order_event");
        evt.put("orderId",   order.getInternalId());
        evt.put("clOrdId",   order.getClOrdId());
        evt.put("sessionId", order.getSessionId());
        evt.put("symbol",    order.getSymbol());
        evt.put("side",      order.getSide().name());
        evt.put("status",    order.getStatus().name());
        evt.put("openQty",   order.getOpenQty());
        evt.put("filledQty", order.getFilledQty());
        bus.publish(evt);
    }

    // ── Admin book management ─────────────────────────────────────────────────

    /**
     * Admin: atomically set a synthetic price level on the book and publish a
     * {@code book_update} WebSocket event.
     */
    public void setAdminBookLevel(String symbol, OrderSide side, BigDecimal price, long qty) {
        OrderBook book = books.get(symbol);
        if (book == null) return;
        book.setAdminLevel(side, price, qty);
        publishBookUpdate(symbol, book);
    }

    private void publishBookUpdate(String symbol, OrderBook book) {
        EventBus bus = this.eventBus;
        if (bus == null) return;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type",   "book_update");
        evt.put("symbol", symbol);
        evt.put("bids",   book.getBidLevels(100));
        evt.put("asks",   book.getAskLevels(100));
        bus.publish(evt);
    }
}
