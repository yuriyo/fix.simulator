# FIX Exchange Simulator

A test exchange for FIX clients. It accepts FIX 4.2 connections over TCP, matches orders in an in-memory order book, and replies with execution reports. Use it to test trading clients without a real exchange.

- **Language and libraries:** Java 17, QuickFIX/J 2.3.1, Javalin (admin API), React (admin UI).
- **Protocol:** FIX **4.2** (`BeginString=FIX.4.2`). Older notes that say FIX 4.4 are wrong.
- **State:** Orders and trades live in memory and are lost on restart. FIX session sequence numbers are kept on disk, so a restarted simulator can resume sessions.

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Quick start with Docker (recommended)](#2-quick-start-with-docker-recommended)
3. [Develop and test in Docker](#3-develop-and-test-in-docker)
4. [Connect a FIX client](#4-connect-a-fix-client)
5. [Configuration](#5-configuration)
6. [Admin UI and API](#6-admin-ui-and-api)
7. [Order behaviour](#7-order-behaviour)
8. [Maintenance](#8-maintenance)
9. [Troubleshooting](#9-troubleshooting)
10. [Build, test and coverage](#10-build-test-and-coverage)
11. [Performance](#11-performance)
12. [Project layout](#12-project-layout)
13. [Known limitations](#13-known-limitations)

---

## 1. Prerequisites

**Docker is the only thing you install.** Java, Maven and Node.js run inside containers. Nothing about the toolchain needs to be installed on your machine.

Check that Docker works:

```bash
docker --version
docker info > /dev/null && echo "Docker is running"
```

Ports used:

| Port | Purpose |
|---|---|
| `9876` | FIX acceptor (clients connect here) |
| `8080` | Admin web UI and REST API |

---

## 2. Quick start with Docker (recommended)

Run every command from the repository root (the folder that contains `Dockerfile` and `build.sh`).

### Step 1: Build the image

```bash
./build.sh build --version 1.0.0
```

This creates a local image named `fix-simulator:1.0.0`. It needs no registry. The first build takes several minutes because it downloads dependencies.

### Step 2: Start the simulator

```bash
./build.sh run
```

This starts the container `fix-simulator` and prints the admin token. Keep the token: the admin UI asks for it. To choose the token yourself, set it first: `ADMIN_TOKEN=my-token ./build.sh run`.

What the container uses:

- Ports `9876` (FIX) and `8080` (admin UI and API) are published to your machine.
- Named volumes `fix-store` and `fix-logs` keep FIX sequence numbers and message logs. They survive restarts and removal.

To start the image without `build.sh`, run `docker run` with the same ports, `-e ADMIN_TOKEN=...` and the two volumes.

### Step 3: Check that it is running

```bash
docker logs fix-simulator | tail -20
```

You should see the acceptor start on port 9876 and the admin server start on port 8080. Then open **http://localhost:8080** in a browser and enter the admin token when asked.

### Step 4: Stop and start

```bash
docker stop fix-simulator      # stop; sequence numbers are kept
docker start fix-simulator     # start again with the same state
docker rm -f fix-simulator     # remove the container (volumes are kept)
```

### Using your own configuration

The image contains default files in `/app/config`. To change them, mount your own folder over it:

```bash
docker run -d --name fix-simulator \
  -p 9876:9876 -p 8080:8080 \
  -e ADMIN_TOKEN="$ADMIN_TOKEN" \
  -v "$(pwd)/config:/app/config" \
  -v fix-store:/app/store \
  -v fix-logs:/app/logs \
  fix-simulator:1.0.0
```

Your `config` folder must contain `server.cfg` and `instruments.yml`. Copy them from the repository's `config/` folder as a starting point.

### Build options

```bash
./build.sh build --version 1.0.0 --no-cache    # rebuild without Docker's cache
./build.sh help                                # show all commands and options
```

To push to a registry (only if your team uses one):

```bash
./build.sh build-push --version 1.0.0 --registry registry.example.com
REGISTRY=registry.example.com IMAGE_PREFIX=team ./build.sh build-push --version 1.0.0
```

`--registry` is required for `push`. `IMAGE_PREFIX` adds a namespace, for example `team/fix-simulator`. Both are empty by default.

---

## 3. Develop and test in Docker

Everything runs in containers. `build.sh` calls only `docker`:

| Command | What it does |
|---|---|
| `./build.sh build --version X` | Compiles the server, runs all tests, builds the admin UI, and makes the image `fix-simulator:X`. A failing test stops the build. |
| `./build.sh test` | Runs all tests and writes the coverage report to `target/site/jacoco/` |
| `./build.sh ui-build` | Builds the admin UI into `admin-ui/dist` |
| `./build.sh shell` | Opens a shell with JDK 17, Maven and Node 20, with the repository at `/src` |
| `./build.sh run` | Starts the simulator container |

The first run of each command builds a developer image (`fix-simulator-dev`) and downloads dependencies. Later runs reuse them from the Docker volume `fix-simulator-m2`.

Pass extra Maven options with `MVN_ARGS`, for example one test class:

```bash
MVN_ARGS="-Dtest=OrderTest" ./build.sh test
```

To change the code: edit files on your machine, then run `./build.sh test`. Build output in `target/` and `admin-ui/dist/` appears on your machine, owned by you.

To change the port of the admin UI, edit the `-p` option in `build.sh` (`run` command) or use `docker run` directly.

---

## 4. Connect a FIX client

Use these settings in your client:

| Setting | Value |
|---|---|
| Host | `localhost` (or the machine running the simulator) |
| Port | `9876` |
| `BeginString` | `FIX.4.2` (a FIX 4.4 client is rejected) |
| `SenderCompID` | Any name you like, for example `CLIENT1`. Each name gets its own session. |
| `TargetCompID` | `EXCHANGE` |
| Heartbeat | `30` seconds (the default) |

The simulator accepts any `SenderCompID` without configuration changes. Each name gets its own session and its own sequence-number file.

A ready-made test client, `fix.testharness`, is in a sibling repository. It runs scripted scenarios against this simulator (see that repository's README).

---

## 5. Configuration

### 5.1 `config/server.cfg` (QuickFIX/J session settings)

| Setting | Default | Meaning |
|---|---|---|
| `SocketAcceptPort` | `9876` | TCP port for FIX clients |
| `HeartBtInt` | `30` | Heartbeat interval in seconds |
| `ResetOnLogon` | `N` | `N` keeps sequence numbers so sessions resume. `Y` resets them on each logon. |
| `ResetOnLogout` / `ResetOnDisconnect` | `N` | Same meaning, applied at logout or disconnect |
| `FileStorePath` | `store` | Folder for sequence and message store files |
| `FileLogPath` | `logs` | Folder for FIX message and event logs |
| `DataDictionary` | `FIX42.xml` | FIX 4.2 dictionary used to validate messages |
| `[SESSION] BeginString` | `FIX.4.2` | Protocol version. Must match your client. |

Change settings in `config/server.cfg` and restart the simulator. The file is read at startup.

### 5.2 `config/instruments.yml` (tradeable symbols)

```yaml
instruments:
  - symbol: AAPL          # FIX tag 55, must be unique
    description: "Apple Inc."
    lotSize: 1            # minimum order quantity step
    tickSize: "0.01"      # minimum price step
```

Orders with a quantity off the lot size or a price off the tick size are rejected. Changes take effect after a restart.

### 5.3 Environment variables and system properties

| Variable (or `-D` property) | Default | Meaning |
|---|---|---|
| `ADMIN_TOKEN` (`-Dadmin.token`) | generated at each start | Bearer token needed for every change made through the admin API |
| `ADMIN_BIND` (`-Dadmin.bind`) | `127.0.0.1` locally, `0.0.0.0` in Docker | Network address the admin server listens on |
| `-Dadmin.port` | `8080` | Admin port |
| `-Dadmin.ui` | `admin-ui/dist` | Folder with the built web UI |
| `JAVA_TOOL_OPTIONS` | none | Extra JVM options, for example `-Xmx2g` to limit memory |

---

## 6. Admin UI and API

Open **http://localhost:8080** (Docker) or the admin port you chose. The UI shows order books, orders, trades, executions and the FIX message log, and lets you cancel orders and change book levels.

The API is at `/api/...`. Reading data needs no token. Any change (POST, PUT, PATCH, DELETE) needs the header:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/orders
curl -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/orders/<orderId>
```

The live event feed is at `/api/ws/events` (WebSocket). It is not protected by the token (see [Known limitations](#13-known-limitations)).

The admin server listens on `127.0.0.1` unless `ADMIN_BIND` is set. Keep it that way on shared machines.

---

## 7. Order behaviour

### 7.1 Supported messages

**Client to simulator (inbound):**

| MsgType (tag 35) | Name | What it does |
|---|---|---|
| `D` | NewOrderSingle | Submit an order |
| `F` | OrderCancelRequest | Cancel an order |
| `G` | OrderCancelReplaceRequest | Change quantity or price of an order |
| `S` | Quote | Single-symbol bid and ask quote |
| `i` | MassQuote | Quotes for several symbols |

**Simulator to client (outbound):**

| MsgType | Name | When |
|---|---|---|
| `8` | ExecutionReport | New order acknowledged, fill, cancel, replace or reject |
| `9` | OrderCancelReject | A cancel or replace was refused |
| `b` | QuoteAcknowledgement / MassQuoteAcknowledgement | Reply to a quote |

### 7.2 Order types

| OrdType (tag 40) | TIF (tag 59) | Behaviour |
|---|---|---|
| LIMIT `2` | DAY `0` or GTC `1` | Rests in the book until filled or cancelled |
| LIMIT `2` | IOC `3` | Fills what it can now; the rest is cancelled at once |
| LIMIT `2` | FOK `4` | Fills completely now, or is cancelled with **no partial fills** |
| MARKET `1` | DAY `0` | Fills at any price; unfilled remainder is cancelled |
| MARKET `1` | FOK `4` | All or nothing at market price |
| STOP `3` | GTC `1` | Waits; becomes a market order when the stop price is reached |
| STOP LIMIT `4` | GTC `1` | Waits; becomes a limit order at `Price` (44) when the stop price (99) is reached |

**FOK rule:** the engine counts available shares before filling anything. If there is not enough, the order is cancelled and the book is unchanged.

**Stop rule:** a buy stop triggers when the last price is at or above the stop price. A sell stop triggers when the last price is at or below it. Stops are checked after every fill.

### 7.3 Quotes

A quote's bid and ask become resting GTC limit orders in the shared book, so other clients' orders can match them. A new quote from a session replaces that session's previous quote on the same symbol.

---

## 8. Maintenance

### 8.1 What to check regularly

| Check | How | Action if wrong |
|---|---|---|
| Simulator is running | `docker ps` or the admin UI | `docker start fix-simulator` |
| Disk use of logs | `docker exec fix-simulator du -sh /app/logs` | See 8.3 |
| Errors in the log | `docker logs fix-simulator 2>&1 \| grep -i error` | Read the message; see [Troubleshooting](#9-troubleshooting) |

### 8.2 Where data is kept

| Path (in the container) | Contents | Kept across restart? |
|---|---|---|
| `/app/store` | Session sequence numbers and message store (`FIX.4.2-<sender>-<target>.*`) | Yes, if mounted as a volume |
| `/app/logs` | `*.messages.log` (every FIX message) and `*.event.log` (session events), one pair per session | Yes, if mounted as a volume |
| `/app/config/blocked-clients.yml` | Client block and allow lists made in the admin UI | Yes, if `/app/config` is mounted |
| In memory | Orders, trades, executions, book | **No**, lost on restart |

### 8.3 Logs grow without limit

The simulator does not rotate or delete its message logs. A busy session can write hundreds of MB. To free space:

```bash
docker stop fix-simulator
docker run --rm -v fix-logs:/app/logs alpine sh -c 'rm -f /app/logs/*.messages.log'
docker start fix-simulator
```

Delete only logs you no longer need. Stopping the simulator first avoids writing to a file while it is removed.

### 8.4 Reset sessions

Resetting makes the next logon start at sequence number 1. Clients must also reset.

- **One session:** stop the simulator, delete the matching files in `/app/store` (for example `FIX.4.2-EXCHANGE-CLIENT1.*`), then start again.
- **All sessions:** stop the simulator and delete everything in `/app/store`.
- **Temporarily:** set `ResetOnLogon=Y` in `server.cfg` and restart. Set it back to `N` afterwards.

```bash
docker stop fix-simulator
docker run --rm -v fix-store:/app/store alpine sh -c 'rm -f /app/store/*'
docker start fix-simulator
```

### 8.5 Change symbols or settings

Edit `config/instruments.yml` or `config/server.cfg` and restart. Open orders are lost on restart, so restart when the market is idle.

### 8.6 Back up and restore

To keep sessions across machines, copy the store volume:

```bash
docker run --rm -v fix-store:/from -v "$(pwd)":/to alpine tar czf /to/fix-store.tgz -C /from .
```

Restore into an empty volume with the reverse command.

### 8.7 Upgrade

```bash
git pull
./build.sh build --version 1.1.0
docker stop fix-simulator && docker rm fix-simulator
docker run -d --name fix-simulator -p 9876:9876 -p 8080:8080 \
  -e ADMIN_TOKEN="$ADMIN_TOKEN" -v fix-store:/app/store -v fix-logs:/app/logs \
  fix-simulator:1.1.0
```

The volumes are kept, so sessions resume. Check that your clients still use `FIX.4.2`.

### 8.8 Remove everything

```bash
docker rm -f fix-simulator
docker volume rm fix-store fix-logs
docker rmi fix-simulator:1.0.0
```

---

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `port is already allocated` on start | Another program uses 9876 or 8080 | Stop it, or change the left-hand port in `-p` |
| Client is rejected at logon | `BeginString` is not `FIX.4.2` | Set the client to FIX 4.2 |
| Client logs on, then `MsgSeqNum too low` or resend loops | Sequence numbers in the client and simulator differ | Reset the session on both sides (section 8.4) |
| Admin API returns 401 or 403 | Missing or wrong `Authorization: Bearer` token | Use the token from `ADMIN_TOKEN` or from the logs |
| Admin UI page is blank or 404 | Web UI not built, or simulator started outside the repo root | Build it (section 3) and start from the repo root |
| `Permission denied` writing `/app/store` or `/app/logs` | A bind-mounted folder is not writable by the container user | Use named volumes as in section 2, or `chown` the folder |
| Orders rejected as `Unsupported` or with a field error | Quantity off lot size, price off tick size, or a missing field | Check `instruments.yml` and the order fields |
| Simulator stops with exit code 137 | Out of memory (the container or Java heap) | Give the container more memory (`--memory 4g`), or cap the heap (`-e JAVA_TOOL_OPTIONS=-Xmx2g`) |

To see detailed logs, read `/app/logs/*.event.log` (session events) and `docker logs fix-simulator`.

---

## 10. Build, test and coverage

### 10.1 Run the tests

```bash
./build.sh test                                                   # all tests, in Docker
MVN_ARGS="-Dtest=OrderTest" ./build.sh test                       # one test class
MVN_ARGS="-Dtest='com.fix.simulator.defects.**'" ./build.sh test  # regression tests for fixed defects
```

There are 517 tests in 28 test classes. Mockito is used only for handler tests. Order-book and engine tests use real objects.

The `defects` package holds regression tests for defects D1 to D8 (see `CLAUDE.md`).

### 10.2 Coverage report (JaCoCo)

`./build.sh test` also writes a coverage report to `target/site/jacoco/` on your machine. Open `index.html` in a browser.

| File | Use |
|---|---|
| `index.html` | Browse coverage per package and class |
| `jacoco.csv` | Plain counters for scripts |
| `jacoco.xml` | Per-line hit counts for tools |

Branch coverage is the closest measure of condition coverage.

### 10.3 Admin UI development

```bash
./build.sh ui-build    # builds admin-ui/dist in the toolbox container
./build.sh build       # rebuilds the image with the new UI
./build.sh run         # restarts the simulator with it
```

The Vite dev server is not part of the Docker workflow. Each UI change is checked by `ui-build`, and `build` packages it.

---

## 11. Performance

Measured with `fix.testharness` on a 10-core Apple Silicon host, with the simulator limited to 4 cores and the clients on the rest of the same host:

| Setup | Orders per second |
|---|---|
| 1 simulator core, saturated | about 37k |
| 4 simulator cores, 8 clients | about 82k to 85k (highest measured) |
| 4 simulator cores, 4 clients | about 69k |

These are the highest rates measured on that setup. The simulator's own ceiling was not reached, because the load generator on the same host could not push harder. Treat these as a lower bound on what the simulator can handle.

Each client JVM needs its heap capped on a small host. Without a cap, a dozen clients can exhaust memory and the simulator is killed.

---

## 12. Project layout

```
fix.simulator/
├── Dockerfile                 # three stages: Maven build, web UI build, runtime
├── build.sh                   # build and push helper
├── config/
│   ├── server.cfg             # FIX acceptor settings
│   └── instruments.yml        # tradeable symbols
├── admin-ui/                  # React admin UI (Vite)
├── FIX_SPEC.md                # protocol notes
└── src/main/java/com/fix/simulator/
    ├── Main.java              # entry point: builds the acceptor and admin server
    ├── FIXSimulatorApp.java   # QuickFIX/J application; routes inbound messages
    ├── engine/                # order book, matching engine, orders, executions
    ├── handler/               # one handler per message type (D, F, G, S, i)
    ├── admin/                 # admin REST API, WebSocket events, client registry
    └── util/                  # instruments, logging (async), FIX report building
```

---

## 13. Known limitations

- **Protocol:** FIX 4.2 only. Other versions need a different QuickFIX/J dictionary.
- **State:** orders and trades are in memory and lost on restart.
- **Logs:** no rotation or size limit (see 8.3).
- **Admin WebSocket:** the live event feed is not protected by the admin token. Keep the admin port on a trusted network.
- **Fill routing:** some executions (for example fills of other clients' stop orders) are sent to the session that submitted the incoming message, not to the owning client. This is a known open issue listed in `CLAUDE.md`.
- **Simulation only:** prices, liquidity and timing are not realistic market data. Do not use it as a real exchange.
