package com.fix.simulator.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fix.simulator.engine.Execution;
import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderStatus;
import com.fix.simulator.util.ExecReportHelper;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Embedded HTTP + WebSocket admin server (default port 8080).
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/ws/events}      – WebSocket feed (book, trades, orders, sessions)</li>
 *   <li>{@code GET /api/orders}          – Active orders (filter: ?symbol=)</li>
 *   <li>{@code GET /api/orders/history}  – Completed orders (filter: ?symbol=)</li>
 *   <li>{@code DELETE /api/orders/:id}   – Force-cancel one order</li>
 *   <li>{@code DELETE /api/orders}       – Force-cancel all orders on ?symbol=</li>
 *   <li>{@code GET /api/instruments}     – Instrument states</li>
 *   <li>{@code POST /api/instruments/:symbol/state} – Set state (OPEN/HALT/QUOTES_ONLY)</li>
 *   <li>{@code GET /api/clients}         – Connected FIX clients</li>
 *   <li>{@code DELETE /api/clients/:compId} – Force disconnect</li>
 *   <li>{@code GET/POST/DELETE /api/clients/blocked} – Blocked list management</li>
 *   <li>{@code GET/POST/DELETE /api/clients/allowed} – Allowed list management</li>
 * </ul>
 *
 * <p>Static React build is served from {@code admin-ui/dist} (relative to the working
 * directory) when present, falling back gracefully if not built yet.
 *
 * <p>Port: configured via system property {@code admin.port}, default {@code 8080}.
 */
public class AdminServer {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final MatchingEngine         engine;
    private final InstrumentStateManager stateManager;
    private final ClientRegistry         clientRegistry;
    private final EventBus               eventBus;
    private final FIXMessageLog          messageLog;
    private final int                    port;

    private Javalin app;

    /** Bearer token required for every mutating request (POST / PUT / PATCH / DELETE). */
    private final String adminToken;

    /** Bind address. Loopback unless overridden with {@code -Dadmin.bind} or {@code ADMIN_BIND}. */
    private final String bindAddress;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AdminServer(MatchingEngine engine,
                       InstrumentStateManager stateManager,
                       ClientRegistry clientRegistry,
                       EventBus eventBus,
                       FIXMessageLog messageLog,
                       int port) {
        this.engine        = engine;
        this.stateManager  = stateManager;
        this.clientRegistry = clientRegistry;
        this.eventBus      = eventBus;
        this.messageLog    = messageLog;
        this.port          = port;
        this.adminToken    = resolveAdminToken();
        String bind        = System.getProperty("admin.bind", System.getenv("ADMIN_BIND"));
        this.bindAddress  = (bind == null || bind.isBlank()) ? "127.0.0.1" : bind.trim();
    }

    /** Token that mutating admin requests must present as {@code Authorization: Bearer <token>}. */
    public String adminToken() { return adminToken; }

    private static String resolveAdminToken() {
        String configured = System.getProperty("admin.token", System.getenv("ADMIN_TOKEN"));
        if (configured != null && !configured.isBlank()) return configured.trim();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        log.warn("No admin.token / ADMIN_TOKEN configured; generated one for this run: {}", generated);
        return generated;
    }

    private static boolean isMutating(io.javalin.http.HandlerType type) {
        return type == io.javalin.http.HandlerType.POST
            || type == io.javalin.http.HandlerType.PUT
            || type == io.javalin.http.HandlerType.PATCH
            || type == io.javalin.http.HandlerType.DELETE;
    }

    private boolean isAuthorised(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return false;
        byte[] presented = authorizationHeader.substring("Bearer ".length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, adminToken.getBytes(StandardCharsets.UTF_8));
    }

    public void start() {
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson().updateMapper(m -> {
                m.registerModule(new JavaTimeModule());
                m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }));

            // No CORS plugin: the UI is served from this origin, so cross-origin access is denied.

            // Serve React build output when present
            Path uiDist = Path.of(System.getProperty("admin.ui", "admin-ui/dist"));
            if (Files.exists(uiDist)) {
                config.staticFiles.add(sf -> {
                    sf.directory  = uiDist.toAbsolutePath().toString();
                    sf.location   = Location.EXTERNAL;
                    sf.hostedPath = "/";
                });
                log.info("Admin UI static files served from {}", uiDist.toAbsolutePath());
            }
        });

        // ── Authentication: every mutating request needs the admin bearer token ──
        app.before(ctx -> {
            if (isMutating(ctx.method()) && !isAuthorised(ctx.header("Authorization")))
                throw new io.javalin.http.UnauthorizedResponse("Missing or invalid admin token");
        });

        // ── WebSocket events ──────────────────────────────────────────────────
        app.ws("/api/ws/events", ws -> {
            ws.onConnect(ctx -> {
                eventBus.subscribe(ctx);
                log.debug("Admin WS connected: {}", ctx.sessionId());
                // Send a snapshot of current instrument states on connect
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("type", "snapshot");
                for (String sym : engine.getSymbols()) {
                    snapshot.put(sym, stateManager.getState(sym).name());
                }
                ctx.send(mapper.writeValueAsString(snapshot));
                // Send current book state for every symbol so the UI
                // renders existing resting orders immediately on (re)connect.
                for (String sym : engine.getSymbols()) {
                    var bk = engine.getBook(sym);
                    Map<String, Object> bookEvt = new LinkedHashMap<>();
                    bookEvt.put("type",   "book_update");
                    bookEvt.put("symbol", sym);
                    bookEvt.put("bids",   bk.getBidLevels(100));
                    bookEvt.put("asks",   bk.getAskLevels(100));
                    ctx.send(mapper.writeValueAsString(bookEvt));
                }
            });
            ws.onClose(ctx -> {
                eventBus.unsubscribe(ctx);
                log.debug("Admin WS disconnected: {}", ctx.sessionId());
            });
        });

        // ── Orders ────────────────────────────────────────────────────────────
        app.get("/api/orders", ctx -> {
            String symbol = ctx.queryParam("symbol");
            List<Map<String, Object>> orders = engine.getAllActiveOrders().stream()
                    .filter(o -> symbol == null || o.getSymbol().equals(symbol))
                    .map(this::orderToMap)
                    .collect(Collectors.toList());
            ctx.json(orders);
        });

        app.get("/api/orders/history", ctx -> {
            String symbol = ctx.queryParam("symbol");
            ctx.json(engine.getCompletedOrders(symbol).stream()
                    .map(this::orderToMap)
                    .collect(Collectors.toList()));
        });

        app.delete("/api/orders/{orderId}", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("orderId")); }
            catch (NumberFormatException e) { ctx.status(400).json(error("Invalid orderId")); return; }

            Order order = engine.findOrderById(id);
            if (order == null) { ctx.status(404).json(error("Order not found")); return; }

            adminCancel(order);
            ctx.json(Map.of("status", "ok", "orderId", id));
        });

        app.delete("/api/orders", ctx -> {
            String symbol = ctx.queryParam("symbol");
            if (symbol == null) { ctx.status(400).json(error("?symbol= required")); return; }
            int cancelled = adminCancelAllOnSymbol(symbol);
            ctx.json(Map.of("cancelled", cancelled, "symbol", symbol));
        });

        // ── Trades ────────────────────────────────────────────────────────────
        app.get("/api/trades", ctx -> {
            String symbol = ctx.queryParam("symbol");
            ctx.json(engine.getTradeLog(symbol));
        });

        // ── Executions ───────────────────────────────────────────────────────
        app.get("/api/executions", ctx -> {
            String symbol   = ctx.queryParam("symbol");
            String clientId = ctx.queryParam("clientId");
            ctx.json(engine.getExecutionLog(symbol, clientId));
        });

        // ── Instruments ───────────────────────────────────────────────────────
        app.get("/api/instruments", ctx -> {
            Map<String, String> result = new LinkedHashMap<>();
            for (String sym : engine.getSymbols()) {
                result.put(sym, stateManager.getState(sym).name());
            }
            ctx.json(result);
        });

        app.post("/api/instruments/{symbol}/state", ctx -> {
            String symbol  = ctx.pathParam("symbol");
            @SuppressWarnings("unchecked")
            Map<String, String> body = jsonObject(ctx);
            String stateStr = body == null ? null : body.get("state");
            if (stateStr == null) { ctx.status(400).json(error("body must contain 'state'")); return; }
            try {
                InstrumentStatus status = InstrumentStatus.valueOf(stateStr.toUpperCase(Locale.ROOT));
                stateManager.setState(symbol, status);
                eventBus.publish(Map.of("type", "instrument_state", "symbol", symbol, "state", status.name()));
                ctx.json(Map.of("symbol", symbol, "state", status.name()));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(error("Unknown state '" + stateStr + "'. Valid: OPEN, HALT, QUOTES_ONLY"));
            }
        });

        // ── Clients ───────────────────────────────────────────────────────────
        app.get("/api/clients", ctx -> ctx.json(clientRegistry.getConnectedClients()));
        // ── Sessions ──────────────────────────────────────────────────
        app.get("/api/sessions", ctx -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ClientRegistry.SessionRecord rec : clientRegistry.getSessionHistory()) {
                Map<String, Integer> stats = engine.getOrderStats(rec.sessionId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId",   rec.sessionId);
                m.put("compId",      rec.compId);
                m.put("loginTime",   rec.loginTime.toString());
                m.put("logoutTime",  rec.logoutTime != null ? rec.logoutTime.toString() : null);
                m.put("active",      rec.isActive());
                m.put("orders",      stats.get("orders"));
                m.put("open",        stats.get("open"));
                m.put("cancelled",   stats.get("cancelled"));
                m.put("filled",      stats.get("filled"));
                // Sequence numbers (available from QuickFIX/J Session object)
                SessionID sid = clientRegistry.resolve(rec.sessionId);
                Session qfSession = sid != null ? Session.lookupSession(sid) : null;
                m.put("seqIn",  qfSession != null ? qfSession.getExpectedTargetNum() : null);
                m.put("seqOut", qfSession != null ? qfSession.getExpectedSenderNum() : null);
                result.add(m);
            }
            ctx.json(result);
        });

        // ── Session sequence reset ─────────────────────────────────────
        app.post("/api/sessions/{compId}/reset-seq", ctx -> {
            String compId = ctx.pathParam("compId");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = jsonObject(ctx);
            if (body == null) { ctx.status(400).json(error("body required")); return; }
            int seqIn  = parseInt(body.get("seqIn")  != null ? body.get("seqIn").toString()  : null, -1);
            int seqOut = parseInt(body.get("seqOut") != null ? body.get("seqOut").toString() : null, -1);
            if (seqIn < 1 || seqOut < 1) {
                ctx.status(400).json(error("seqIn and seqOut must be >= 1")); return;
            }
            boolean found = false;
            for (ClientRegistry.SessionRecord rec : clientRegistry.getSessionHistory()) {
                if (!rec.compId.equals(compId)) continue;
                SessionID sid = clientRegistry.resolve(rec.sessionId);
                if (sid == null) continue;
                Session qfSession = Session.lookupSession(sid);
                if (qfSession == null) continue;
                try {
                    qfSession.setNextTargetMsgSeqNum(seqIn);
                    qfSession.setNextSenderMsgSeqNum(seqOut);
                    found = true;
                } catch (Exception e) {
                    ctx.status(500).json(error("Reset failed: " + e.getMessage())); return;
                }
            }
            if (!found) { ctx.status(404).json(error("No active session for " + compId)); return; }
            ctx.json(Map.of("status", "ok", "compId", compId, "seqIn", seqIn, "seqOut", seqOut));
        });

        // ── Admin book level management ────────────────────────────────
        app.post("/api/instruments/{symbol}/book", ctx -> {
            String symbol = ctx.pathParam("symbol");
            if (!engine.hasSymbol(symbol)) { ctx.status(404).json(error("Unknown symbol: " + symbol)); return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = jsonObject(ctx);
            if (body == null) { ctx.status(400).json(error("body required")); return; }
            String sideStr  = body.get("side")  != null ? body.get("side").toString()  : null;
            String priceStr = body.get("price") != null ? body.get("price").toString() : null;
            String qtyStr   = body.get("qty")   != null ? body.get("qty").toString()   : null;
            if (sideStr == null || priceStr == null || qtyStr == null) {
                ctx.status(400).json(error("side, price and qty are required")); return;
            }
            com.fix.simulator.engine.OrderSide side;
            java.math.BigDecimal price;
            long qty;
            try {
                side  = com.fix.simulator.engine.OrderSide.valueOf(sideStr.toUpperCase(Locale.ROOT));
                price = new java.math.BigDecimal(priceStr);
                qty   = Long.parseLong(qtyStr);
            } catch (NumberFormatException e) {
                ctx.status(400).json(error("Invalid price or qty")); return;
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(error("Invalid side '" + sideStr + "'. Use BUY or SELL")); return;
            }
            if (qty < 0) { ctx.status(400).json(error("qty must be >= 0")); return; }
            engine.setAdminBookLevel(symbol, side, price, qty);
            ctx.json(Map.of("status", "ok", "symbol", symbol,
                    "side", side.name(), "price", price.toPlainString(), "qty", qty));
        });

        // ── FIX Messages ──────────────────────────────────────────────
        app.get("/api/messages", ctx -> {
            String compId  = ctx.queryParam("clientId");
            String symbol  = ctx.queryParam("symbol");
            String text    = ctx.queryParam("text");
            int    page    = parseInt(ctx.queryParam("page"),     1);
            int    pgSize  = parseInt(ctx.queryParam("pageSize"), 100);
            pgSize = Math.min(Math.max(pgSize, 1), 500);

            List<FIXMessageLog.Entry> all = messageLog.getMessages(compId, symbol, text);
            int total = all.size();
            int pageCount = (int) Math.ceil((double) total / pgSize);
            page = Math.max(1, Math.min(page, Math.max(1, pageCount)));

            List<FIXMessageLog.Entry> page_data = all.stream()
                    .skip((long)(page - 1) * pgSize)
                    .limit(pgSize)
                    .collect(Collectors.toList());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (FIXMessageLog.Entry e : page_data) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        e.id);
                m.put("sessionId", e.sessionId);
                m.put("compId",    e.compId);
                m.put("direction", e.direction.name());
                m.put("msgType",   e.msgType);
                m.put("symbol",    e.symbol);
                m.put("time",      e.time.toString());
                m.put("tags",      e.getTags());
                rows.add(m);
            }
            ctx.json(Map.of(
                    "total",     total,
                    "page",      page,
                    "pageSize",  pgSize,
                    "pageCount", pageCount,
                    "messages",  rows));
        });
        app.delete("/api/clients/{compId}", ctx -> {
            clientRegistry.forceDisconnect(ctx.pathParam("compId"));
            ctx.json(Map.of("status", "ok"));
        });

        app.get("/api/clients/blocked",    ctx -> ctx.json(Map.of("blocked", clientRegistry.getBlocked())));
        app.get("/api/clients/allowed",    ctx -> ctx.json(Map.of("allowed", clientRegistry.getAllowed())));

        app.post("/api/clients/blocked", ctx -> {
            String compId = extractCompId(ctx.body());
            if (compId == null) { ctx.status(400).json(error("'compId' required")); return; }
            clientRegistry.block(compId);
            ctx.json(Map.of("status", "ok", "compId", compId));
        });

        app.post("/api/clients/allowed", ctx -> {
            String compId = extractCompId(ctx.body());
            if (compId == null) { ctx.status(400).json(error("'compId' required")); return; }
            clientRegistry.allow(compId);
            ctx.json(Map.of("status", "ok", "compId", compId));
        });

        app.delete("/api/clients/blocked/{compId}", ctx -> {
            clientRegistry.unblock(ctx.pathParam("compId"));
            ctx.json(Map.of("status", "ok"));
        });

        app.delete("/api/clients/allowed/{compId}", ctx -> {
            clientRegistry.disallow(ctx.pathParam("compId"));
            ctx.json(Map.of("status", "ok"));
        });

        // ── Dashboard summary ──────────────────────────────────────────────
        app.get("/api/dashboard", ctx -> {
            Map<String, Object> dash = new LinkedHashMap<>();

            // -- Order counts by status --
            List<Order> allActive = engine.getAllActiveOrders();
            long totalOrders    = engine.getExecutionLog(null, null).stream()
                    .filter(e -> "NEW".equals(e.get("execType"))).count();
            long openOrders     = allActive.size();
            List<Map<String, Object>> allTrades = engine.getTradeLog(null);
            long tradeCount     = allTrades.size();
            long execCount      = engine.getExecutionLog(null, null).size();

            dash.put("totalOrders",  totalOrders);
            dash.put("openOrders",   openOrders);
            dash.put("tradeCount",   tradeCount);
            dash.put("execCount",    execCount);

            // -- Order status breakdown (for donut chart), counted per order --
            Map<String, Long> ordersByStatus = new LinkedHashMap<>();
            for (OrderStatus st : OrderStatus.values()) ordersByStatus.put(st.name(), 0L);
            // Walk all tracked orders
            for (Order o : allActive) {
                ordersByStatus.merge(o.getStatus().name(), 1L, Long::sum);
            }
            for (String sym : engine.getSymbols()) {
                for (Order o : engine.getCompletedOrders(sym)) {
                    ordersByStatus.merge(o.getStatus().name(), 1L, Long::sum);
                }
            }
            dash.put("ordersByStatus", ordersByStatus);

            // -- Sessions --
            int activeSessions = 0, totalSessions = 0;
            for (ClientRegistry.SessionRecord rec : clientRegistry.getSessionHistory()) {
                totalSessions++;
                if (rec.isActive()) activeSessions++;
            }
            dash.put("activeSessions", activeSessions);
            dash.put("totalSessions",  totalSessions);

            // -- Volume by symbol (trade count + total qty) --
            Map<String, Object> volumeBySymbol = new LinkedHashMap<>();
            for (String sym : engine.getSymbols()) {
                List<Map<String, Object>> symTrades = engine.getTradeLog(sym);
                long totalQty = 0;
                for (Map<String, Object> t : symTrades) {
                    totalQty += ((Number) t.get("qty")).longValue();
                }
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("trades", symTrades.size());
                sv.put("volume", totalQty);
                volumeBySymbol.put(sym, sv);
            }
            dash.put("volumeBySymbol", volumeBySymbol);

            // -- Instrument states --
            Map<String, String> instrumentStates = new LinkedHashMap<>();
            for (String sym : engine.getSymbols()) {
                instrumentStates.put(sym, stateManager.getState(sym).name());
            }
            dash.put("instrumentStates", instrumentStates);

            // -- Recent trades (last 20 for sparkline) --
            int tradeLogSize = allTrades.size();
            List<Map<String, Object>> recentTrades = allTrades.subList(
                    Math.max(0, tradeLogSize - 50), tradeLogSize);
            dash.put("recentTrades", recentTrades);

            // -- Messages logged --
            dash.put("messagesLogged", messageLog.getMessages(null, null).size());

            ctx.json(dash);
        });

        app.start(bindAddress, port);
        log.info("Admin server started on http://{}:{}", bindAddress, port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("Admin server stopped");
        }
    }

    // ── Admin order operations ────────────────────────────────────────────────

    private void adminCancel(Order order) {
        List<Execution> execs = engine.cancelOrder(
                order.getSessionId(), order.getClOrdId(),
                "ADMIN-" + order.getClOrdId(), order.getSymbol());
        sendFixReports(execs, order.getSessionId());
    }

    private int adminCancelAllOnSymbol(String symbol) {
        List<Order> active = engine.getAllActiveOrders(symbol);
        for (Order o : active) adminCancel(o);
        return active.size();
    }

    private void sendFixReports(List<Execution> execs, String sessionIdStr) {
        SessionID sid = clientRegistry.resolve(sessionIdStr);
        if (sid == null) return;
        for (Execution exec : execs) {
            try {
                Session.sendToTarget(ExecReportHelper.build(exec), sid);
            } catch (Exception e) {
                log.warn("Admin: failed to send FIX report to {}: {}", sessionIdStr, e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parse the request body as a JSON object. Returns {@code null} for a missing, malformed or
     * non-object body (for example the literal {@code null}), so callers can answer 400 instead of 500.
     */
    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> jsonObject(io.javalin.http.Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractCompId(String body) {
        try {
            Map<String, String> m = new ObjectMapper().readValue(body, Map.class);
            return m.get("compId");
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> orderToMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId",    o.getInternalId());
        m.put("clOrdId",    o.getClOrdId());
        m.put("sessionId",  o.getSessionId());
        m.put("symbol",     o.getSymbol());
        m.put("side",       o.getSide().name());
        m.put("type",       o.getType().name());
        m.put("tif",        o.getTif().name());
        m.put("status",     o.getStatus().name());
        m.put("openQty",    o.getOpenQty());
        m.put("filledQty",  o.getFilledQty());
        m.put("totalQty",   o.getTotalQty());
        m.put("limitPrice", o.getLimitPrice()  != null ? o.getLimitPrice().toPlainString()  : null);
        m.put("stopPrice",  o.getStopPrice()   != null ? o.getStopPrice().toPlainString()   : null);
        m.put("createTime", o.getCreateTime().toString());
        return m;
    }

    private Map<String, String> error(String msg) {
        return Map.of("error", msg);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
