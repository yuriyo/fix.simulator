package com.fix.simulator.admin;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderSide;
import com.fix.simulator.engine.OrderType;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Paging parameters, the page envelope, and lifetime dashboard totals over HTTP. */
@DisplayName("AdminServer – paged API and lifetime totals")
class AdminPagingApiTest {

    private static final String SYM = "AAPL";

    @TempDir Path tmp;

    private MatchingEngine engine;
    private AdminServer admin;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void start() throws Exception {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
        InstrumentStateManager states = new InstrumentStateManager();
        ClientRegistry clients = new ClientRegistry(tmp.resolve("clients.yml").toString());
        EventBus bus = new EventBus();
        FIXMessageLog messageLog = new FIXMessageLog();
        engine.setEventBus(bus);
        engine.setStateManager(states);
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        admin = new AdminServer(engine, states, clients, bus, messageLog, port);
        admin.start();
    }

    @AfterEach
    void stop() {
        admin.stop();
        System.clearProperty("admin.token");
        System.clearProperty("admin.bind");
        System.clearProperty("admin.ui");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void iocOrders(int n) {
        for (int i = 0; i < n; i++) {
            engine.newOrder(new Order("FIX.4.2:EXCHANGE->C1", "I" + i, null, SYM, OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.IOC, 1, new BigDecimal("1.00"), null));
        }
    }

    @Test
    @DisplayName("executions page carries total, offset, limit and rows")
    void executionsEnvelope() throws Exception {
        iocOrders(3);   // 6 executions
        String body = get("/api/executions?offset=2&limit=2").body();
        assertTrue(body.contains("\"total\":6"), body);
        assertTrue(body.contains("\"offset\":2"), body);
        assertTrue(body.contains("\"limit\":2"), body);
        assertTrue(body.contains("\"rows\":["), body);
    }

    @Test
    @DisplayName("orders page returns only the requested window")
    void ordersWindow() throws Exception {
        iocOrders(5);
        String body = get("/api/orders/history?offset=0&limit=2").body();
        assertTrue(body.contains("\"total\":5"), body);
        assertEquals(2, body.split("\"orderId\"", -1).length - 1, body);
    }

    @Test
    @DisplayName("trades page: empty log gives total 0")
    void emptyTrades() throws Exception {
        assertEquals("{\"total\":0,\"offset\":0,\"limit\":25,\"rows\":[]}", get("/api/trades").body());
    }

    @Test
    @DisplayName("invalid offset or limit is rejected with HTTP 400")
    void badParametersRejected() throws Exception {
        assertEquals(400, get("/api/orders?offset=-1").statusCode());
        assertEquals(400, get("/api/orders?offset=abc").statusCode());
        assertEquals(400, get("/api/trades?limit=0").statusCode());
        assertEquals(400, get("/api/trades?limit=501").statusCode());
        assertEquals(400, get("/api/executions?limit=x").statusCode());
        assertTrue(get("/api/trades?limit=0").body().contains("limit must be between 1 and 500"));
    }

    @Test
    @DisplayName("blank parameters fall back to the defaults")
    void defaults() throws Exception {
        assertEquals(200, get("/api/executions?offset=&limit=").statusCode());
        assertTrue(get("/api/executions?offset=&limit=").body().contains("\"limit\":25"));
    }

    @Test
    @DisplayName("dashboard totals are lifetime, not the retained window")
    void dashboardLifetime() throws Exception {
        int n = 10_000 + 50;   // past the 10,000-order retained window
        iocOrders(n);
        String body = get("/api/dashboard").body();
        assertTrue(body.contains("\"totalOrders\":" + n), "totalOrders should be " + n);
        assertTrue(body.contains("\"CANCELLED\":" + n), "ordersByStatus CANCELLED should be " + n);
        assertTrue(body.contains("\"execCount\":" + 2L * n), "execCount should be " + (2L * n));
    }
}
