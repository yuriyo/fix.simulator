package com.fix.simulator.handler;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderSide;
import com.fix.simulator.engine.OrderType;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import quickfix.Group;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.MarketDataIncrementalRefresh;
import quickfix.fix42.MarketDataRequest;
import quickfix.fix42.MarketDataRequestReject;
import quickfix.fix42.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarketDataHandler – top-of-book (BBO) per symbol, FIX 4.2 V / W / X / Y")
class MarketDataHandlerTest {

    private static final String AAPL = "AAPL";
    private static final String MSFT = "MSFT";
    private static final SessionID SESSION = new SessionID("FIX.4.2", "EXCHANGE", "CLIENT1");

    private MatchingEngine engine;
    private List<Message> sent;
    private MarketDataHandler handler;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(AAPL);
        engine.registerSymbol(MSFT);
        sent = new ArrayList<>();
        handler = new MarketDataHandler(engine, (sid, msg) -> sent.add(msg));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void rest(String clOrdId, String sym, OrderSide side, long qty, String px) {
        engine.newOrder(new Order("S:A", clOrdId, null, sym, side,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null));
    }

    private void cancel(String clOrdId, String sym) {
        engine.cancelOrder("S:A", clOrdId, "C-" + clOrdId, sym);
    }

    /** V request: reqId, subscription type, depth, optional MDEntryType list, symbols. */
    private static MarketDataRequest request(String reqId, char subType, int depth,
                                             List<Character> entryTypes, String... symbols) throws Exception {
        MarketDataRequest m = new MarketDataRequest();
        m.set(new MDReqID(reqId));
        m.set(new SubscriptionRequestType(subType));
        m.set(new MarketDepth(depth));
        if (entryTypes != null) {
            for (char t : entryTypes) {
                MarketDataRequest.NoMDEntryTypes g = new MarketDataRequest.NoMDEntryTypes();
                g.set(new MDEntryType(t));
                m.addGroup(g);
            }
        }
        for (String s : symbols) {
            MarketDataRequest.NoRelatedSym g = new MarketDataRequest.NoRelatedSym();
            g.set(new Symbol(s));
            m.addGroup(g);
        }
        return m;
    }

    private static MarketDataRequest snapshotBbo(String reqId, String... symbols) throws Exception {
        return request(reqId, '0', 1, null, symbols);
    }

    /**
     * Read every repetition of a repeating group. QuickFIX/J fills the group object it is given,
     * so each repetition needs its own instance; a shared one would hold only the last values.
     */
    private static List<Group> entries(Message m, int groupCountField, Group proto) throws Exception {
        List<Group> out = new ArrayList<>();
        int n = m.getGroupCount(groupCountField);
        for (int i = 1; i <= n; i++) {
            Group g = proto.getClass().getDeclaredConstructor().newInstance();
            out.add(m.getGroup(i, g));
        }
        return out;
    }

    private List<Group> snapshotEntries(Message w) throws Exception {
        return entries(w, 268, new MarketDataSnapshotFullRefresh.NoMDEntries());
    }

    private static Group entryOfType(List<Group> entries, char type) throws Exception {
        for (Group g : entries) if (g.getChar(MDEntryType.FIELD) == type) return g;
        throw new AssertionError("no entry of type " + type + " in " + entries.size());
    }

    private Message onlySent() {
        assertEquals(1, sent.size(), "expected exactly one message, got " + sent.size());
        return sent.get(0);
    }

    private static String rejectReason(Message y) throws Exception {
        return y.getString(MDReqRejReason.FIELD);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Snapshot (V subscription type 0 → W)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("snapshot (SubscriptionRequestType 0)")
    class Snapshot {

        @Test
        @DisplayName("both sides: best bid and best offer with their total quantities")
        void bothSides() throws Exception {
            rest("B1", AAPL, OrderSide.BUY, 100, "9.90");
            rest("B2", AAPL, OrderSide.BUY, 50,  "9.90");   // same best level: quantities add up
            rest("B3", AAPL, OrderSide.BUY, 10,  "9.80");   // lower level, not in BBO
            rest("S1", AAPL, OrderSide.SELL, 70, "10.10");

            handler.handle(snapshotBbo("R1", AAPL), SESSION);

            Message w = onlySent();
            assertInstanceOf(MarketDataSnapshotFullRefresh.class, w);
            assertEquals("R1", w.getString(MDReqID.FIELD));
            assertEquals(AAPL, w.getString(Symbol.FIELD));

            List<Group> e = snapshotEntries(w);
            assertEquals(2, w.getInt(NoMDEntries.FIELD));
            assertEquals(2, e.size());
            // Entry order is not significant in FIX, so look each side up by its MDEntryType.
            Group bid = entryOfType(e, '0');
            Group ask = entryOfType(e, '1');
            assertEquals(9.90, bid.getDouble(MDEntryPx.FIELD), 1e-9);
            assertEquals(150, bid.getInt(MDEntrySize.FIELD));
            assertEquals(10.10, ask.getDouble(MDEntryPx.FIELD), 1e-9);
            assertEquals(70, ask.getInt(MDEntrySize.FIELD));
        }

        @Test
        @DisplayName("only offers in the book: one offer entry, no bid entry")
        void onlyOffers() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 25, "10.50");
            handler.handle(snapshotBbo("R2", AAPL), SESSION);
            List<Group> e = snapshotEntries(onlySent());
            assertEquals(1, e.size());
            assertEquals('1', e.get(0).getChar(MDEntryType.FIELD));
        }

        @Test
        @DisplayName("empty book: snapshot with no entries")
        void emptyBook() throws Exception {
            handler.handle(snapshotBbo("R3", AAPL), SESSION);
            Message w = onlySent();
            assertEquals(AAPL, w.getString(Symbol.FIELD));
            assertEquals(0, w.getInt(NoMDEntries.FIELD));   // count is sent even when it is 0
            assertTrue(snapshotEntries(w).isEmpty());
        }

        @Test
        @DisplayName("one W per symbol when several symbols are requested")
        void severalSymbols() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 5, "10.00");
            rest("S2", MSFT, OrderSide.SELL, 7, "20.00");
            handler.handle(snapshotBbo("R4", AAPL, MSFT), SESSION);
            assertEquals(2, sent.size());
            assertEquals(AAPL, sent.get(0).getString(Symbol.FIELD));
            assertEquals(MSFT, sent.get(1).getString(Symbol.FIELD));
        }

        @Test
        @DisplayName("entry types 0 only: bid entry only")
        void bidOnlyRequested() throws Exception {
            rest("B1", AAPL, OrderSide.BUY, 4, "9.00");
            rest("S1", AAPL, OrderSide.SELL, 4, "10.00");
            handler.handle(request("R5", '0', 1, List.of('0'), AAPL), SESSION);
            List<Group> e = snapshotEntries(onlySent());
            assertEquals(1, e.size());
            assertEquals('0', e.get(0).getChar(MDEntryType.FIELD));
        }

        @Test
        @DisplayName("a snapshot does not register a subscription")
        void snapshotIsNotSubscription() throws Exception {
            handler.handle(snapshotBbo("R6", AAPL), SESSION);
            assertEquals(0, handler.subscriptionCount());
        }

        @Test
        @DisplayName("a cancelled best order moves the BBO to the next level")
        void bboFollowsCancel() throws Exception {
            rest("B1", AAPL, OrderSide.BUY, 10, "9.90");
            rest("B2", AAPL, OrderSide.BUY, 10, "9.80");
            cancel("B1", AAPL);
            handler.handle(snapshotBbo("R7", AAPL), SESSION);
            List<Group> e = snapshotEntries(onlySent());
            assertEquals(9.80, e.get(0).getDouble(MDEntryPx.FIELD), 1e-9);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rejects (Y)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("rejects (MarketDataRequestReject, 35=Y)")
    class Rejects {

        @Test
        @DisplayName("unknown symbol → reason 0, nothing subscribed")
        void unknownSymbol() throws Exception {
            handler.handle(snapshotBbo("X1", "NOPE"), SESSION);
            Message y = onlySent();
            assertInstanceOf(MarketDataRequestReject.class, y);
            assertEquals("X1", y.getString(MDReqID.FIELD));
            assertEquals("0", rejectReason(y));
        }

        @Test
        @DisplayName("unknown symbol among several → whole request rejected, no snapshot sent")
        void unknownAmongKnown() throws Exception {
            handler.handle(snapshotBbo("X2", AAPL, "NOPE"), SESSION);
            assertEquals(1, sent.size());
            assertInstanceOf(MarketDataRequestReject.class, sent.get(0));
        }

        @Test
        @DisplayName("no symbol in the request → reason 0")
        void noSymbol() throws Exception {
            handler.handle(request("X3", '0', 1, null), SESSION);
            assertEquals("0", rejectReason(onlySent()));
        }

        @Test
        @DisplayName("MarketDepth other than 1 → reason 5")
        void depthNotTop() throws Exception {
            handler.handle(request("X4", '0', 0, null, AAPL), SESSION);
            assertEquals("5", rejectReason(onlySent()));
        }

        @Test
        @DisplayName("unsupported SubscriptionRequestType → reason 4")
        void badSubType() throws Exception {
            handler.handle(request("X5", '9', 1, null, AAPL), SESSION);
            assertEquals("4", rejectReason(onlySent()));
        }

        @Test
        @DisplayName("missing SubscriptionRequestType → reason 4")
        void missingSubType() throws Exception {
            MarketDataRequest m = new MarketDataRequest();
            m.set(new MDReqID("X6"));
            m.set(new MarketDepth(1));
            MarketDataRequest.NoRelatedSym g = new MarketDataRequest.NoRelatedSym();
            g.set(new Symbol(AAPL));
            m.addGroup(g);
            handler.handle(m, SESSION);
            assertEquals("4", rejectReason(onlySent()));
        }

        @Test
        @DisplayName("full refresh MDUpdateType 0 is not supported → reason 6")
        void fullRefreshNotSupported() throws Exception {
            MarketDataRequest m = request("X7", '1', 1, null, AAPL);
            m.set(new MDUpdateType(0));
            handler.handle(m, SESSION);
            assertEquals("6", rejectReason(onlySent()));
            assertEquals(0, handler.subscriptionCount());
        }

        @Test
        @DisplayName("trade entries (MDEntryType 2) are not supported → reason 8")
        void tradeEntryNotSupported() throws Exception {
            handler.handle(request("X8", '0', 1, List.of('2'), AAPL), SESSION);
            assertEquals("8", rejectReason(onlySent()));
        }

        @Test
        @DisplayName("a request without MDReqID gets no reply (nothing to answer)")
        void missingReqIdIgnored() throws Exception {
            MarketDataRequest m = new MarketDataRequest();
            m.set(new SubscriptionRequestType('0'));
            m.set(new MarketDepth(1));
            MarketDataRequest.NoRelatedSym g = new MarketDataRequest.NoRelatedSym();
            g.set(new Symbol(AAPL));
            m.addGroup(g);
            handler.handle(m, SESSION);
            assertTrue(sent.isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Subscribe (V type 1) and incremental updates (X)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("subscribe (SubscriptionRequestType 1) and updates (X)")
    class Subscribe {

        @Test
        @DisplayName("subscribe sends the snapshot first and registers one subscription")
        void snapshotThenRegistered() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            assertInstanceOf(MarketDataSnapshotFullRefresh.class, onlySent());
            assertEquals(1, handler.subscriptionCount());
        }

        @Test
        @DisplayName("no update is sent while the BBO does not change")
        void noChangeNoUpdate() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            sent.clear();
            handler.publishChanges();
            handler.publishChanges();
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("a new best offer sends an incremental New entry with the new price and size")
        void newBestOffer() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            sent.clear();

            rest("S2", AAPL, OrderSide.SELL, 4, "9.50");   // new best offer
            handler.publishChanges();

            Message x = onlySent();
            assertInstanceOf(MarketDataIncrementalRefresh.class, x);
            assertEquals("R1", x.getString(MDReqID.FIELD));
            List<Group> e = entries(x, 268, new MarketDataIncrementalRefresh.NoMDEntries());
            assertEquals(1, e.size());
            assertEquals(MDUpdateAction.NEW, e.get(0).getChar(MDUpdateAction.FIELD));
            assertEquals('1', e.get(0).getChar(MDEntryType.FIELD));
            assertEquals(AAPL, e.get(0).getString(Symbol.FIELD));
            assertEquals(9.50, e.get(0).getDouble(MDEntryPx.FIELD), 1e-9);
            assertEquals(4, e.get(0).getInt(MDEntrySize.FIELD));
        }

        @Test
        @DisplayName("a change in size at the same price is an update")
        void sizeChangeIsUpdate() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            sent.clear();
            rest("S2", AAPL, OrderSide.SELL, 5, "10.00");   // same level, more quantity
            handler.publishChanges();
            List<Group> e = entries(onlySent(), 268, new MarketDataIncrementalRefresh.NoMDEntries());
            assertEquals(15, e.get(0).getInt(MDEntrySize.FIELD));
        }

        @Test
        @DisplayName("when a side empties it sends a Delete entry (279=2) with no price")
        void sideEmptiesIsDelete() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            sent.clear();
            cancel("S1", AAPL);
            handler.publishChanges();
            List<Group> e = entries(onlySent(), 268, new MarketDataIncrementalRefresh.NoMDEntries());
            assertEquals(MDUpdateAction.DELETE, e.get(0).getChar(MDUpdateAction.FIELD));
            assertEquals('1', e.get(0).getChar(MDEntryType.FIELD));
            assertFalse(e.get(0).isSetField(MDEntryPx.FIELD));
        }

        @Test
        @DisplayName("bid and offer changing together send two entries in one X")
        void bothSidesInOneUpdate() throws Exception {
            rest("B1", AAPL, OrderSide.BUY, 1, "9.00");
            rest("S1", AAPL, OrderSide.SELL, 1, "10.00");
            handler.handle(request("R1", '1', 1, List.of('0', '1'), AAPL), SESSION);
            sent.clear();
            // both sides change in one publish cycle: two X messages, one per side
            rest("B2", AAPL, OrderSide.BUY, 2, "9.20");
            rest("S2", AAPL, OrderSide.SELL, 2, "9.80");
            handler.publishChanges();
            assertEquals(2, sent.size());
        }

        @Test
        @DisplayName("entry type filter: a bid-only subscription gets no offer updates")
        void bidOnlyFilter() throws Exception {
            rest("B1", AAPL, OrderSide.BUY, 1, "9.00");
            handler.handle(request("R1", '1', 1, List.of('0'), AAPL), SESSION);
            sent.clear();
            rest("S1", AAPL, OrderSide.SELL, 1, "10.00");   // offer changes, not subscribed
            handler.publishChanges();
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("unsubscribe stops the updates for that request")
        void unsubscribe() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            handler.handle(request("R1", '2', 1, null, AAPL), SESSION);
            assertEquals(0, handler.subscriptionCount());
            sent.clear();
            rest("S2", AAPL, OrderSide.SELL, 1, "9.00");
            handler.publishChanges();
            assertTrue(sent.isEmpty());
        }

        @Test
        @DisplayName("two requests on one symbol are independent subscriptions")
        void twoRequestsSameSymbol() throws Exception {
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            handler.handle(request("R2", '1', 1, null, AAPL), SESSION);
            assertEquals(2, handler.subscriptionCount());
            sent.clear();
            rest("S2", AAPL, OrderSide.SELL, 1, "9.00");
            handler.publishChanges();
            assertEquals(2, sent.size());
            assertEquals("R1", sent.get(0).getString(MDReqID.FIELD));
            assertEquals("R2", sent.get(1).getString(MDReqID.FIELD));
        }

        @Test
        @DisplayName("logout drops that session's subscriptions only")
        void logoutDropsSession() throws Exception {
            SessionID other = new SessionID("FIX.4.2", "EXCHANGE", "CLIENT2");
            rest("S1", AAPL, OrderSide.SELL, 10, "10.00");
            handler.handle(request("R1", '1', 1, null, AAPL), SESSION);
            handler.handle(request("R2", '1', 1, null, AAPL), other);
            handler.onSessionLogout(SESSION);
            assertEquals(1, handler.subscriptionCount());
        }

        @Test
        @DisplayName("an unknown symbol in a subscribe request registers nothing")
        void unknownSymbolNoSubscription() throws Exception {
            handler.handle(request("R1", '1', 1, null, "NOPE"), SESSION);
            assertEquals(0, handler.subscriptionCount());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Message builders
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("market data request reject carries MDReqID, reason and text")
    void rejectFields() throws Exception {
        handler.handle(snapshotBbo("X9", "NOPE"), SESSION);
        Message y = onlySent();
        assertEquals("X9", y.getString(MDReqID.FIELD));
        assertEquals("0", y.getString(MDReqRejReason.FIELD));
        assertTrue(y.isSetField(Text.FIELD));
    }
}
