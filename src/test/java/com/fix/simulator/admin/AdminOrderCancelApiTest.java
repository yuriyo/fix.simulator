package com.fix.simulator.admin;

import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.OrderSide;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancelling an admin book level from the Orders tab (DELETE /api/orders/{id}).
 * Admin levels are resting orders with the session "ADMIN"; they must be cancellable by id.
 */
@DisplayName("AdminServer – cancel admin book levels by order id")
class AdminOrderCancelApiTest {

    private static final String SYM = "MSFT";

    @TempDir Path tmp;

    private MatchingEngine engine;
    private AdminServer admin;
    private String token;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void start() throws Exception {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
        InstrumentStateManager states = new InstrumentStateManager();
        ClientRegistry clients = new ClientRegistry(tmp.resolve("clients.yml").toString());
        EventBus bus = new EventBus();
        engine.setEventBus(bus);
        engine.setStateManager(states);
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        admin = new AdminServer(engine, states, clients, bus, new FIXMessageLog(), port);
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

    private HttpResponse<String> send(String method, String path, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (auth) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** orderId of the first row of GET /api/orders?symbol=MSFT. */
    private long firstActiveOrderId() throws Exception {
        String body = send("GET", "/api/orders?symbol=" + SYM, false).body();
        Matcher m = Pattern.compile("\"orderId\":(\\d+)").matcher(body);
        assertTrue(m.find(), "no active order in " + body);
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("an admin level listed in the Orders tab can be cancelled by its id")
    void cancelAdminLevel() throws Exception {
        engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 100);
        long id = firstActiveOrderId();

        HttpResponse<String> r = send("DELETE", "/api/orders/" + id, true);
        assertEquals(200, r.statusCode(), r.body());

        assertTrue(engine.getAllActiveOrders(SYM).isEmpty(), "order is still resting");
    }

    @Test
    @DisplayName("both sides of a symbol can be cancelled independently")
    void cancelBothSides() throws Exception {
        engine.setAdminBookLevel(SYM, OrderSide.BUY, new BigDecimal("9.00"), 100);
        engine.setAdminBookLevel(SYM, OrderSide.SELL, new BigDecimal("11.00"), 100);
        assertEquals(2, engine.getAllActiveOrders(SYM).size());

        assertEquals(200, send("DELETE", "/api/orders/" + firstActiveOrderId(), true).statusCode());
        assertEquals(200, send("DELETE", "/api/orders/" + firstActiveOrderId(), true).statusCode());
        assertTrue(engine.getAllActiveOrders(SYM).isEmpty());
    }

    @Test
    @DisplayName("an unknown order id still returns 404")
    void unknownIdStill404() throws Exception {
        assertEquals(404, send("DELETE", "/api/orders/999999", true).statusCode());
    }
}
