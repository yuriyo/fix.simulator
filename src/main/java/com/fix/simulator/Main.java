package com.fix.simulator;

import com.fix.simulator.util.AsyncLogFactory;
import com.fix.simulator.admin.AdminServer;
import com.fix.simulator.admin.ClientRegistry;
import com.fix.simulator.admin.EventBus;
import com.fix.simulator.admin.FIXMessageLog;
import com.fix.simulator.admin.InstrumentStateManager;
import com.fix.simulator.engine.MatchingEngine;
import com.fix.simulator.util.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Entry point for the FIX Exchange Simulator.
 *
 * <p>Usage:
 * <pre>
 *   java -jar fix-simulator.jar [server.cfg] [instruments.yml]
 * </pre>
 *
 * <p>Defaults: {@code config/server.cfg} and {@code config/instruments.yml}.
 *
 * <p>Dynamic sessions: any SenderCompID is accepted; each gets its own
 * FIX session with independent sequence number tracking stored in the
 * {@code store/} directory.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String cfgPath   = args.length > 0 ? args[0] : "config/server.cfg";
        String instrPath = args.length > 1 ? args[1] : "config/instruments.yml";

        // ── Load instruments ────────────────────────────────────────────────
        InstrumentRegistry registry = new InstrumentRegistry();
        registry.load(instrPath);

        // ── Build matching engine ───────────────────────────────────────────
        MatchingEngine engine = new MatchingEngine();
        registry.symbols().forEach(engine::registerSymbol);

        // ── Admin subsystems ────────────────────────────────────────────────
        EventBus               eventBus     = new EventBus();
        InstrumentStateManager stateManager = new InstrumentStateManager();
        ClientRegistry         clientReg    = new ClientRegistry("config/blocked-clients.yml");
        engine.setEventBus(eventBus);
        engine.setStateManager(stateManager);

        // ── Load QuickFIX/J settings ────────────────────────────────────────
        SessionSettings settings;
        try (InputStream in = new FileInputStream(cfgPath)) {
            settings = new SessionSettings(in);
        }

        FIXSimulatorApp app        = new FIXSimulatorApp(engine, registry);
        FIXMessageLog   msgLog     = new FIXMessageLog();
        app.setClientRegistry(clientReg);
        app.setEventBus(eventBus);
        app.setInstrumentStateManager(stateManager);
        app.setMessageLog(msgLog);
        app.startMarketDataPublisher();   // sends BBO updates to market data subscribers every 100 ms
        MessageStoreFactory store  = new FileStoreFactory(settings);
        // FIX session logs are written on a background thread so session threads never wait on disk.
        AsyncLogFactory asyncLog   = new AsyncLogFactory(new FileLogFactory(settings));
        LogFactory logFactory      = asyncLog;
        Runtime.getRuntime().addShutdownHook(new Thread(asyncLog::close, "fix-log-shutdown"));
        MessageFactory msgFactory  = new DefaultMessageFactory();

        // ── Build acceptor with dynamic session support ─────────────────────
        ThreadedSocketAcceptor acceptor = buildAcceptor(
                app, store, settings, logFactory, msgFactory, cfgPath);

        // ── Start ────────────────────────────────────────────────────────────
        acceptor.start();

        int adminPort = Integer.parseInt(System.getProperty("admin.port", "8080"));
        AdminServer adminServer = new AdminServer(engine, stateManager, clientReg, eventBus, msgLog, adminPort);
        adminServer.start();

        int port = getPort(settings);
        log.info("FIX Simulator started on port {} with {} symbols",
                port, registry.symbols().size());
        log.info("Press CTRL-C to stop");

        // ── Shutdown hook ─────────────────────────────────────────────────────
        final ThreadedSocketAcceptor finalAcceptor = acceptor;
        final AdminServer finalAdmin = adminServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down FIX Simulator...");
            finalAdmin.stop();
            finalAcceptor.stop(true);
        }));

        // Park the main thread
        Thread.currentThread().join();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Build acceptor with a DynamicAcceptorSessionProvider so that any
    //  client SenderCompID is accepted without requiring a hard-coded
    //  [SESSION] block per client.
    // ──────────────────────────────────────────────────────────────────────

    private static ThreadedSocketAcceptor buildAcceptor(Application app,
                                                         MessageStoreFactory store,
                                                         SessionSettings settings,
                                                         LogFactory logFactory,
                                                         MessageFactory msgFactory,
                                                         String cfgPath) throws Exception {

        ThreadedSocketAcceptor acceptor =
                new ThreadedSocketAcceptor(app, store, settings, logFactory, msgFactory);

        // Register a DynamicAcceptorSessionProvider for every configured listen address.
        // This lets any client CompID connect; sessions are created on-the-fly and
        // their sequence stores are committed to disk for restore support.
        Iterator<SessionID> it = settings.sectionIterator();
        while (it.hasNext()) {
            SessionID templateSessionId = it.next();
            if (!templateSessionId.getBeginString().isEmpty()) {
                // Use each declared session as a template for dynamic sessions
                List<DynamicAcceptorSessionProvider.TemplateMapping> mappings = List.of(
                        new DynamicAcceptorSessionProvider.TemplateMapping(templateSessionId, templateSessionId)
                );

                int port = getPort(settings);
                acceptor.setSessionProvider(
                        new InetSocketAddress(port),
                        new DynamicAcceptorSessionProvider(settings, mappings, app, store, logFactory, msgFactory));
            }
        }

        return acceptor;
    }

    private static int getPort(SessionSettings settings) {
        try {
            Iterator<SessionID> it = settings.sectionIterator();
            while (it.hasNext()) {
                SessionID sid = it.next();
                try {
                    return (int) settings.getLong(sid, "SocketAcceptPort");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return 9876;
    }
}
