package com.fix.simulator.handler;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.TopOfBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Group;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.MarketDataIncrementalRefresh;
import quickfix.fix42.MarketDataRequest;
import quickfix.fix42.MarketDataRequestReject;
import quickfix.fix42.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-of-book (BBO) market data over FIX 4.2, per symbol.
 *
 * <p>Supported requests (35=V, MarketDataRequest):
 * <ul>
 *   <li>SubscriptionRequestType 0 (snapshot): one MarketDataSnapshotFullRefresh (35=W) per symbol
 *       with the best bid and best offer and their total quantities.</li>
 *   <li>SubscriptionRequestType 1 (subscribe): the same snapshot, then a MarketDataIncrementalRefresh
 *       (35=X) each time the best bid or offer changes. Changes are checked by
 *       {@link #publishChanges()}, which the simulator calls about every 100 ms.</li>
 *   <li>SubscriptionRequestType 2 (unsubscribe): stops the updates for that request.</li>
 * </ul>
 *
 * <p>MarketDepth must be 1 (top of book). MDEntryType must be 0 (bid), 1 (offer), or absent
 * for both. Anything else is answered with a MarketDataRequestReject (35=Y) and the reason in 281.
 */
public class MarketDataHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataHandler.class);

    /** MDReqRejReason values (FIX 4.2, field 281). */
    static final char REJ_UNKNOWN_SYMBOL          = '0';
    static final char REJ_UNSUPPORTED_SUB_TYPE    = '4';
    static final char REJ_UNSUPPORTED_DEPTH       = '5';
    static final char REJ_UNSUPPORTED_UPDATE_TYPE = '6';
    static final char REJ_UNSUPPORTED_ENTRY_TYPE  = '8';

    private final MatchingEngine engine;
    private final ResponseSender sender;

    /** Active subscriptions keyed by {@code session|reqId|symbol}. */
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** One subscription to one symbol from one request, with the top of book last sent to the client. */
    static final class Subscription {
        final SessionID session;
        final String    reqId;
        final String    symbol;
        final boolean   wantBid;
        final boolean   wantOffer;
        volatile TopOfBook last;

        Subscription(SessionID session, String reqId, String symbol, boolean wantBid, boolean wantOffer) {
            this.session   = session;
            this.reqId     = reqId;
            this.symbol    = symbol;
            this.wantBid   = wantBid;
            this.wantOffer = wantOffer;
        }
    }

    public MarketDataHandler(MatchingEngine engine, ResponseSender sender) {
        this.engine = engine;
        this.sender = sender;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  MarketDataRequest (35=V)
    // ──────────────────────────────────────────────────────────────────────

    public void handle(MarketDataRequest msg, SessionID sessionId) {
        try {
            String reqId = msg.isSetField(MDReqID.FIELD) ? msg.getString(MDReqID.FIELD) : "";
            if (reqId.isEmpty()) {
                // Without MDReqID there is nothing to answer with; the request is malformed.
                log.warn("[{}] MarketDataRequest without MDReqID (262) ignored", sessionId);
                return;
            }

            char subType = msg.isSetField(SubscriptionRequestType.FIELD)
                    ? msg.getChar(SubscriptionRequestType.FIELD) : '\0';
            if (subType != '0' && subType != '1' && subType != '2') {
                reject(sessionId, reqId, REJ_UNSUPPORTED_SUB_TYPE, "Unsupported SubscriptionRequestType: " + subType);
                return;
            }

            // Only top of book (MarketDepth 1) is supported.
            int depth = msg.isSetField(MarketDepth.FIELD) ? msg.getInt(MarketDepth.FIELD) : 0;
            if (depth != 1) {
                reject(sessionId, reqId, REJ_UNSUPPORTED_DEPTH, "Only MarketDepth 1 (top of book) is supported");
                return;
            }

            if (msg.isSetField(MDUpdateType.FIELD) && msg.getInt(MDUpdateType.FIELD) != 1) {
                reject(sessionId, reqId, REJ_UNSUPPORTED_UPDATE_TYPE, "Only MDUpdateType 1 (incremental) is supported");
                return;
            }

            boolean wantBid = true, wantOffer = true;
            int entryCount = msg.getGroupCount(267);
            if (entryCount > 0) {
                wantBid = false;
                wantOffer = false;
                for (int i = 1; i <= entryCount; i++) {
                    Group g = msg.getGroup(i, new MarketDataRequest.NoMDEntryTypes());
                    char t = g.getChar(MDEntryType.FIELD);
                    if (t == MDEntryType.BID)        wantBid = true;
                    else if (t == MDEntryType.OFFER) wantOffer = true;
                    else {
                        reject(sessionId, reqId, REJ_UNSUPPORTED_ENTRY_TYPE, "Unsupported MDEntryType: " + t);
                        return;
                    }
                }
            }

            int symCount = msg.getGroupCount(146);
            if (symCount == 0) {
                reject(sessionId, reqId, REJ_UNKNOWN_SYMBOL, "No symbol in request");
                return;
            }
            String[] symbols = new String[symCount];
            for (int i = 1; i <= symCount; i++) {
                Group g = msg.getGroup(i, new MarketDataRequest.NoRelatedSym());
                symbols[i - 1] = g.getString(Symbol.FIELD);
                if (engine.getTopOfBook(symbols[i - 1]) == null) {
                    reject(sessionId, reqId, REJ_UNKNOWN_SYMBOL, "Unknown symbol: " + symbols[i - 1]);
                    return;
                }
            }

            for (String symbol : symbols) {
                switch (subType) {
                    case '0' -> sendSnapshot(sessionId, reqId, symbol, wantBid, wantOffer);
                    case '1' -> subscribe(sessionId, reqId, symbol, wantBid, wantOffer);
                    case '2' -> unsubscribe(sessionId, reqId, symbol);
                    default  -> { /* unreachable: subType validated above */ }
                }
            }
        } catch (Exception e) {
            log.error("[{}] MarketDataRequest failed: {}", sessionId, e.getMessage(), e);
        }
    }

    private void subscribe(SessionID sessionId, String reqId, String symbol, boolean wantBid, boolean wantOffer) {
        Subscription s = new Subscription(sessionId, reqId, symbol, wantBid, wantOffer);
        subscriptions.put(key(sessionId, reqId, symbol), s);
        TopOfBook now = engine.getTopOfBook(symbol);
        sender.send(sessionId, buildSnapshot(reqId, symbol, now, wantBid, wantOffer));
        s.last = now;
    }

    private void unsubscribe(SessionID sessionId, String reqId, String symbol) {
        subscriptions.remove(key(sessionId, reqId, symbol));
    }

    private void sendSnapshot(SessionID sessionId, String reqId, String symbol, boolean wantBid, boolean wantOffer) {
        sender.send(sessionId, buildSnapshot(reqId, symbol, engine.getTopOfBook(symbol), wantBid, wantOffer));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Incremental updates (called periodically)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Compare each subscribed symbol's top of book with what the client last received, and send a
     * MarketDataIncrementalRefresh for each side that changed. Safe to call from a timer thread.
     */
    public void publishChanges() {
        for (Subscription s : subscriptions.values()) {
            TopOfBook now = engine.getTopOfBook(s.symbol);
            TopOfBook was = s.last;
            if (now == null || (was != null && now.sameAs(was))) continue;

            if (s.wantBid && !bidSame(now, was)) {
                sender.send(s.session, buildUpdate(s, MDEntryType.BID, now.bidPx(), now.bidQty()));
            }
            if (s.wantOffer && !askSame(now, was)) {
                sender.send(s.session, buildUpdate(s, MDEntryType.OFFER, now.askPx(), now.askQty()));
            }
            s.last = now;
        }
    }

    /** Drop every subscription that belongs to a session, for example when it logs out. */
    public void onSessionLogout(SessionID sessionId) {
        subscriptions.values().removeIf(s -> s.session.equals(sessionId));
    }

    /** Number of active subscriptions (one per symbol per request). */
    public int subscriptionCount() {
        return subscriptions.size();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Message builders
    // ──────────────────────────────────────────────────────────────────────

    /** MarketDataSnapshotFullRefresh (35=W) for one symbol. Sides without orders are left out. */
    static MarketDataSnapshotFullRefresh buildSnapshot(String reqId, String symbol, TopOfBook top,
                                                       boolean wantBid, boolean wantOffer) {
        MarketDataSnapshotFullRefresh w = new MarketDataSnapshotFullRefresh();
        w.set(new MDReqID(reqId));
        w.set(new Symbol(symbol));
        // NoMDEntries (268) is required even when it is 0, so it is always sent.
        int entries = 0;
        if (top != null) {
            if (wantBid && top.bidPx() != null) {
                MarketDataSnapshotFullRefresh.NoMDEntries e = new MarketDataSnapshotFullRefresh.NoMDEntries();
                e.set(new MDEntryType(MDEntryType.BID));
                e.set(new MDEntryPx(top.bidPx().doubleValue()));
                e.set(new MDEntrySize(top.bidQty()));
                w.addGroup(e);
                entries++;
            }
            if (wantOffer && top.askPx() != null) {
                MarketDataSnapshotFullRefresh.NoMDEntries e = new MarketDataSnapshotFullRefresh.NoMDEntries();
                e.set(new MDEntryType(MDEntryType.OFFER));
                e.set(new MDEntryPx(top.askPx().doubleValue()));
                e.set(new MDEntrySize(top.askQty()));
                w.addGroup(e);
                entries++;
            }
        }
        w.set(new NoMDEntries(entries));
        return w;
    }

    /**
     * MarketDataIncrementalRefresh (35=X) for one side. A null price means the side is now empty,
     * so the entry is a Delete (279=2) with no price.
     */
    static MarketDataIncrementalRefresh buildUpdate(Subscription s, char side, BigDecimal price, long qty) {
        MarketDataIncrementalRefresh x = new MarketDataIncrementalRefresh();
        x.set(new MDReqID(s.reqId));
        MarketDataIncrementalRefresh.NoMDEntries e = new MarketDataIncrementalRefresh.NoMDEntries();
        e.set(new MDUpdateAction(price == null ? MDUpdateAction.DELETE : MDUpdateAction.NEW));
        e.set(new MDEntryType(side));
        e.set(new Symbol(s.symbol));
        if (price != null) {
            e.set(new MDEntryPx(price.doubleValue()));
            e.set(new MDEntrySize(qty));
        }
        x.addGroup(e);
        return x;
    }

    private void reject(SessionID sessionId, String reqId, char reason, String text) {
        MarketDataRequestReject y = new MarketDataRequestReject();
        y.set(new MDReqID(reqId));
        y.set(new MDReqRejReason(reason));
        y.set(new Text(text));
        sender.send(sessionId, y);
    }

    private static boolean bidSame(TopOfBook a, TopOfBook b) {
        return b != null && Objects.equals(a.bidPx(), b.bidPx()) && a.bidQty() == b.bidQty();
    }

    private static boolean askSame(TopOfBook a, TopOfBook b) {
        return b != null && Objects.equals(a.askPx(), b.askPx()) && a.askQty() == b.askQty();
    }

    private static String key(SessionID sessionId, String reqId, String symbol) {
        return sessionId + "|" + reqId + "|" + symbol;
    }
}
