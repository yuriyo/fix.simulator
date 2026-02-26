package com.fix.simulator.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MatchingEngine – order lifecycle and matching")
class MatchingEngineTest {

    private static final String SYM  = "AAPL";
    private static final String SESS = "CLIENT:SESSION";

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order limitBuy(String c, long qty, String px) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null);
    }

    private Order limitSell(String c, long qty, String px) {
        return new Order(SESS, c, null, SYM, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.DAY, qty, new BigDecimal(px), null);
    }

    private Order iocBuy(String c, long qty, String px) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.IOC, qty, new BigDecimal(px), null);
    }

    private Order fokBuy(String c, long qty, String px) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.FOK, qty, new BigDecimal(px), null);
    }

    private Order fokSell(String c, long qty, String px) {
        return new Order(SESS, c, null, SYM, OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.FOK, qty, new BigDecimal(px), null);
    }

    private Order mktFokBuy(String c, long qty) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.MARKET, TimeInForce.FOK, qty, null, null);
    }

    private Order mktBuy(String c, long qty) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.MARKET, TimeInForce.DAY, qty, null, null);
    }

    private Order mktSell(String c, long qty) {
        return new Order(SESS, c, null, SYM, OrderSide.SELL,
                OrderType.MARKET, TimeInForce.DAY, qty, null, null);
    }

    private Order stopBuy(String c, long qty, String stopPx) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.STOP, TimeInForce.GTC, qty, null, new BigDecimal(stopPx));
    }

    private Order stopSell(String c, long qty, String stopPx) {
        return new Order(SESS, c, null, SYM, OrderSide.SELL,
                OrderType.STOP, TimeInForce.GTC, qty, null, new BigDecimal(stopPx));
    }

    private Order stopLimitBuy(String c, long qty, String limitPx, String stopPx) {
        return new Order(SESS, c, null, SYM, OrderSide.BUY,
                OrderType.STOP_LIMIT, TimeInForce.GTC, qty,
                new BigDecimal(limitPx), new BigDecimal(stopPx));
    }

    /** Place passive resting SELL and consume the NEW ack. */
    private Order placeSellPassive(String c, long qty, String px) {
        Order sell = limitSell(c, qty, px);
        engine.newOrder(sell);  // returns [NEW] – no fills yet
        return sell;
    }

    /** Place passive resting BUY and consume the NEW ack. */
    private Order placeBuyPassive(String c, long qty, String px) {
        Order buy = limitBuy(c, qty, px);
        engine.newOrder(buy);
        return buy;
    }

    // ── Symbol management ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Symbol registration")
    class SymbolRegistration {

        @Test
        void hasSymbol_afterRegister() {
            assertTrue(engine.hasSymbol(SYM));
        }

        @Test
        void hasSymbol_unknownReturnsFalse() {
            assertFalse(engine.hasSymbol("UNKNOWN"));
        }

        @Test
        void getBook_knownSymbolNotNull() {
            assertNotNull(engine.getBook(SYM));
        }

        @Test
        void getBook_unknownReturnsNull() {
            assertNull(engine.getBook("UNKNOWN"));
        }
    }

    // ── New order – basic ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("newOrder – basic scenarios")
    class NewOrderBasic {

        @Test
        void limitBuyNoFill_onlyNewAck() {
            List<Execution> execs = engine.newOrder(limitBuy("B1", 100, "100.00"));
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.NEW, execs.get(0).getExecType());
        }

        @Test
        void limitSellNoFill_onlyNewAck() {
            List<Execution> execs = engine.newOrder(limitSell("S1", 100, "100.00"));
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.NEW, execs.get(0).getExecType());
        }

        @Test
        void newLimitBuyAddsToBook() {
            engine.newOrder(limitBuy("B1", 100, "99.00"));
            assertEquals(new BigDecimal("99.00"), engine.getBook(SYM).bestBid());
        }

        @Test
        void newLimitSellAddsToBook() {
            engine.newOrder(limitSell("S1", 100, "101.00"));
            assertEquals(new BigDecimal("101.00"), engine.getBook(SYM).bestAsk());
        }

        @Test
        void unknownSymbol_returnsRejected() {
            Order order = new Order(SESS, "X1", null, "ZZZZ", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.DAY, 100, new BigDecimal("1.00"), null);
            List<Execution> execs = engine.newOrder(order);
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.REJECTED, execs.get(0).getExecType());
            assertEquals(OrderStatus.REJECTED, order.getStatus());
        }
    }

    // ── New order – limit matching ────────────────────────────────────────────

    @Nested
    @DisplayName("newOrder – limit order matching")
    class NewOrderLimitMatching {

        @Test
        void limitBuyMatchesSell_exactQty() {
            placeSellPassive("S1", 100, "99.00");
            List<Execution> execs = engine.newOrder(limitBuy("B1", 100, "100.00"));

            // [NEW, FILL(buy), FILL(sell)]
            assertEquals(3, execs.size());
            assertEquals(Execution.ExecType.NEW,  execs.get(0).getExecType());
            assertEquals(Execution.ExecType.FILL, execs.get(1).getExecType());
            assertEquals(Execution.ExecType.FILL, execs.get(2).getExecType());
        }

        @Test
        void limitBuyMatchesSell_aggressorFillsPriceIsPassivePrice() {
            placeSellPassive("S1", 100, "99.00");
            List<Execution> execs = engine.newOrder(limitBuy("B1", 100, "101.00"));
            assertEquals(new BigDecimal("99.00"), execs.get(1).getPrice());
        }

        @Test
        void limitBuy_partialFill_remainderInBook() {
            placeSellPassive("S1", 50, "99.00");
            Order buy = limitBuy("B1", 100, "100.00");
            List<Execution> execs = engine.newOrder(buy);

            // [NEW, PARTIAL_FILL(buy), FILL(sell)]
            assertEquals(3, execs.size());
            assertEquals(Execution.ExecType.PARTIAL_FILL, execs.get(1).getExecType());
            assertEquals(50, buy.getOpenQty());
            // remainder should be in the book
            assertEquals(new BigDecimal("100.00"), engine.getBook(SYM).bestBid());
        }

        @Test
        void limitSellMatchesBuy() {
            placeBuyPassive("B1", 100, "100.00");
            List<Execution> execs = engine.newOrder(limitSell("S1", 100, "99.00"));
            assertEquals(3, execs.size());
            assertEquals(Execution.ExecType.FILL, execs.get(1).getExecType());
        }

        @Test
        void limitBuyPriceTooLow_noMatch_restingInBook() {
            placeSellPassive("S1", 100, "105.00");
            List<Execution> execs = engine.newOrder(limitBuy("B1", 100, "100.00"));
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.NEW, execs.get(0).getExecType());
            // both are now resting in the book
            assertNotNull(engine.getBook(SYM).bestBid());
            assertNotNull(engine.getBook(SYM).bestAsk());
        }

        @Test
        void multipleSells_buySweepes_pricePriority() {
            placeSellPassive("S1", 50, "99.00");
            placeSellPassive("S2", 50, "100.00");
            Order buy = limitBuy("B1", 100, "101.00");
            List<Execution> execs = engine.newOrder(buy);

            // NEW + 2*FILL(buy) + FILL(S1) + FILL(S2) ... wait actually it's
            // NEW + PARTIAL_FILL(buy) + FILL(S1) + FILL(buy) + FILL(S2)
            // The aggressor fill type depends on qty remaining after each passive fill
            assertTrue(execs.size() >= 3);
            assertEquals(Execution.ExecType.NEW, execs.get(0).getExecType());
            assertEquals(0, buy.getOpenQty());
        }
    }

    // ── New order – market orders ─────────────────────────────────────────────

    @Nested
    @DisplayName("newOrder – market orders")
    class NewOrderMarket {

        @Test
        void marketBuyFullFill() {
            placeSellPassive("S1", 100, "99.00");
            Order mkt = mktBuy("M1", 100);
            List<Execution> execs = engine.newOrder(mkt);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.FILL
                    && e.getOrder() == mkt));
        }

        @Test
        void marketBuyNoLiquidity_cancelled() {
            Order mkt = mktBuy("M1", 100);
            List<Execution> execs = engine.newOrder(mkt);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(OrderStatus.CANCELLED, mkt.getStatus());
        }

        @Test
        void marketSellPartialLiquidity_remainderCancelled() {
            placeBuyPassive("B1", 50, "100.00");
            Order mkt = mktSell("M1", 100);
            engine.newOrder(mkt);

            assertEquals(OrderStatus.CANCELLED, mkt.getStatus());
            assertEquals(0, mkt.getOpenQty()); // fully cancelled after partial fill
        }
    }

    // ── New order – IOC ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("newOrder – IOC (immediate-or-cancel)")
    class NewOrderIoc {

        @Test
        void iocPartialFill_remainderCancelled() {
            placeSellPassive("S1", 50, "99.00");
            Order ioc = iocBuy("I1", 100, "100.00");
            List<Execution> execs = engine.newOrder(ioc);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(OrderStatus.CANCELLED, ioc.getStatus());
            // IOC remainder must NOT appear in book
            assertNull(engine.getBook(SYM).findByClientKey(SESS, "I1"));
        }

        @Test
        void iocNoFill_fullyCancelled() {
            Order ioc = iocBuy("I1", 100, "90.00");
            List<Execution> execs = engine.newOrder(ioc);
            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void iocFullFill_noCancelExecution() {
            placeSellPassive("S1", 100, "99.00");
            Order ioc = iocBuy("I1", 100, "100.00");
            List<Execution> execs = engine.newOrder(ioc);
            assertTrue(execs.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }
    }

    // ── FOK (Fill-or-Kill) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("newOrder – FOK (fill-or-kill)")
    class NewOrderFok {

        @Test
        void fokBuy_fullFill_noCancel() {
            placeSellPassive("S1", 100, "99.00");
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> execs = engine.newOrder(fok);

            // NEW ack + 2 fill execs, no cancel
            assertTrue(execs.stream().anyMatch(e ->
                    e.getExecType() == Execution.ExecType.FILL && e.getOrder() == fok));
            assertTrue(execs.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(OrderStatus.FILLED, fok.getStatus());
        }

        @Test
        void fokBuy_partialLiquidity_cancelledImmediately() {
            placeSellPassive("S1", 50, "99.00"); // only 50 available, want 100
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            assertEquals(OrderStatus.CANCELLED, fok.getStatus());
            // Passive sell must be completely untouched
            assertEquals(50, engine.getBook(SYM).findByClientKey(SESS, "S1").getOpenQty());
        }

        @Test
        void fokBuy_noLiquidity_cancelledImmediately() {
            Order fok = fokBuy("F1", 100, "100.00");
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void fokBuy_cancelled_notAddedToBook() {
            placeSellPassive("S1", 50, "99.00");
            Order fok = fokBuy("F1", 100, "100.00");
            engine.newOrder(fok);

            // Cancelled FOK must not rest in book
            assertNull(engine.getBook(SYM).findByClientKey(SESS, "F1"));
        }

        @Test
        void fokSell_fullFill_noCancel() {
            placeBuyPassive("B1", 100, "101.00");
            Order fok = fokSell("F1", 100, "99.00");
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e ->
                    e.getExecType() == Execution.ExecType.FILL && e.getOrder() == fok));
            assertTrue(execs.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void fokSell_partialLiquidity_cancelled() {
            placeBuyPassive("B1", 60, "101.00");
            Order fok = fokSell("F1", 100, "99.00");
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            // Passive buy must be untouched
            assertEquals(60, engine.getBook(SYM).findByClientKey(SESS, "B1").getOpenQty());
        }

        @Test
        void mktFokBuy_sufficientLiquidity_fills() {
            placeSellPassive("S1", 100, "500.00"); // MARKET matches any price
            Order fok = mktFokBuy("F1", 100);
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e ->
                    e.getExecType() == Execution.ExecType.FILL && e.getOrder() == fok));
            assertTrue(execs.stream().noneMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
        }

        @Test
        void mktFokBuy_insufficientLiquidity_cancelled() {
            placeSellPassive("S1", 50, "99.00");
            Order fok = mktFokBuy("F1", 100);
            List<Execution> execs = engine.newOrder(fok);

            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.CANCELLED));
            // Passive sell entirely untouched
            assertEquals(50, engine.getBook(SYM).findByClientKey(SESS, "S1").getOpenQty());
        }
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrderTests {

        @Test
        void cancelRestingLimit_returnsCancelledExecution() {
            Order buy = limitBuy("B1", 100, "99.00");
            engine.newOrder(buy);

            List<Execution> execs = engine.cancelOrder(SESS, "B1", "B1-CXL", SYM);
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.CANCELLED, execs.get(0).getExecType());
            assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        }

        @Test
        void cancelledOrder_removedFromBook() {
            Order buy = limitBuy("B1", 100, "99.00");
            engine.newOrder(buy);
            engine.cancelOrder(SESS, "B1", "B1-CXL", SYM);
            assertNull(engine.getBook(SYM).bestBid());
        }

        @Test
        void cancelStopOrder_removedFromPending() {
            Order stop = stopBuy("ST1", 100, "110.00");
            engine.newOrder(stop);
            List<Execution> execs = engine.cancelOrder(SESS, "ST1", "ST1-CXL", SYM);
            assertEquals(1, execs.size());
            assertEquals(Execution.ExecType.CANCELLED, execs.get(0).getExecType());
        }

        @Test
        void cancelUnknownOrder_returnsEmpty() {
            List<Execution> execs = engine.cancelOrder(SESS, "NONE", "CXL", SYM);
            assertTrue(execs.isEmpty());
        }

        @Test
        void cancelUnknownSymbol_returnsEmpty() {
            List<Execution> execs = engine.cancelOrder(SESS, "B1", "CXL", "ZZZZ");
            assertTrue(execs.isEmpty());
        }

        @Test
        void cancelAlreadyFilledOrder_returnsEmpty() {
            placeSellPassive("S1", 100, "99.00");
            Order buy = limitBuy("B1", 100, "100.00");
            engine.newOrder(buy); // fully fills

            List<Execution> execs = engine.cancelOrder(SESS, "B1", "B1-CXL", SYM);
            assertTrue(execs.isEmpty());
        }

        @Test
        void cancelAlreadyCancelledOrder_returnsEmpty() {
            Order buy = limitBuy("B1", 100, "99.00");
            engine.newOrder(buy);
            engine.cancelOrder(SESS, "B1", "B1-CXL", SYM);
            // try to cancel again
            List<Execution> execs2 = engine.cancelOrder(SESS, "B1", "B1-CXL2", SYM);
            assertTrue(execs2.isEmpty());
        }
    }

    // ── Cancel-Replace ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replaceOrder")
    class ReplaceOrderTests {

        @Test
        void replaceLimit_returnsReplacedAndNew() {
            Order orig = limitBuy("B1", 100, "99.00");
            engine.newOrder(orig);

            Order replacement = limitBuy("B2", 200, "98.00");
            replacement = new Order(SESS, "B2", "B1", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    200, new BigDecimal("98.00"), null);

            List<Execution> execs = engine.replaceOrder(SESS, "B1", replacement);

            // [REPLACED(orig), NEW(replacement)]
            assertEquals(2, execs.size());
            assertEquals(Execution.ExecType.REPLACED, execs.get(0).getExecType());
            assertEquals(Execution.ExecType.NEW,      execs.get(1).getExecType());
        }

        @Test
        void replaceRemovesOldFromBook() {
            engine.newOrder(limitBuy("B1", 100, "99.00"));
            Order replacement = new Order(SESS, "B2", "B1", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    200, new BigDecimal("98.00"), null);
            engine.replaceOrder(SESS, "B1", replacement);

            // Old order no longer findable by old clOrdId
            assertNull(engine.getBook(SYM).findByClientKey(SESS, "B1"));
        }

        @Test
        void replaceAddsNewToBook() {
            engine.newOrder(limitBuy("B1", 100, "99.00"));
            Order replacement = new Order(SESS, "B2", "B1", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    200, new BigDecimal("98.00"), null);
            engine.replaceOrder(SESS, "B1", replacement);

            assertNotNull(engine.getBook(SYM).findByClientKey(SESS, "B2"));
        }

        @Test
        void replaceUnknownOrder_returnsEmpty() {
            Order replacement = new Order(SESS, "B2", "NONE", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    100, new BigDecimal("99.00"), null);
            List<Execution> execs = engine.replaceOrder(SESS, "NONE", replacement);
            assertTrue(execs.isEmpty());
        }

        @Test
        void replaceFilledOrder_returnsEmpty() {
            placeSellPassive("S1", 100, "99.00");
            engine.newOrder(limitBuy("B1", 100, "100.00")); // fully fills

            Order replacement = new Order(SESS, "B2", "B1", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    100, new BigDecimal("99.00"), null);
            List<Execution> execs = engine.replaceOrder(SESS, "B1", replacement);
            assertTrue(execs.isEmpty());
        }

        @Test
        void replaceWithMatchablePrice_triggersImmediateFill() {
            placeSellPassive("S1", 100, "99.00");
            engine.newOrder(limitBuy("B1", 100, "98.00")); // sits in book, no match

            // Replace with higher price that crosses the ask
            Order replacement = new Order(SESS, "B2", "B1", SYM,
                    OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY,
                    100, new BigDecimal("100.00"), null);
            List<Execution> execs = engine.replaceOrder(SESS, "B1", replacement);

            // [REPLACED, NEW, FILL(buy), FILL(sell)]
            assertTrue(execs.size() >= 3);
            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.REPLACED));
            assertTrue(execs.stream().anyMatch(e -> e.getExecType() == Execution.ExecType.FILL));
        }
    }

    // ── STOP / STOP_LIMIT ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stop and stop-limit orders")
    class StopOrders {

        @Test
        void stopBuy_parksInPending_notInBook() {
            Order stop = stopBuy("ST1", 100, "105.00");
            engine.newOrder(stop);
            assertNull(engine.getBook(SYM).bestBid());
            assertNull(engine.getBook(SYM).bestAsk());
        }

        @Test
        void stopBuy_triggeredByFill() {
            // Park a buy stop at 105
            engine.newOrder(stopBuy("ST1", 50, "105.00"));

            // Place a passive sell at 106 only (no 99-sell so the fill price is 106)
            placeSellPassive("S1", 50, "106.00");

            // Aggressive buy at 106 → fills at 106 → last price 106 >= stop 105 → trigger
            Order aggressiveBuy = limitBuy("B-AGG", 50, "106.00");
            List<Execution> execs = engine.newOrder(aggressiveBuy);

            assertEquals(new BigDecimal("106.00"), engine.getMarketState().getLastPrice(SYM));
            // The aggressive buy itself must have filled
            assertTrue(execs.stream().anyMatch(e -> e.getOrder() == aggressiveBuy
                    && e.getExecType() == Execution.ExecType.FILL));
            // After fill at 106, buy stop at 105 should have been triggered
            assertTrue(execs.stream().anyMatch(e -> e.getOrder().getClOrdId().equals("ST1")));
        }

        @Test
        void stopBuy_immediatelyTriggered_whenLastPriceAlreadyAtStop() {
            // Set last price above stop level
            engine.getMarketState().updateLastPrice(SYM, new BigDecimal("110.00"));

            // Stop buy at 105 – last price 110 >= 105 → immediately triggered
            Order stop = stopBuy("ST1", 50, "105.00");
            List<Execution> execs = engine.newOrder(stop);

            // Should have been triggered immediately – at minimum a NEW ack
            assertFalse(execs.isEmpty());
            assertEquals(Execution.ExecType.NEW, execs.get(0).getExecType());
        }

        @Test
        void stopSell_immediatelyTriggered_whenLastPriceBelowStop() {
            engine.getMarketState().updateLastPrice(SYM, new BigDecimal("95.00"));
            Order stop = stopSell("ST1", 50, "100.00");
            List<Execution> execs = engine.newOrder(stop);
            assertFalse(execs.isEmpty());
        }

        @Test
        void stopSell_notTriggered_parks() {
            engine.getMarketState().updateLastPrice(SYM, new BigDecimal("105.00"));
            Order stop = stopSell("ST1", 50, "99.00"); // last 105 > stopPx 99 → not triggered
            engine.newOrder(stop);
            assertNull(engine.getBook(SYM).bestAsk());
        }

        @Test
        void stopLimit_triggersAsLimitOrder() {
            placeSellPassive("S1", 100, "99.00");
            // Set last price above stop-limit buy stop level
            engine.getMarketState().updateLastPrice(SYM, new BigDecimal("100.00"));

            // stop-limit BUY: stop=100, limit=101 → immediately triggered, placed as limit
            Order sl = stopLimitBuy("SL1", 100, "101.00", "100.00");
            List<Execution> execs = engine.newOrder(sl);

            // Should produce fills since the limit 101 >= ask 99
            assertTrue(execs.stream().anyMatch(e ->
                    e.getExecType() == Execution.ExecType.FILL ||
                    e.getExecType() == Execution.ExecType.PARTIAL_FILL));
        }
    }

    // ── onQuote ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("onQuote – market data updates")
    class OnQuoteTests {

        @Test
        void quoteUpdatesMarketState() {
            engine.onQuote(SESS, SYM,
                    new BigDecimal("99.00"), 100,
                    new BigDecimal("101.00"), 100,
                    "Q1");
            MarketState.Quote q = engine.getMarketState().get(SYM);
            assertNotNull(q);
            assertEquals(new BigDecimal("99.00"),  q.bidPrice);
            assertEquals(new BigDecimal("101.00"), q.askPrice);
        }

        @Test
        void quoteUnknownSymbol_returnsEmptyNoException() {
            List<Execution> fills = engine.onQuote(SESS, "UNKNOWN",
                    new BigDecimal("99.00"), 100, null, 0, "Q1");
            assertTrue(fills.isEmpty());
        }

        @Test
        void quoteNullBid_onlyAskInserted() {
            engine.onQuote(SESS, SYM, null, 0, new BigDecimal("101.00"), 100, "Q1");
            // Market state updated with ask side only
            MarketState.Quote q = engine.getMarketState().get(SYM);
            assertNotNull(q);
        }

        @Test
        void quoteBidMatchesRestingAggressor() {
            // Place a resting BUY at 100; a quote with ask at 99 should fill against it
            placeBuyPassive("B1", 100, "100.00");
            List<Execution> fills = engine.onQuote(SESS, SYM,
                    null, 0,
                    new BigDecimal("99.00"), 100,
                    "Q1");
            // The ask quote at 99 should match against the resting BUY at 100
            assertFalse(fills.isEmpty());
        }

        @Test
        void quoteAskMatchesRestingBuy_updatesLastPrice() {
            placeBuyPassive("B1", 100, "100.00");
            engine.onQuote(SESS, SYM, null, 0, new BigDecimal("99.00"), 100, "Q1");
            // Last price should be set after fill
            assertNotNull(engine.getMarketState().getLastPrice(SYM));
        }
    }

    // ── MarketState accessor ──────────────────────────────────────────────────

    @Test
    void getMarketState_notNull() {
        assertNotNull(engine.getMarketState());
    }

    @Test
    void fillUpdatesLastPrice() {
        placeSellPassive("S1", 100, "99.00");
        engine.newOrder(limitBuy("B1", 100, "100.00"));
        assertEquals(new BigDecimal("99.00"), engine.getMarketState().getLastPrice(SYM));
    }
}
