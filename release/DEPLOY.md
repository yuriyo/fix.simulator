# FIX Exchange Simulator: deploy and run

This guide is for a release of the FIX Exchange Simulator. It covers loading the image, running it, configuring it, and keeping it running. You need Docker and nothing else.

## What is in this release

| File | What it is |
|------|-----------|
| Docker Hub `tradeeze/fixsimulator:<version>` | The Docker image. Pull it with `docker pull`. |
| `fixsimulator-<version>.image.tar.gz` | The same image as a file, for machines without Internet access. Load it with `docker load`. |
| `fixsimulator-<version>.jar` | The server as one runnable jar (for reference; the image already contains it) |
| `fixsimulator-<version>-config.tar.gz` | Default `server.cfg` and `instruments.yml`. Start from these. |
| `DEPLOY.md` | This guide |
| `SHA256SUMS` | Checksums of every file above |

Check the downloads before you load them:

```bash
sha256sum -c SHA256SUMS
```

## Ports

| Port | Use |
|------|-----|
| `9876` | FIX (clients connect here) |
| `8080` | Admin web UI and REST API |

## 1. Get the image

From Docker Hub (needs Internet access):

```bash
docker pull tradeeze/fixsimulator:<version>
```

Or, from the release file, on a machine without Internet access:

```bash
docker load -i fixsimulator-<version>.image.tar.gz
```

Both give the image `tradeeze/fixsimulator:<version>`. Use that name in the commands below.

## 2. Prepare the configuration

```bash
tar -xzf fixsimulator-<version>-config.tar.gz     # creates ./config
mkdir -p fix-simulator && mv config fix-simulator/
```

Edit `fix-simulator/config/instruments.yml` to set the symbols you trade. Edit `server.cfg` only if you need a different port or FIX setting. Changes are read at start-up.

## 3. Run it

Choose an admin token. It protects every change made through the admin UI and API.

```bash
export ADMIN_TOKEN="$(openssl rand -hex 16)"
echo "Admin token: $ADMIN_TOKEN"   # keep it safe

docker run -d --name fix-simulator --restart unless-stopped \
  -p 9876:9876 -p 8080:8080 \
  -e ADMIN_TOKEN="$ADMIN_TOKEN" \
  -v "$(pwd)/fix-simulator/config:/app/config" \
  -v fix-store:/app/store \
  -v fix-logs:/app/logs \
  tradeeze/fixsimulator:<version>
```

- `--restart unless-stopped` starts the simulator again after a reboot.
- `fix-store` keeps the FIX sequence numbers, so sessions resume after a restart.
- `fix-logs` keeps the FIX message logs. The Messages tab reads them.

Check that it started:

```bash
docker logs fix-simulator | tail -20
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/   # expect 200
```

Open **http://localhost:8080** in a browser and enter the admin token.

To run on another machine, replace `localhost` with the host's address, and open ports 9876 and 8080 in its firewall. The admin port accepts changes only with the token, but use it on a trusted network only.

## 4. Connect a FIX client

| Setting | Value |
|---------|-------|
| Host / port | the server, port `9876` |
| `BeginString` | `FIX.4.2` (FIX 4.4 clients are refused) |
| `SenderCompID` | any name; each name gets its own session |
| `TargetCompID` | `EXCHANGE` |
| Heartbeat | 30 seconds |

No configuration change is needed for a new client.

## 5. Configuration reference

`server.cfg` (FIX session settings):

| Setting | Default | Meaning |
|---------|---------|---------|
| `SocketAcceptPort` | `9876` | FIX port |
| `HeartBtInt` | `30` | Heartbeat interval (seconds) |
| `ResetOnLogon` | `N` | `N` keeps sequence numbers so sessions resume |
| `FileStorePath` / `FileLogPath` | `store` / `logs` | Session store and message logs |

Environment variables (`docker run -e ...`):

| Variable | Default | Meaning |
|----------|---------|---------|
| `ADMIN_TOKEN` | generated at each start (printed in the logs) | Required for every change through the admin |
| `ADMIN_BIND` | `0.0.0.0` in the image | Address the admin listens on |
| `JAVA_TOOL_OPTIONS` | none | JVM options, e.g. `-Xmx2g` to cap memory |

## 6. Keep it running

**Daily checks**

| Check | Command |
|-------|---------|
| Running | `docker ps --filter name=fix-simulator` |
| Errors | `docker logs fix-simulator 2>&1 \| grep -i " error "` |
| Log size | `docker exec fix-simulator du -sh /app/logs` |

**Where the data is**

| Path in the container | What | Kept across restarts |
|-----------------------|------|----------------------|
| `/app/store` | FIX sequence numbers | yes (volume `fix-store`) |
| `/app/logs` | Every FIX message and session event | yes (volume `fix-logs`) |
| `/app/config/blocked-clients.yml` | Blocked clients set in the admin | yes, if `/app/config` is mounted |
| Orders and trades | in memory | no, lost on restart |

**Logs grow without limit.** The simulator does not rotate its message logs. To free space, stop the simulator and delete the logs you no longer need:

```bash
docker stop fix-simulator
docker run --rm -v fix-logs:/app/logs alpine sh -c 'rm -f /app/logs/*.messages.log'
docker start fix-simulator
```

**Reset a session.** Stop the simulator, delete the session's files in the store (for example `FIX.4.2-EXCHANGE-CLIENT1.*`), and start it again. The client must reset too.

**Back up the sessions:**

```bash
docker run --rm -v fix-store:/from -v "$(pwd)":/to alpine tar czf /to/fix-store-backup.tgz -C /from .
```

**Upgrade to a new release:**

```bash
docker stop fix-simulator && docker rm fix-simulator
docker pull tradeeze/fixsimulator:<new-version>
docker run -d --name fix-simulator --restart unless-stopped \
  -p 9876:9876 -p 8080:8080 -e ADMIN_TOKEN="$ADMIN_TOKEN" \
  -v "$(pwd)/fix-simulator/config:/app/config" \
  -v fix-store:/app/store -v fix-logs:/app/logs \
  tradeeze/fixsimulator:<new-version>
```

The volumes are kept, so sessions resume. Check that clients still use `FIX.4.2`.

**Stop and remove:** `docker stop fix-simulator`. To delete everything, including sessions and logs: `docker rm -f fix-simulator && docker volume rm fix-store fix-logs`.

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `port is already allocated` | another program uses 9876 or 8080 | stop it, or change the left-hand port in `-p` |
| Client is refused at logon | `BeginString` is not `FIX.4.2` | set the client to FIX 4.2 |
| `MsgSeqNum too low` or resend loops | sequence numbers differ | reset the session on both sides |
| Admin returns 401 or 403 | missing or wrong token | send `Authorization: Bearer <token>` |
| Admin page is blank | wrong or missing UI build | use this release's image, not a local build |
| `Permission denied` on a mounted folder | the container user cannot write to it | use named volumes, as above |
| Exit code 137 | out of memory | give the container more memory, or cap the heap with `-e JAVA_TOOL_OPTIONS=-Xmx2g` |

Details of each error are in `docker logs fix-simulator`, and session events are in `/app/logs/*.event.log`.

## Limits of this release

- FIX 4.2 only.
- Orders and trades are in memory and are lost on restart. Sequence numbers and message logs are kept.
- The message logs are not rotated (see above).
- The admin WebSocket does not check the token. Keep the admin port on a trusted network.
- Prices and timing are simulated. Do not use this as a real exchange.
