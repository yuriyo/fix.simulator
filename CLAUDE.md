# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working rules

- When showing a code extract or file contents to the user, always include line numbers.

## Commands

Java 17 + Maven (3.8+). Offline builds work once dependencies are cached (`mvn -o`).

```bash
# Build the fat jar (target/fix-simulator-1.0.0-SNAPSHOT.jar)
mvn clean package -q

# Run locally (from repo root: the admin UI is loaded from ./admin-ui/dist relative to CWD)
java -jar target/fix-simulator-1.0.0-SNAPSHOT.jar config/server.cfg config/instruments.yml
java -Dadmin.port=8080 -jar target/fix-simulator-1.0.0-SNAPSHOT.jar   # admin port default 8080

# Tests (JUnit 5 + Mockito)
mvn test
mvn test -Dtest=OrderTest                                    # one test class
mvn test -Dtest='MatchingEngineTest$NewOrderBasic'           # one @Nested group
```

`src/test/java/com/fix/simulator/defects/` holds regression tests for defects D1–D8 (see below). They assert correct behaviour and all pass. Run only them with `mvn test -Dtest='com.fix.simulator.defects.**'`.

Admin UI (React + Vite, `admin-ui/`):

```bash
cd admin-ui && npm install
npm run dev      # Vite dev server; proxies /api and WebSocket to localhost:8080 (run the JVM first)
npm run build    # writes admin-ui/dist, which the JVM serves as static files
```

There is no lint configuration in the repo (no ESLint, Checkstyle or similar), and no frontend tests.

Docker: `./build.sh build | push | build-push [--version X] [--registry R] [--no-cache]`. The Dockerfile is multi-stage (Maven build, Vite build, JRE runtime) and runs as a non-root user.

## Architecture

**Protocol is FIX 4.2 in practice.** `config/server.cfg` sets `BeginString=FIX.4.2`, the handlers import `quickfix.fix42.*`, and the store/log files are named `FIX.4.4-…`. The README and `FIX_SPEC.md` say FIX 4.4. Trust the code and config.

**Startup wiring (`Main.main`).** Read this first, since everything is constructed by hand here and there is no DI container:

1. `InstrumentRegistry` loads `instruments.yml`, then `MatchingEngine.registerSymbol` creates one `OrderBook` per symbol.
2. Admin subsystems: `EventBus`, `InstrumentStateManager`, `ClientRegistry` (persisted to `config/blocked-clients.yml`). They are injected into the engine with setters.
3. `FIXSimulatorApp` (the QuickFIX/J `Application`) is built with the engine and registry, then given the admin pieces through setters.
4. QuickFIX/J `ThreadedSocketAcceptor` with a `DynamicAcceptorSessionProvider` per port. Any SenderCompID is accepted, and each session takes its settings from the `[SESSION]` template in `server.cfg`.
5. `AdminServer` (Javalin) starts on `admin.port`. The main thread then parks.

**Inbound FIX path.** `FIXSimulatorApp.fromApp` → `FIXMessageLog.record` → `crack()` dispatches on MsgType to one of `NewOrderHandler`, `CancelHandler`, `CancelReplaceHandler`, `QuoteHandler` (Quote and MassQuote). Handlers parse FIX fields, check `InstrumentStateManager` (`HALT` and `QUOTES_ONLY` reject orders), call `MatchingEngine`, convert each `Execution` with `ExecReportHelper.build`, and send it through `ResponseSender`. The real sender is `Session.sendToTarget`. Handlers stay FIX-only and never touch the book directly.

**Matching engine (`engine/`).** `MatchingEngine` is the only entry point for order operations. It owns a `ConcurrentHashMap<symbol, OrderBook>` and a `MarketState` (NBBO and last price). Its private helpers `processLimit`, `processMarket`, `processStop` and `afterFills` run the flow:
- `afterFills` is where last price is updated, stops are checked, executions and trades are logged, and events are published.
- A triggered stop re-enters `afterFills` through `processTrigger`.
- `OrderBook` holds `TreeMap` price levels with `ArrayDeque` FIFO queues, one `synchronized` monitor per book, plus `pendingStops` and a `sessionId:clOrdId` index. `match()` implements price-time priority, IOC, and FOK. FOK checks available liquidity first and leaves the book untouched if it cannot fill.
- `Order` is mutable state guarded by the book lock. `Execution` is an immutable report record.

**Quotes are client-driven liquidity.** `MatchingEngine.onQuote` inserts a quote's bid and ask as resting GTC limit orders with clOrdIds `q-bid-<quoteId>` and `q-ask-<quoteId>`. These are ordinary book orders, so they match against other clients' orders. `MarketState` is updated from quotes too. Quote-generated fills do not go through `afterFills`, which matters for the trade log and stops (see defect D3).

**Admin plane (`admin/`).** `AdminServer` is a Javalin REST and WebSocket server. Mutating requests (POST/PUT/PATCH/DELETE) need `Authorization: Bearer <token>`. The token comes from `-Dadmin.token` / `ADMIN_TOKEN`, or is generated and logged at startup. There is no CORS, and it binds `127.0.0.1` unless `-Dadmin.bind` / `ADMIN_BIND` is set (the Docker image sets `0.0.0.0`). The WebSocket feed is still unauthenticated. The UI prompts for the token once per session (`admin-ui/src/main.jsx` wraps `fetch`). Admin operations call the engine directly, for example `adminCancel` and `setAdminBookLevel`, and send FIX ExecutionReports to the affected client sessions. `EventBus` batches events on a single-thread ticker every 200 ms. It also snapshots book levels only for symbols marked dirty, so the UI stays cheap under load.

**Persistence is split on purpose.** FIX session sequence numbers and message store go to `store/` and `logs/`, through QuickFIX `FileStoreFactory` and `FileLogFactory`. Order and trade state exists only in memory and is lost on restart. `ResetOnLogon=N` keeps sequences across reconnects.

**Tests mirror the source packages.** The `defects/` package is a deliberate exception. It reproduces open bugs and fails on purpose. Mockito is used only for handler tests. Engine and book tests use real objects. `MatchingAtomicityDefectsTest` swaps the engine's private `books` map for a hooked `OrderBook` via reflection to force race windows deterministically. Keep that approach in mind if you touch `MatchingEngine.books`.

## Fixed defects (regression-tested)

- **D1** `onQuote` no longer inserts a quote twice: the quote order matches first and rests only once, and fully filled quotes never enter the book.
- **D2** A session's new quote withdraws its previous resting quote on that symbol (`MatchingEngine.restingQuotes`).
- **D3** Quote fills go through `afterFills`, so they are logged as trades, set last price and trigger stops.
- **D4** `afterFills` logs its own fills before triggering stops, so nested stop fills are never logged twice.
- **D5** Compound book operations run under `synchronized (book)` in `MatchingEngine`.
- **D6** Trade, execution and order history are capped (oldest evicted first). `getTradedSymbols` iterates under the trade-log monitor.
- **D7** `NewOrderHandler` rejects non-positive or fractional quantities, quantities off the lot size, and prices or stop prices off the tick size.
- **D8** Admin auth, CORS and bind address are fixed as described under Admin plane.

Still open, found while fixing: `NewOrderHandler` and `QuoteHandler` send every execution returned by the engine to the *incoming* session. That includes passive-side fills and fills of triggered stops that belong to other clients.
