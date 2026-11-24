# FIX Exchange Simulator

A FIX 4.4 exchange simulator built with **Java 17** and **QuickFIX/J 2.3.1**.

---

## Features

| Category | Details |
|---|---|
| Protocol | FIX 4.4 over TCP |
| Session layer | QuickFIX/J (heartbeat, sequence store, resend, logon/logout) |
| Multiple clients | Dynamic session acceptance – any CompID connects without config changes |
| Sequence restore | Per-session `FileStore` in `store/`; resend replay on reconnect |
| Order types | LIMIT, IOC (Immediate or Cancel), FOK (Fill or Kill), STOP, STOP LIMIT |
| Operations | New Order, Cancel, Cancel-Replace (modify qty / price) |
| Market data | Client-driven via FIX Quote (S) and MassQuote (i) messages |
| Matching | Price-Time FIFO central limit order book, one book per instrument |
| Stop triggering | Stops checked and triggered after every fill |
| Performance | ~2–10K messages/s (per-symbol synchronized OrderBook, Java NIO via MINA) |
| Persistence | Real-time in-memory only (FIX session sequences are stored, not order state) |

---

## Quick Start

### Option A – Docker (recommended)

```bash
# Build locally as fix-simulator:<version> (no registry, no namespace by default)
./build.sh build --version 1.0.0

# Build and push: a registry host is required for push
./build.sh build-push --version 1.0.0 --registry registry.domain

# Build only
./build.sh build

# Push a previously built image
./build.sh push --version 1.0.0 --registry registry.domain

# Build without Docker layer cache
./build.sh build --no-cache

# Registry host and namespace via env vars
REGISTRY=registry.domain IMAGE_PREFIX=team ./build.sh build-push --version 1.0.0

# Show help
./build.sh help
```

Run the container:

```bash
docker run -d \
  -p 9876:9876 \
  -v $(pwd)/config:/app/config \
  -v $(pwd)/store:/app/store \
  -v $(pwd)/logs:/app/logs \
  fix-simulator:latest
```

The `config/`, `store/`, and `logs/` directories can be bind-mounted to inject
custom configuration and persist FIX session sequence state between restarts.

### Option B – Local JVM

```bash
cd fix.simulator
mvn clean package -q
java -jar target/fix-simulator-1.0.0-SNAPSHOT.jar
# or with explicit config paths:
java -jar target/fix-simulator-1.0.0-SNAPSHOT.jar config/server.cfg config/instruments.yml
```

### 2. Configure instruments

Edit `config/instruments.yml` to add/remove tradeable symbols.

The server listens on **port 9876** by default (change `SocketAcceptPort` in `config/server.cfg`).

---

## Configuration

### server.cfg (QuickFIX/J)

Key settings:

| Setting | Default | Description |
|---|---|---|
| `SocketAcceptPort` | `9876` | TCP listen port |
| `ResetOnLogon` | `N` | `N` = keep sequences (enable restore); `Y` = reset on every logon |
| `FileStorePath` | `store` | Directory for sequence number files |
| `FileLogPath` | `logs` | Directory for FIX message logs |
| `HeartBtInt` | `30` | Heartbeat interval in seconds |

### instruments.yml

```yaml
instruments:
  - symbol: AAPL
    description: "Apple Inc."
    lotSize: 1
    tickSize: "0.01"
```

---

## FIX Message Reference

### Inbound (client → server)

| MsgType | Tag 35 | Description |
|---|---|---|
| NewOrderSingle | `D` | Submit new order |
| OrderCancelRequest | `F` | Cancel an existing order |
| OrderCancelReplaceRequest | `G` | Modify qty / price of existing order |
| Quote | `S` | Single-symbol bid/ask quote |
| MassQuote | `i` | Multi-symbol bid/ask quotes |

### Outbound (server → client)

| MsgType | Tag 35 | Trigger |
|---|---|---|
| ExecutionReport | `8` | New ack, fills, cancel, replace, reject |
| OrderCancelReject | `9` | Cancel / replace rejected |
| QuoteAcknowledgement | `b` | Response to Quote message |
| MassQuoteAcknowledgement | `b` | Response to MassQuote message |

---

## Order Type Reference

| FIX OrdType (tag 40) | TIF (tag 59) | Behaviour |
|---|---|---|
| LIMIT `2` | DAY `0` / GTC `1` | Rests in book; fills at limit price or better |
| LIMIT `2` | IOC `3` | Immediate or Cancel – any unfilled remainder is cancelled immediately |
| LIMIT `2` | FOK `4` | Fill or Kill – entire order must be fillable now; otherwise cancelled with **no partial fills** |
| MARKET `1` | DAY `0` | Matches at any price; unfillable remainder cancelled |
| MARKET `1` | FOK `4` | Market FOK – all-or-nothing at market price |
| STOP `3` | GTC `1` | Held pending; converts to **market** order when `StopPx` is triggered |
| STOP LIMIT `4` | GTC `1` | Held pending; converts to **limit** order at `Price` (44) when `StopPx` (99) is triggered |

**FOK semantics:**
- The matching engine counts available marketable shares *before* executing any fills.
- If `availableLiquidity < orderQty` the order is cancelled immediately and the book is left completely untouched.
- This applies to both limit FOK (price-filtered liquidity) and market FOK (all liquidity at any price).

**Stop trigger rules:**
- **Buy stop**: triggered when `LastPrice >= StopPx`
- **Sell stop**: triggered when `LastPrice <= StopPx`
- Stops are evaluated after every fill in any session

---

## Quote / Market Data Flow

When a client sends a `Quote` (S) or `MassQuote` (i):

1. The `MarketState` NBBO is updated (bid/ask).
2. Bid and ask legs are inserted as resting GTC limit orders in the shared order book.
3. Any immediate matches against existing resting orders produce `ExecutionReport` (8) responses.
4. A `QuoteAcknowledgement` (b) is sent back.

---

## Session Restore

QuickFIX/J writes sequence state to `store/<BeginString>-<SenderCompID>-<TargetCompID>.seqnums`.

When a client reconnects with the same CompIDs:
- If `ResetOnLogon=N`, the server maintains sequence continuity.
- The client can request a resend (`ResendRequest`); QFJ replays stored messages automatically.

To **hard reset** a session: delete the corresponding files in `store/`, or set `ResetOnLogon=Y` temporarily.

---

## Multiple Clients

Dynamic session acceptance is used: any `SenderCompID` can connect without
modifying `server.cfg`. Each new CompID pair gets an independent session and
its own sequence store file.

The `[SESSION]` block in `server.cfg` serves as the **template** (providing defaults
like heartbeat interval and dictionary). The actual `TargetCompID` is taken from
the connecting client's `Logon` message.

---

## Project Structure

```
fix.simulator/
├── pom.xml
├── config/
│   ├── server.cfg          # QuickFIX/J acceptor config
│   └── instruments.yml     # Tradeable symbols
└── src/main/java/com/fix/simulator/
    ├── Main.java                    # Entry point, builds acceptor
    ├── FIXSimulatorApp.java         # QuickFIX/J Application, message dispatch
    ├── engine/
    │   ├── Order.java               # Order domain object
    │   ├── OrderType / Side / Status / TimeInForce  (enums)
    │   ├── Execution.java           # Fill record
    │   ├── MarketState.java         # NBBO per symbol
    │   ├── OrderBook.java           # CLOB, price-time FIFO matching
    │   └── MatchingEngine.java      # Coordinates books, stop triggers
    ├── handler/
    │   ├── ResponseSender.java      # Abstraction over Session.sendToTarget
    │   ├── NewOrderHandler.java     # D → engine.newOrder
    │   ├── CancelHandler.java       # F → engine.cancelOrder
    │   ├── CancelReplaceHandler.java# G → engine.replaceOrder
    │   └── QuoteHandler.java        # S / i → engine.onQuote
    └── util/
        ├── InstrumentRegistry.java  # Load instruments.yml
        └── ExecReportHelper.java    # Execution → FIX ExecutionReport
```

---

## Testing

```bash
mvn test
```

222 JUnit 5 tests across 11 test classes covering the full functional layer:

| Test class | Scope |
|---|---|
| `OrderTest` | Order state machine (fill, cancel, trigger, qty) |
| `ExecutionTest` | Execution construction and ExecType enum |
| `MarketStateTest` | NBBO tracking, mid-price, symbol isolation |
| `OrderBookTest` | Full/partial fills, FIFO ordering, IOC, **FOK**, stops, concurrency |
| `MatchingEngineTest` | New order lifecycle, IOC, **FOK**, cancel, replace, stop triggers |
| `ExecReportHelperTest` | FIX message construction, all tag-value converters |
| `InstrumentRegistryTest` | YAML loading, lookup, defaults, edge cases |
| `NewOrderHandlerTest` | Validation, field parsing, TIF (IOC / GTC / **FOK** / DAY), dispatch |
| `CancelHandlerTest` | Cancel success / not-found paths |
| `CancelReplaceHandlerTest` | Replace success / not-found, replacement field parsing |
| `QuoteHandlerTest` | Quote (S), MassQuote (i), fills forwarding |

Mockito 5.8 is used for handler-layer tests; engine and book tests use real objects.

---

## Performance Notes

- **Target**: 2–10K messages/s total across all symbols
- Each `OrderBook` is synchronized on its own monitor → per-symbol contention only
- QuickFIX/J uses MINA non-blocking I/O, so the network layer is not the bottleneck
- Hot path: `OrderBook.match()` iterates `TreeMap` price levels with `ArrayDeque` FIFO queues – O(k) where k = number of price levels crossed
- For higher throughput, `OrderBook` can be further optimised with a lock-free design (Disruptor pattern), but is not needed at this target rate
