package com.fix.simulator.defects;

import com.fix.simulator.admin.AdminServer;
import com.fix.simulator.admin.ClientRegistry;
import com.fix.simulator.admin.EventBus;
import com.fix.simulator.admin.FIXMessageLog;
import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.admin.InstrumentStatus;
import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.engine.Order;
import com.fix.simulator.engine.OrderSide;
import com.fix.simulator.engine.OrderType;
import com.fix.simulator.engine.TimeInForce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect regression tests D8 for {@link AdminServer}:
 * <ul>
 *   <li>No authentication on state-changing endpoints.</li>
 *   <li>CORS allows any origin.</li>
 *   <li>The server binds every interface, not only loopback.</li>
 * </ul>
 * Each test runs against a real Javalin server on a free port.
 */
@DisplayName("Defect D8 – admin API: no auth, open CORS, bound to all interfaces")
class AdminSecurityDefectsTest {

    private static final String SYM = "AAPL";
    private static final String EVIL_ORIGIN = "https://evil.example";

    @TempDir Path tmp;

    private MatchingEngine engine;
    private InstrumentStateManager states;
    private AdminServer admin;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void startServer() throws Exception {
        engine = new MatchingEngine();
        engine.registerSymbol(SYM);
        states = new InstrumentStateManager();
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        admin = new AdminServer(engine, states,
                new ClientRegistry(tmp.resolve("blocked-clients.yml").toString()),
                new EventBus(), new FIXMessageLog(), port);
        admin.start();
    }

    @AfterEach
    void stopServer() {
        admin.stop();
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isAuthRejection(int code) { return code == 401 || code == 403; }

    @Nested
    @DisplayName("D8a – unauthenticated state-changing requests are accepted")
    class NoAuthentication {

        @Test
        void unauthenticatedHalt_isRejected() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments/" + SYM + "/state")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"HALT\"}")).build());
            assertTrue(isAuthRejection(r.statusCode()),
                    "unauthenticated POST /state returned " + r.statusCode());               // actual: 200
        }

        @Test
        void unauthenticatedHalt_doesNotChangeInstrumentState() throws Exception {
            send(req("/api/instruments/" + SYM + "/state")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"HALT\"}")).build());
            assertEquals(InstrumentStatus.OPEN, states.getState(SYM),
                    "unauthenticated caller changed trading state");                          // actual: HALT
        }

        @Test
        void unauthenticatedCancelAll_doesNotCancelRestingOrders() throws Exception {
            engine.newOrder(new Order("CLIENT2:S", "S1", null, SYM, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.DAY, 5, new BigDecimal("10.00"), null));
            send(req("/api/orders?symbol=" + SYM).DELETE().build());
            assertEquals(1, engine.getAllActiveOrders(SYM).size(),
                    "unauthenticated DELETE /api/orders removed resting orders");            // actual: 0
        }

        @Test
        void unauthenticatedCancelAll_isRejected() throws Exception {
            HttpResponse<String> r = send(req("/api/orders?symbol=" + SYM).DELETE().build());
            assertTrue(isAuthRejection(r.statusCode()), "status " + r.statusCode());        // actual: 200
        }

        @Test
        void control_readOnlyEndpoint_isAvailableLocally() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments").GET().build());
            assertEquals(200, r.statusCode());
        }

        @Test
        void control_authenticatedStateChangeWithoutBody_returns400() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments/" + SYM + "/state")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + admin.adminToken())
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            assertEquals(400, r.statusCode());
        }

        @Test
        void control_authenticatedHalt_succeedsAndChangesState() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments/" + SYM + "/state")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + admin.adminToken())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"HALT\"}")).build());
            assertEquals(200, r.statusCode());
            assertEquals(InstrumentStatus.HALT, states.getState(SYM));
        }

        @Test
        void wrongToken_isRejected() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments/" + SYM + "/state")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer not-the-token")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"state\":\"HALT\"}")).build());
            assertEquals(401, r.statusCode());
            assertEquals(InstrumentStatus.OPEN, states.getState(SYM));
        }
    }

    @Nested
    @DisplayName("D8b – CORS accepts any origin")
    class OpenCors {

        @Test
        void foreignOrigin_isNotGrantedWildcardAccess() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments").GET()
                    .header("Origin", EVIL_ORIGIN).build());
            String acao = r.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
            assertNotEquals("*", acao, "CORS grants wildcard origin access");               // actual: *
        }

        @Test
        void foreignOrigin_isNotEchoed() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments").GET()
                    .header("Origin", EVIL_ORIGIN).build());
            String acao = r.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
            assertNotEquals(EVIL_ORIGIN, acao, "CORS echoes arbitrary origin");              // actual: echoed
        }

        @Test
        void control_sameOriginRequest_succeeds() throws Exception {
            HttpResponse<String> r = send(req("/api/instruments").GET().build());
            assertEquals(200, r.statusCode());
        }
    }

    @Nested
    @DisplayName("D8c – admin port reachable on non-loopback interfaces")
    class BindAddress {

        private InetAddress nonLoopbackIPv4() throws SocketException {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr;
                }
            }
            return null;
        }

        @Test
        void adminPort_isNotReachableFromNonLoopbackAddress() throws Exception {
            InetAddress ip = nonLoopbackIPv4();
            Assumptions.assumeTrue(ip != null, "no non-loopback IPv4 interface on this host");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 1000);
                fail("admin port " + port + " accepted a connection on " + ip);             // actual: connected
            } catch (IOException expected) {
                // ConnectException / timeout: the desired outcome
            }
        }

        @Test
        void control_adminPort_isReachableOnLoopback() throws IOException {
            try (Socket s = new Socket()) {
                assertDoesNotThrow(() -> s.connect(new InetSocketAddress("127.0.0.1", port), 1000));
            }
        }
    }
}
