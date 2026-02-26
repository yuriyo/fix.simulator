package com.fix.simulator;

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

        // ── Load QuickFIX/J settings ────────────────────────────────────────
        SessionSettings settings;
        try (InputStream in = new FileInputStream(cfgPath)) {
            settings = new SessionSettings(in);
        }

        FIXSimulatorApp app        = new FIXSimulatorApp(engine, registry);
        MessageStoreFactory store  = new FileStoreFactory(settings);
        LogFactory logFactory      = new FileLogFactory(settings);
        MessageFactory msgFactory  = new DefaultMessageFactory();

        // ── Build acceptor with dynamic session support ─────────────────────
        ThreadedSocketAcceptor acceptor = buildAcceptor(
                app, store, settings, logFactory, msgFactory, cfgPath);

        // ── Start ────────────────────────────────────────────────────────────
        acceptor.start();

        int port = getPort(settings);
        log.info("FIX Simulator started on port {} with {} symbols",
                port, registry.symbols().size());
        log.info("Press CTRL-C to stop");

        // ── Shutdown hook ─────────────────────────────────────────────────────
        final ThreadedSocketAcceptor finalAcceptor = acceptor;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down FIX Simulator...");
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
