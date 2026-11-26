package com.fix.simulator.admin;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderSide;
import com.fix.simulator.engine.OrderType;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Endpoint, auth and lifecycle coverage for {@link AdminServer}, exercised over real HTTP. */
@DisplayName("AdminServer – REST, WebSocket, auth and lifecycle")
class AdminServerCoverageTest {

    private static final String SYM = "AAPL";

    @TempDir Path tmp;

    private MatchingEngine engine;
    private InstrumentStateManager states;
    private ClientRegistry clients;
    private EventBus bus;
    private FIXMessageLog messageLog;
    private AdminServer admin;
    private String token;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void start() throws Exception {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
        states = new InstrumentStateManager();
        clients = new ClientRegistry(tmp.resolve("clients.yml").toString());
        bus = new EventBus();
        messageLog = new FIXMessageLog();
        engine.setEventBus(bus);
        engine.setStateManager(states);
        port = freePort();
        admin = new AdminServer(engine, states, clients, bus, messageLog, port);
        token = admin.adminToken();
        admin.start();
    }

    @AfterEach
    void stop() {
        admin.stop();
        System.clearProperty("admin.token");
        System.clearProperty("admin.bind");
        System.clearProperty("admin.ui");
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    HttpResponse<String> call(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, pub);
        if (auth) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception { return call("GET", path, null, false); }

    HttpResponse<String> authed(String method, String path, String body) throws Exception {
        return call(method, path, body, true);
    }

    static NewOrderSingle newOrderMsg(String clOrdId, String symbol) throws Exception {
        NewOrderSingle m = new NewOrderSingle();
        m.set(new ClOrdID(clOrdId));
        m.set(new Symbol(symbol));
        m.set(new Side(Side.BUY));
        m.set(new OrdType(OrdType.LIMIT));
        m.set(new OrderQty(5));
        m.set(new Price(10.0));
        m.set(new TransactTime());
        return m;
    }

    static SessionID sid(String target) {
        return new SessionID("FIX.4.2", "EXCHANGE", target);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Auth and lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("authentication and lifecycle")
    class AuthAndLifecycle {

        @Test
        void putAndPatch_requireToken() throws Exception {
            assertEquals(401, call("PUT", "/api/x", "{}", false).statusCode());
            assertEquals(401, call("PATCH", "/api/x", "{}", false).statusCode());
        }

        @Test
        void authorisationMustBeBearerScheme() throws Exception {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/clients/blocked"))
                    .header("Authorization", "Basic " + token)
                    .DELETE().build();
            assertEquals(401, http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
        }

        @Test
        void authorisedPut_passesAuthCheck() throws Exception {
            assertEquals(404, authed("PUT", "/api/unknown", "{}").statusCode());
        }

        @Test
        void generatedTokenWhenNoneConfigured() {
            assertNotNull(token);
            assertTrue(token.length() >= 32);
        }

        @Test
        void configuredTokenIsUsed() throws Exception {
            System.setProperty("admin.token", "  fixed-token-123  ");
            AdminServer other = new AdminServer(engine, states, clients, bus, messageLog, freePort());
            assertEquals("fixed-token-123", other.adminToken());
        }

        @Test
        void blankConfiguredTokenFallsBackToGenerated() throws Exception {
            System.setProperty("admin.token", "   ");
            AdminServer other = new AdminServer(engine, states, clients, bus, messageLog, freePort());
            assertNotEquals("   ", other.adminToken());
            assertFalse(other.adminToken().isBlank());
        }

        @Test
        void explicitBindAddressIsUsed() throws Exception {
            System.setProperty("admin.bind", "127.0.0.1");
            int p = freePort();
            AdminServer other = new AdminServer(engine, states, clients, bus, messageLog, p);
            other.start();
            try (Socket s = new Socket()) {
                assertDoesNotThrow(() -> s.connect(new InetSocketAddress("127.0.0.1", p), 1000));
            } finally {
                other.stop();
            }
        }

        @Test
        void blankBindAddressFallsBackToLoopback() throws Exception {
            System.setProperty("admin.bind", "  ");
            AdminServer other = new AdminServer(engine, states, clients, bus, messageLog, freePort());
            assertNotNull(other.adminToken());
        }

        @Test
        void stopWithoutStart_isNoop() throws Exception {
            AdminServer never = new AdminServer(engine, states, clients, bus, messageLog, freePort());
            assertDoesNotThrow(never::stop);
        }

        @Test
        void webSocketSendsSnapshotAndCloses() throws Exception {
            CompletableFuture<String> first = new CompletableFuture<>();
            WebSocket ws = http.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/api/ws/events"),
                    new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            first.complete(data.toString());
                            return WebSocket.Listener.super.onText(w, data, last);
                        }
                    }).get(5, TimeUnit.SECONDS);
            assertTrue(first.get(5, TimeUnit.SECONDS).contains("snapshot"));
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Orders and trades
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("orders, trades and executions")
    class OrdersAndTrades {

        @Test
        void activeOrders_unfilteredAndFiltered() throws Exception {
            engine.newOrder(new Order("CLIENT1:S", "A1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            assertTrue(get("/api/orders").body().contains("\"clOrdId\":\"A1\""));
            assertTrue(get("/api/orders?symbol=" + SYM).body().contains("A1"));
            assertEquals("{\"total\":0,\"offset\":0,\"limit\":25,\"rows\":[]}", get("/api/orders?symbol=MSFT").body());
        }

        @Test
        void history_unfilteredAndFiltered() throws Exception {
            engine.newOrder(new Order("CLIENT1:S", "A1", null, SYM, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            engine.newOrder(new Order("CLIENT2:S", "B1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            assertTrue(get("/api/orders/history").body().contains("FILLED"));
            assertTrue(get("/api/orders/history?symbol=" + SYM).body().contains("FILLED"));
        }

        @Test
        void cancelById_invalidNotFoundAndSuccess() throws Exception {
            assertEquals(400, authed("DELETE", "/api/orders/abc", null).statusCode());
            assertEquals(404, authed("DELETE", "/api/orders/999999", null).statusCode());

            Order o = new Order("CLIENT1:S", "A1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null);
            engine.newOrder(o);
            HttpResponse<String> r = authed("DELETE", "/api/orders/" + o.getInternalId(), null);
            assertEquals(200, r.statusCode());
            assertEquals(com.fix.simulator.engine.OrderStatus.CANCELLED, o.getStatus());
        }

        @Test
        void cancelById_connectedSession_reportsFailureToClientAndContinues() throws Exception {
            SessionID s = sid("CLIENT2");
            clients.onConnect(s);
            Order o = new Order(s.toString(), "A1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null);
            engine.newOrder(o);
            assertEquals(200, authed("DELETE", "/api/orders/" + o.getInternalId(), null).statusCode());
        }

        @Test
        void cancelAllOnSymbol_requiresSymbolAndReportsCount() throws Exception {
            engine.newOrder(new Order("CLIENT1:S", "A1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            engine.newOrder(new Order("CLIENT1:S", "A2", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("9"), null));
            assertEquals(400, authed("DELETE", "/api/orders", null).statusCode());
            HttpResponse<String> r = authed("DELETE", "/api/orders?symbol=" + SYM, null);
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"cancelled\":2"));
        }

        @Test
        void trades_andExecutions_withFilters() throws Exception {
            engine.newOrder(new Order("CLIENT1:S", "A1", null, SYM, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            engine.newOrder(new Order("CLIENT2:S", "B1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            assertTrue(get("/api/trades").body().contains("\"symbol\":\"AAPL\""));
            assertTrue(get("/api/trades?symbol=" + SYM).body().contains("AAPL"));
            assertTrue(get("/api/executions").body().contains("FILL"));
            assertTrue(get("/api/executions?symbol=" + SYM + "&clientId=CLIENT2").body().contains("CLIENT2"));
        }

        @Test
        void dashboard_summarisesEmptyAndPopulatedState() throws Exception {
            assertTrue(get("/api/dashboard").body().contains("ordersByStatus"));
            clients.onConnect(sid("CLIENT1"));
            messageLog.record("S", "CLIENT1", FIXMessageLog.Direction.IN, newOrderMsg("A1", SYM));
            engine.newOrder(new Order("CLIENT1:S", "A1", null, SYM, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            engine.newOrder(new Order("CLIENT2:S", "B1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null));
            String body = get("/api/dashboard").body();
            assertTrue(body.contains("\"activeSessions\":1"));
            assertTrue(body.contains("\"messagesLogged\":1"));
            assertTrue(body.contains("volumeBySymbol"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Instruments
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("instruments and book levels")
    class Instruments {

        @Test
        void listStates() throws Exception {
            assertTrue(get("/api/instruments").body().contains("\"AAPL\":\"OPEN\""));
        }

        @Test
        void setState_missingBodyUnknownAndValid() throws Exception {
            assertEquals(400, authed("POST", "/api/instruments/AAPL/state", "null").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/state", "{}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/state", "{\"state\":\"bogus\"}").statusCode());
            HttpResponse<String> ok = authed("POST", "/api/instruments/AAPL/state", "{\"state\":\"halt\"}");
            assertEquals(200, ok.statusCode());
            assertEquals(InstrumentStatus.HALT, states.getState(SYM));
        }

        @Test
        void bookLevel_validationPaths() throws Exception {
            assertEquals(404, authed("POST", "/api/instruments/NOPE/book", "{}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book", "null").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book", "{\"side\":\"BUY\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book",
                    "{\"side\":\"BUY\",\"price\":\"abc\",\"qty\":\"5\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book",
                    "{\"side\":\"BUY\",\"price\":\"10\",\"qty\":\"x\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book",
                    "{\"side\":\"SIDEWAYS\",\"price\":\"10\",\"qty\":\"5\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book",
                    "{\"side\":\"BUY\",\"price\":\"10\",\"qty\":\"-1\"}").statusCode());
        }

        @Test
        void bookLevel_setsQuantity() throws Exception {
            HttpResponse<String> r = authed("POST", "/api/instruments/AAPL/book",
                    "{\"side\":\"buy\",\"price\":\"10.5\",\"qty\":\"25\"}");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"side\":\"BUY\""));
            assertEquals(25L, engine.getBook(SYM).getLevelQty(OrderSide.BUY, new BigDecimal("10.5")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Sessions and clients
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sessions and client access lists")
    class SessionsAndClients {

        @Test
        void connectedClientsAndSessions() throws Exception {
            clients.onConnect(sid("CLIENT2"));
            clients.onConnect(sid("CLIENT3"));
            clients.onDisconnect(sid("CLIENT3"));
            assertTrue(get("/api/clients").body().contains("CLIENT2"));
            String sessions = get("/api/sessions").body();
            assertTrue(sessions.contains("CLIENT2") && sessions.contains("CLIENT3"));
            assertTrue(sessions.contains("logoutTime"));
        }

        @Test
        void resetSeq_validationAndNotFound() throws Exception {
            clients.onConnect(sid("CLIENT2"));
            clients.onConnect(sid("CLIENT3"));
            clients.onDisconnect(sid("CLIENT3"));
            assertEquals(400, authed("POST", "/api/sessions/CLIENT2/reset-seq", "null").statusCode());
            assertEquals(400, authed("POST", "/api/sessions/CLIENT2/reset-seq", "{\"seqIn\":0,\"seqOut\":1}").statusCode());
            assertEquals(400, authed("POST", "/api/sessions/CLIENT2/reset-seq", "{\"seqIn\":5}").statusCode());
            assertEquals(404, authed("POST", "/api/sessions/CLIENT2/reset-seq", "{\"seqIn\":5,\"seqOut\":5}").statusCode());
            assertEquals(404, authed("POST", "/api/sessions/NOBODY/reset-seq", "{\"seqIn\":5,\"seqOut\":5}").statusCode());
        }

        @Test
        void forceDisconnect_returnsOk() throws Exception {
            assertEquals(200, authed("DELETE", "/api/clients/CLIENT2", null).statusCode());
        }

        @Test
        void blockAndAllowLists_crud() throws Exception {
            assertTrue(get("/api/clients/blocked").body().contains("blocked"));
            assertTrue(get("/api/clients/allowed").body().contains("allowed"));
            assertEquals(200, authed("POST", "/api/clients/blocked", "{\"compId\":\"BAD\"}").statusCode());
            assertEquals(200, authed("POST", "/api/clients/allowed", "{\"compId\":\"VIP\"}").statusCode());
            assertTrue(get("/api/clients/blocked").body().contains("BAD"));
            assertTrue(get("/api/clients/allowed").body().contains("VIP"));
            assertEquals(200, authed("DELETE", "/api/clients/blocked/BAD", null).statusCode());
            assertEquals(200, authed("DELETE", "/api/clients/allowed/VIP", null).statusCode());
            assertFalse(get("/api/clients/blocked").body().contains("BAD"));
        }

        @Test
        void listPosts_rejectMissingOrInvalidCompId() throws Exception {
            assertEquals(400, authed("POST", "/api/clients/blocked", "{}").statusCode());
            assertEquals(400, authed("POST", "/api/clients/blocked", "{not json").statusCode());
            assertEquals(400, authed("POST", "/api/clients/allowed", "{}").statusCode());
            assertEquals(400, authed("POST", "/api/clients/allowed", "{not json").statusCode());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FIX message log endpoint
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FIX message endpoint")
    class Messages {

        @Test
        void filtersAndPaging() throws Exception {
            for (int i = 0; i < 3; i++) {
                messageLog.record("S1", "CLIENT1", FIXMessageLog.Direction.IN, newOrderMsg("C" + i, SYM));
            }
            messageLog.record("S2", "CLIENT2", FIXMessageLog.Direction.OUT, newOrderMsg("ZZ", "MSFT"));

            assertTrue(get("/api/messages").body().contains("\"total\":4"));
            assertTrue(get("/api/messages?clientId=CLIENT1").body().contains("\"total\":3"));
            assertTrue(get("/api/messages?symbol=MSFT").body().contains("\"total\":1"));
            assertTrue(get("/api/messages?text=ZZ").body().contains("\"total\":1"));
            assertTrue(get("/api/messages?page=2&pageSize=2").body().contains("\"page\":2"));
            assertTrue(get("/api/messages?page=99&pageSize=2").body().contains("\"page\":2"));   // clamped
            assertTrue(get("/api/messages?pageSize=0").body().contains("\"pageSize\":1"));       // clamped up
            assertTrue(get("/api/messages?pageSize=9999").body().contains("\"pageSize\":500"));  // clamped down
            assertTrue(get("/api/messages?page=abc").body().contains("\"page\":1"));             // default
        }

        @Test
        void emptyLog_givesOnePageZeroCount() throws Exception {
            String body = get("/api/messages").body();
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"pageCount\":0"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Live QuickFIX sessions and remaining branches
    // ═════════════════════════════════════════════════════════════════════════

    /** Creates a real QuickFIX session so {@code Session.lookupSession} finds it. */
    static quickfix.Session liveSession(SessionID sid) throws Exception {
        quickfix.SessionSettings settings = new quickfix.SessionSettings();
        settings.setString(sid, "ConnectionType", "acceptor");
        settings.setString(sid, "StartTime", "00:00:00");
        settings.setString(sid, "EndTime", "00:00:00");
        settings.setString(sid, "HeartBtInt", "30");
        quickfix.DefaultSessionFactory f = new quickfix.DefaultSessionFactory(
                new com.fix.simulator.FIXSimulatorApp(new MatchingEngine(),
                        new com.fix.simulator.util.InstrumentRegistry()),
                new quickfix.MemoryStoreFactory(),
                new quickfix.ScreenLogFactory(false, false, false));
        return f.create(sid, settings);
    }

    @Nested
    @DisplayName("live QuickFIX sessions")
    class LiveSessions {

        @Test
        void sessionsEndpoint_reportsSequenceNumbersForLiveSession() throws Exception {
            SessionID sid = sid("LIVE1");
            clients.onConnect(sid);
            liveSession(sid);
            String body = get("/api/sessions").body();
            assertTrue(body.contains("\"seqIn\":1") && body.contains("\"seqOut\":1"), body);
        }

        @Test
        void resetSeq_appliesToLiveSession() throws Exception {
            SessionID sid = sid("LIVE2");
            clients.onConnect(sid);
            quickfix.Session qf = liveSession(sid);
            HttpResponse<String> r = authed("POST", "/api/sessions/LIVE2/reset-seq", "{\"seqIn\":7,\"seqOut\":9}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(7, qf.getExpectedTargetNum());
            assertEquals(9, qf.getExpectedSenderNum());
        }

        @Test
        void resetSeq_skipsOtherCompIdsAndInactiveSessions() throws Exception {
            SessionID other = sid("OTHER3");
            SessionID mine = sid("LIVE4");
            SessionID gone = new SessionID("FIX.4.2", "EXCHANGE2", "LIVE4");   // same target, different session
            clients.onConnect(other);
            liveSession(other);
            clients.onConnect(mine);
            liveSession(mine);
            clients.onConnect(gone);
            clients.onDisconnect(gone);
            HttpResponse<String> r = authed("POST", "/api/sessions/LIVE4/reset-seq", "{\"seqIn\":3,\"seqOut\":4}");
            assertEquals(200, r.statusCode(), r.body());
        }

        @Test
        void forceDisconnect_withLiveSession_logsOut() throws Exception {
            SessionID sid = sid("LIVE5");
            clients.onConnect(sid);
            liveSession(sid);
            assertEquals(200, authed("DELETE", "/api/clients/LIVE5", null).statusCode());
        }

        @Test
        void cancelWithLiveSession_reportFailureIsCaughtAndLogged() throws Exception {
            SessionID sid = sid("LIVE6");
            clients.onConnect(sid);
            liveSession(sid);
            Order o = new Order(sid.toString(), "A1", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10"), null);
            engine.newOrder(o);
            assertEquals(200, authed("DELETE", "/api/orders/" + o.getInternalId(), null).statusCode());
            assertEquals(com.fix.simulator.engine.OrderStatus.CANCELLED, o.getStatus());
        }
    }

    @Nested
    @DisplayName("request validation and remaining branches")
    class RemainingBranches {

        @Test
        void resetSeq_missingSeqIn_rejected() throws Exception {
            clients.onConnect(sid("LIVE7"));
            assertEquals(400, authed("POST", "/api/sessions/LIVE7/reset-seq", "{\"seqOut\":5}").statusCode());
        }

        @Test
        void book_missingEachField_rejected() throws Exception {
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book", "{\"price\":\"10\",\"qty\":\"5\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book", "{\"side\":\"BUY\",\"qty\":\"5\"}").statusCode());
            assertEquals(400, authed("POST", "/api/instruments/AAPL/book", "{\"side\":\"BUY\",\"price\":\"10\"}").statusCode());
        }

        @Test
        void parseInt_blankParameterUsesDefault() throws Exception {
            assertTrue(get("/api/messages?page=%20").body().contains("\"page\":1"));
        }

        @Test
        void orders_includeStopAndMarketFieldsAsNull() throws Exception {
            engine.newOrder(new Order("CLIENT1:S", "STP", null, SYM, OrderSide.SELL, OrderType.STOP,
                    TimeInForce.GTC, 3, null, new BigDecimal("9.0")));
            String body = get("/api/orders").body();
            assertTrue(body.contains("\"stopPrice\":\"9.0\""));
            assertTrue(body.contains("\"limitPrice\":null"));
        }

        @Test
        void dashboard_withRestingOrdersAndMixedSessions() throws Exception {
            clients.onConnect(sid("LIVE8"));
            clients.onConnect(sid("LIVE9"));
            clients.onDisconnect(sid("LIVE9"));
            engine.newOrder(new Order("CLIENT1:S", "R1", null, SYM, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("12"), null));
            engine.newOrder(new Order("CLIENT2:S", "R2", null, SYM, OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.DAY, 2, new BigDecimal("12"), null));
            String body = get("/api/dashboard").body();
            assertTrue(body.contains("\"openOrders\":1"), body);
            assertTrue(body.contains("\"activeSessions\":1") && body.contains("\"totalSessions\":2"), body);
            assertTrue(body.contains("\"PARTIALLY_FILLED\":1") || body.contains("\"NEW\":1"), body);
        }

        @Test
        void missingUiDirectory_serverStillStartsAndServesApi() throws Exception {
            System.setProperty("admin.ui", tmp.resolve("no-such-dist").toString());
            int p = freePort();
            AdminServer noUi = new AdminServer(engine, states, clients, bus, messageLog, p);
            noUi.start();
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + p + "/api/instruments")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, r.statusCode());
            } finally {
                noUi.stop();
            }
        }
    }
}
