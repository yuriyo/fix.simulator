# FIX Exchange Simulator — Protocol Specification

**Protocol Version**: FIX 4.4  
**Transport**: TCP/IP (plain socket, no SSL)  
**Default Port**: 9876  
**Encoding**: ASCII  

---

## Table of Contents

1. [Session Layer](#1-session-layer)  
2. [Message Header and Trailer](#2-message-header-and-trailer)  
3. [Logon (A)](#3-logon-a--35a)  
4. [Logout (5)](#4-logout-5--355)  
5. [Heartbeat (0)](#5-heartbeat-0--350)  
6. [Resend Request (2)](#6-resend-request-2--352)  
7. [NewOrderSingle (D)](#7-newordersingle-d--35d)  
8. [OrderCancelRequest (F)](#8-ordercancelrequest-f--35f)  
9. [OrderCancelReplaceRequest (G)](#9-ordercancelreplacerequest-g--35g)  
10. [Quote (S)](#10-quote-s--35s)  
11. [MassQuote (i)](#11-massquote-i--35i)  
12. [ExecutionReport (8)](#12-executionreport-8--358)  
13. [OrderCancelReject (9)](#13-ordercancelreject-9--359)  
14. [QuoteAcknowledgement (b)](#14-quoteacknowledgement-b--35b)  
15. [MassQuoteAcknowledgement (b – MassQuote variant)](#15-massquoteacknowledgement)  
16. [Order Types](#16-order-types)  
17. [Time In Force](#17-time-in-force)  
18. [Order Matching Rules](#18-order-matching-rules)  
19. [Stop Order Trigger Rules](#19-stop-order-trigger-rules)  
20. [Quote / Market Data Model](#20-quote--market-data-model)  
21. [Reject Conditions](#21-reject-conditions)  
22. [Field Value Reference](#22-field-value-reference)  
23. [Restrictions and Limitations](#23-restrictions-and-limitations)  
24. [Admin HTTP API](#24-admin-http-api)  

> **Spec version note (Feb 2026):** FOK is now fully implemented as a true all-or-nothing pre-check. MARKET orders (`OrdType=1`) are accepted from clients.

---

## 1. Session Layer

### 1.1 Connection

The simulator acts as a **FIX acceptor** (server). Clients initiate the TCP connection and send the `Logon` message.

| Parameter | Value |
|---|---|
| Role | Acceptor |
| Protocol | FIX 4.4 |
| BeginString (tag 8) | `FIX.4.4` |
| SenderCompID (tag 49) | `EXCHANGE` |
| TargetCompID (tag 56) | Any — dynamic session acceptance |
| Port | `9876` (configurable in `server.cfg`) |
| Heartbeat interval | 30 s (configurable) |
| Multiple sessions | Supported; each client CompID pair is tracked independently |

### 1.2 Sequence Number Management

- Sequence numbers are **persisted** per session to the `store/` directory.
- `ResetOnLogon=N` by default: sequence state is preserved across reconnections.
- A client may send `ResendRequest (2)` after reconnect; the server will replay stored outbound messages from `FileLogFactory` for the requested range.
- To perform a hard reset, delete the corresponding files in `store/` or configure `ResetOnLogon=Y` in `server.cfg`.

### 1.3 Session Restore Flow

```
Client                          Server
  |                               |
  |--- Logon (34=<last+1>) ------>|  (same CompID pair, ResetOnLogon=N)
  |<-- Logon ----------------------|
  |
  |  (gap detected by server)
  |<-- ResendRequest (2) ----------|
  |--- SequenceReset (4) / replay->|
  |                               |
```

---

## 2. Message Header and Trailer

All FIX messages include the standard header and trailer.

### Standard Header

| Tag | Field | Req | Notes |
|---|---|---|---|
| 8 | BeginString | Y | Must be `FIX.4.4` |
| 9 | BodyLength | Y | Length in bytes; set automatically |
| 35 | MsgType | Y | See individual message descriptions |
| 49 | SenderCompID | Y | Client sets its own ID |
| 56 | TargetCompID | Y | Must be `EXCHANGE` |
| 34 | MsgSeqNum | Y | Monotonically increasing per session |
| 52 | SendingTime | Y | UTC timestamp; format `YYYYMMDD-HH:MM:SS.sss` |

### Standard Trailer

| Tag | Field | Req | Notes |
|---|---|---|---|
| 10 | CheckSum | Y | Standard FIX checksum; validated by server |

---

## 3. Logon (A) — 35=A

Initiates a FIX session. The server responds with its own `Logon`.

### Client → Server

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `A` |
| 98 | EncryptMethod | Y | Must be `0` (none) |
| 108 | HeartBtInt | Y | Proposed heartbeat interval in seconds; server uses its configured value |
| 141 | ResetSeqNumFlag | N | `Y` resets sequences; `N` (default) continues existing sequence |

### Server → Client (response)

Same fields mirrored back with server's `SenderCompID=EXCHANGE`.

**Restrictions:**
- `EncryptMethod` value other than `0` causes the session to be rejected.
- Duplicate CompID pairs (same SenderCompID already connected) will have the existing session terminated before the new one is established.

---

## 4. Logout (5) — 35=5

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `5` |
| 58 | Text | N | Optional free-text reason |

Either side may initiate logout. The receiving side responds with its own `Logout`.

---

## 5. Heartbeat (0) — 35=0

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `0` |
| 112 | TestReqID | N | Present only if this is a response to a `TestRequest (1)` |

Sent automatically when no other message is exchanged within `HeartBtInt` seconds.

---

## 6. Resend Request (2) — 35=2

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `2` |
| 7 | BeginSeqNo | Y | First sequence number to resend |
| 16 | EndSeqNo | Y | Last sequence number (`0` means all remaining) |

QuickFIX/J handles resend replay automatically from stored message logs.

---

## 7. NewOrderSingle (D) — 35=D

Submit a new order.

### Client → Server

| Tag | Field | Req | Supported Values | Notes |
|---|---|---|---|---|
| 35 | MsgType | Y | `D` | |
| 11 | ClOrdID | Y | Any unique string | Client-assigned, must be unique per session |
| 55 | Symbol | Y | Instrument symbol | Must be in `instruments.yml`; unknown symbol → Reject |
| 54 | Side | Y | `1` (Buy), `2` (Sell) | Other values → Reject |
| 40 | OrdType | Y | `1`, `2`, `3`, `4` | See [Order Types](#16-order-types) |
| 38 | OrderQty | Y | > 0, integer | Fractional quantities are truncated |
| 44 | Price | C | Decimal > 0 | **Required** for `OrdType=2` (LIMIT) and `OrdType=4` (STOP LIMIT) |
| 99 | StopPx | C | Decimal > 0 | **Required** for `OrdType=3` (STOP) and `OrdType=4` (STOP LIMIT) |
| 59 | TimeInForce | N | `0`, `1`, `3`, `4` | Default: `0` (DAY); see [Time In Force](#17-time-in-force) |
| 60 | TransactTime | N | UTC timestamp | Informational; not validated |

### Server → Client (response)

Response is one or more `ExecutionReport (8)` messages:

| Scenario | ExecType (150) | OrdStatus (39) |
|---|---|---|
| Order accepted | `0` (NEW) | `0` (NEW) |
| Fully filled | `F` (FILL) | `2` (FILLED) |
| Partially filled | `1` (PARTIAL FILL) | `1` (PARTIALLY FILLED) |
| IOC remainder cancelled | `4` (CANCELLED) | `4` (CANCELED) |
| FOK cancelled (insufficient liquidity) | `4` (CANCELLED) | `4` (CANCELED) |
| Rejected | `8` (REJECTED) | `8` (REJECTED) |

**Order of responses:** NEW ack is sent first, followed by any fill reports.

---

## 8. OrderCancelRequest (F) — 35=F

Cancel a live order.

### Client → Server

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `F` |
| 11 | ClOrdID | Y | New unique ID for this cancel request |
| 41 | OrigClOrdID | Y | ClOrdID of the order to cancel |
| 55 | Symbol | Y | Must match the original order's symbol |
| 54 | Side | Y | Must match the original order's side |
| 60 | TransactTime | N | Informational |

### Server → Client (response)

| Scenario | Message |
|---|---|
| Cancel accepted | `ExecutionReport` with `ExecType=4` (CANCELLED) |
| Order not found | `OrderCancelReject (9)` |
| Order already filled or cancelled | `OrderCancelReject (9)` |

---

## 9. OrderCancelReplaceRequest (G) — 35=G

Modify an existing live order. Only the fields listed below may be changed.  
Symbol and side **cannot** be modified.

### Client → Server

| Tag | Field | Req | Modifiable | Notes |
|---|---|---|---|---|
| 35 | MsgType | Y | — | `G` |
| 11 | ClOrdID | Y | — | New unique ID for the replace request |
| 41 | OrigClOrdID | Y | — | ClOrdID of the order being replaced |
| 55 | Symbol | Y | **No** | Must match original |
| 54 | Side | Y | **No** | Must match original |
| 40 | OrdType | Y | Yes | May change order type (see restrictions) |
| 38 | OrderQty | Y | Yes | New total quantity; may not be less than already-filled quantity |
| 44 | Price | C | Yes | Required if new OrdType is LIMIT or STOP LIMIT |
| 99 | StopPx | C | Yes | Required if new OrdType is STOP or STOP LIMIT |
| 59 | TimeInForce | N | Yes | New TIF, or retain original if omitted |
| 60 | TransactTime | N | — | Informational |

### Server → Client (response)

The original order is cancelled and a replacement order is created. Responses:

1. `ExecutionReport` with `ExecType=5` (REPLACED) for the **cancelled** original order.
2. `ExecutionReport` with `ExecType=0` (NEW) for the **replacement** order.
3. Any fill reports from immediate matching of the replacement order.

On failure: `OrderCancelReject (9)`.

---

## 10. Quote (S) — 35=S

Submit a single-symbol two-sided quote. The quote is inserted as resting limit orders on both sides of the order book and updates the NBBO market snapshot.

### Client → Server

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `S` |
| 117 | QuoteID | Y | Unique quote identifier |
| 55 | Symbol | Y | Must be in `instruments.yml` |
| 132 | BidPx | N | Bid price; required if `BidSize` is provided |
| 134 | BidSize | N | Bid quantity |
| 133 | OfferPx | N | Ask (offer) price; required if `OfferSize` is provided |
| 135 | OfferSize | N | Ask (offer) quantity |

At least one side (Bid or Offer) must be present.

### Server → Client (response)

| Scenario | Message |
|---|---|
| Quote accepted | `QuoteAcknowledgement (b)` with `QuoteAckStatus=0` (ACCEPTED) |
| Unknown symbol | `QuoteAcknowledgement (b)` with `QuoteAckStatus=5` (REJECTED) |
| Any fill generated | One or more `ExecutionReport (8)` sent **before** the ack |

---

## 11. MassQuote (i) — 35=i

Submit quotes for multiple symbols in a single message.

### Client → Server

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `i` |
| 1166 | MassQuoteID | Y | Unique ID for the mass quote message |
| 296 | NoQuoteSets | Y | Number of quote set groups |

**NoQuoteSets group (296):**

| Tag | Field | Req | Notes |
|---|---|---|---|
| 302 | QuoteSetID | Y | ID for this set |
| 295 | NoQuoteEntries | Y | Number of entries in this set |

**NoQuoteEntries group (295):**

| Tag | Field | Req | Notes |
|---|---|---|---|
| 299 | QuoteEntryID | Y | Unique ID within the set |
| 55 | Symbol | Y | Must be in `instruments.yml` |
| 132 | BidPx | N | Bid price |
| 134 | BidSize | N | Bid quantity |
| 133 | OfferPx | N | Ask price |
| 135 | OfferSize | N | Ask quantity |

### Server → Client (response)

`MassQuoteAcknowledgement` is sent after processing all entries. Entries with unknown symbols are skipped (warning logged); remaining valid entries are processed. Any fills produced are sent as `ExecutionReport (8)` messages before the ack.

---

## 12. ExecutionReport (8) — 35=8

Sent by the server in response to order operations and market activity.

### Server → Client

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `8` |
| 37 | OrderID | Y | Server-assigned unique order identifier |
| 17 | ExecID | Y | Unique execution identifier (`E<n>`) |
| 150 | ExecType | Y | See table below |
| 39 | OrdStatus | Y | Current status of the order; see table below |
| 11 | ClOrdID | Y | Client order ID |
| 41 | OrigClOrdID | N | Set on REPLACED or CANCELLED via replace |
| 55 | Symbol | Y | Instrument symbol |
| 54 | Side | Y | `1` (Buy) or `2` (Sell) |
| 40 | OrdType | Y | Original order type |
| 59 | TimeInForce | Y | Original TIF |
| 38 | OrderQty | Y | Total original quantity |
| 14 | CumQty | Y | Total quantity filled so far |
| 151 | LeavesQty | Y | Remaining open quantity |
| 32 | LastQty | N | Quantity filled in this specific execution (fills only) |
| 31 | LastPx | N | Price of this specific fill (fills only) |
| 6 | AvgPx | Y | Average fill price (`0.0` if no fills yet) |
| 44 | Price | N | Limit price (if applicable) |
| 99 | StopPx | N | Stop price (if applicable) |
| 58 | Text | N | Rejection reason (on `ExecType=8`) |
| 103 | OrdRejReason | N | Standard reject code (on `ExecType=8`) |
| 60 | TransactTime | Y | UTC timestamp |

### ExecType (tag 150) Values

| Value | Name | Trigger |
|---|---|---|
| `0` | NEW | Order accepted into book |
| `1` | PARTIAL FILL | Partial fill execution |
| `F` | FILL | Final fill – order fully filled |
| `4` | CANCELLED | Order cancelled (explicit cancel, IOC remainder, or FOK insufficient liquidity) |
| `5` | REPLACED | Original order cancelled by CancelReplace |
| `8` | REJECTED | Order rejected at entry |
| `6` | PENDING CANCEL | Internal state (not currently used) |
| `E` | PENDING REPLACE | Internal state (not currently used) |

### OrdStatus (tag 39) Values

| Value | Name |
|---|---|
| `0` | NEW |
| `1` | PARTIALLY FILLED |
| `2` | FILLED |
| `4` | CANCELED |
| `8` | REJECTED |

---

## 13. OrderCancelReject (9) — 35=9

Sent when a cancel or cancel-replace request cannot be processed.

### Server → Client

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `9` |
| 37 | OrderID | Y | `NONE` (order was not found) |
| 11 | ClOrdID | Y | ClOrdID from the rejected request |
| 41 | OrigClOrdID | N | OrigClOrdID from the request |
| 39 | OrdStatus | Y | `8` (REJECTED) |
| 434 | CxlRejResponseTo | Y | `1` (Cancel) or `2` (CancelReplace) |
| 102 | CxlRejReason | Y | `0` =Too late to cancel, `1` = Unknown order |
| 58 | Text | N | Human-readable explanation |
| 60 | TransactTime | Y | UTC timestamp |

### CxlRejReason (tag 102) Values Used

| Value | Meaning |
|---|---|
| `0` | Too late to cancel (order already filled or cancelled) |
| `1` | Unknown order (OrigClOrdID not found in session's active orders) |

---

## 14. QuoteAcknowledgement (b) — 35=b

Response to a `Quote (S)` message.

### Server → Client

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `b` |
| 117 | QuoteID | Y | Echo of the `QuoteID` from the request |
| 297 | QuoteAckStatus | Y | `0` = Accepted, `5` = Rejected |
| 58 | Text | N | Reason on rejection |

---

## 15. MassQuoteAcknowledgement

Response to a `MassQuote (i)` message. Uses the same `MsgType=b` as QuoteAcknowledgement.

### Server → Client

| Tag | Field | Req | Notes |
|---|---|---|---|
| 35 | MsgType | Y | `b` |
| 1166 | MassQuoteID | Y | Echo of the `MassQuoteID` from the request |
| 297 | QuoteAckStatus | Y | `0` = All entries accepted, `5` = One or more entries rejected |

Individual entry-level rejections (e.g., unknown symbol) are logged server-side but do not produce per-entry rejection messages.

---

## 16. Order Types

Controlled by tag **OrdType (40)**.

| FIX Value | Name | Behaviour |
|---|---|---|
| `1` | MARKET | Matches immediately against the best available prices. Unfillable remainder is cancelled. Supported as both a standalone order type and in combination with `FOK` (all-or-nothing market). |
| `2` | LIMIT | Matches at `Price (44)` or better. Unfilled remainder rests in the book unless `TimeInForce=IOC` or `FOK`. Requires `Price (44)`. |
| `3` | STOP | Held in a pending list. **Converts to a MARKET order** when `StopPx (99)` is triggered. Requires `StopPx (99)`. |
| `4` | STOP LIMIT | Held in a pending list. **Converts to a LIMIT order** at `Price (44)` when `StopPx (99)` is triggered. Requires both `Price (44)` and `StopPx (99)`. |

---

## 17. Time In Force

Controlled by tag **TimeInForce (59)**. Default is `DAY (0)` if the field is absent.

| FIX Value | Name | Behaviour |
|---|---|---|
| `0` | DAY | Order rests in book until end-of-session or until filled/cancelled. The simulator has no session schedule, so DAY is equivalent to GTC. |
| `1` | GTC (Good Till Cancel) | Same as DAY in this simulator. |
| `3` | IOC (Immediate or Cancel) | Attempts to fill immediately. **Partial fills are allowed.** Any unfilled quantity is cancelled after matching completes. |
| `4` | FOK (Fill or Kill) | **All-or-nothing.** The engine first counts total marketable liquidity at valid prices. If it is less than the full order quantity, the order is **cancelled immediately with no fills** and the book is left completely untouched. Only proceeds to matching if the entire quantity can be filled. |

> **Note**: GTC and DAY orders persist indefinitely in memory. There is no session cutover and no automatic cancellation at end of day.

**Key difference between IOC and FOK:**

| | IOC | FOK |
|---|---|---|
| Partial fill allowed | Yes | **No** |
| Book modified on cancel | N/A | **Never** |
| Cancellation timing | After matching attempt | **Before** any fill |
| Use case | Fill as much as possible now | Fill everything or nothing |

---

## 18. Order Matching Rules

### 18.1 Matching Model

- **Price-Time FIFO** (First In First Out within each price level).
- Separate order books per instrument symbol.
- Books are global and shared across all connected sessions.

### 18.2 Matching Algorithm

When an incoming (aggressor) order is processed:

1. Identify the opposite side of the book.
2. Walk price levels from best to worst:
   - **Buy aggressor**: best ask first (lowest ask price).
   - **Sell aggressor**: best bid first (highest bid price).
3. At each price level, match against resting orders FIFO.
4. A **LIMIT aggressor** stops when no resting price satisfies the limit: `ask <= bid_limit` for buys; `bid >= ask_limit` for sells.
5. A **MARKET aggressor** matches all available liquidity regardless of price.
6. Each match produces an `ExecutionReport (8)` for **both** the aggressor and the passive resting order.

### 18.3 Fill Price

Fill price is the **passive (resting) order's price** — standard exchange price improvement for the aggressor.

### 18.4 IOC Remainder

After matching is attempted:
- If `TimeInForce=IOC` and `LeavesQty > 0`, the remainder is immediately cancelled.
- A `ExecutionReport` with `ExecType=4` (CANCELLED) is sent for the remainder.
- Partial fills that occurred before the cancel are **kept**.

### 18.5 FOK Pre-Check (Fill or Kill)

FOK is evaluated **before** any fills are executed:

1. The engine performs a dry scan of the opposite side of the book, summing all marketable quantity at valid prices (price-filtered for LIMIT FOK; unrestricted for MARKET FOK).
2. If `availableLiquidity < orderQty`:
   - The order status is set to **CANCELLED**.
   - A single `ExecutionReport` with `ExecType=4` (CANCELLED), `LeavesQty=0`, `CumQty=0` is returned.
   - **No resting orders are modified.** The book is completely untouched.
3. If `availableLiquidity >= orderQty`, normal price-time FIFO matching proceeds and the order is guaranteed to fill in full.

This two-phase approach (measure then execute) ensures strict all-or-nothing semantics and eliminates any need to roll back partial fills.

### 18.6 Resting Orders

If an aggressor LIMIT order is not fully filled and its TIF is DAY or GTC:
- It is added to the passive side of the book at its limit price.
- It will be matched against future aggressor orders.

IOC and FOK orders are **never** added to the resting book regardless of fill outcome.

---

## 19. Stop Order Trigger Rules

Stop and Stop-Limit orders sit in a **pending list** per symbol until triggered by a fill at the relevant price.

### 19.1 Trigger Condition

| Side | Triggered when |
|---|---|
| **Buy** STOP / STOP LIMIT | `lastTradePrice >= StopPx (99)` |
| **Sell** STOP / STOP LIMIT | `lastTradePrice <= StopPx (99)` |

`lastTradePrice` is the most recent **fill price** from any matched trade on the same symbol.

### 19.2 Trigger Flow

1. A fill occurs → `lastTradePrice` updated.
2. All pending stops for the symbol are scanned.
3. Triggered STOP orders are converted to **MARKET** orders and matched immediately.
4. Triggered STOP LIMIT orders are converted to **LIMIT** orders at `Price (44)` and processed normally (may rest in book).
5. Any chains (fills from triggered stops may trigger further stops) are resolved in the same processing cycle.

### 19.3 Immediate Trigger at Entry

If `lastTradePrice` at the time of order submission already satisfies the stop condition, the stop is triggered immediately without entering the pending list.

### 19.4 Stop Order Cancellation

Stop orders in the pending list can be cancelled via `OrderCancelRequest (F)` using the original `ClOrdID`.

---

## 20. Quote / Market Data Model

### 20.1 Market Snapshot

The simulator maintains an NBBO snapshot per symbol:

| Field | Updated by |
|---|---|
| `bidPrice` / `bidSize` | Quote (S) or MassQuote (i) |
| `askPrice` / `askSize` | Quote (S) or MassQuote (i) |
| `lastTradePrice` | Any fill from order matching |

### 20.2 Quote as Resting Liquidity

When the server receives a Quote or MassQuote:

- Each bid leg is inserted as a **GTC LIMIT BUY** order at `BidPx` for `BidSize`.
- Each ask leg is inserted as a **GTC LIMIT SELL** order at `OfferPx` for `OfferSize`.
- These orders participate in matching immediately upon insertion.
- Any unfilled remainder rests in the book.

### 20.3 Market Data Output

The simulator does **not** publish market data (no `MarketDataSnapshotFullRefresh` / `MarketDataIncrementalRefresh`).  
Clients receive fills via `ExecutionReport (8)` messages only.  
Market data publishing may be added in a future version.

---

## 21. Reject Conditions

### 21.1 Order Rejected (ExecutionReport ExecType=8)

| Condition | OrdRejReason (103) | Tag |
|---|---|---|
| Unknown symbol | `0` (BROKER_EXCHANGE_OPTION) | 55 |
| LIMIT order missing `Price` | `0` | 44 |
| STOP LIMIT order missing `Price` | `0` | 44 |
| STOP order missing `StopPx` | `0` | 99 |
| Unsupported `Side` value | `0` | 54 |
| Unsupported `OrdType` value | `0` | 40 |

### 21.2 Cancel / Replace Rejected (OrderCancelReject)

| Condition | CxlRejReason (102) |
|---|---|
| `OrigClOrdID` not found for this session | `1` (Unknown Order) |
| Order already filled | `0` (Too Late to Cancel) |
| Order already cancelled | `0` (Too Late to Cancel) |
| Unknown symbol on replace | `1` (Unknown Order) |

### 21.3 Session-Level Rejects

| Condition | Response |
|---|---|
| `BeginString` ≠ `FIX.4.4` | Reject / Logout |
| `EncryptMethod` ≠ `0` | Reject / Logout |
| Invalid checksum | Session Reject (35=3) |
| Duplicate sequence number | Session Reject with `SessionRejectReason=47` |
| Missing required session field | Session Reject |

---

## 22. Field Value Reference

### Side (tag 54)

| Value | Name |
|---|---|
| `1` | Buy |
| `2` | Sell |

### OrdType (tag 40)

| Value | Name |
|---|---|
| `1` | Market *(internal only)* |
| `2` | Limit |
| `3` | Stop |
| `4` | Stop Limit |

### TimeInForce (tag 59)

| Value | Name |
|---|---|
| `0` | Day *(default)* |
| `1` | Good Till Cancel |
| `3` | Immediate or Cancel |
| `4` | Fill or Kill |

### ExecType (tag 150)

| Value | Name |
|---|---|
| `0` | New |
| `1` | Partial Fill |
| `F` | Fill |
| `4` | Cancelled |
| `5` | Replaced |
| `8` | Rejected |

### OrdStatus (tag 39)

| Value | Name |
|---|---|
| `0` | New |
| `1` | Partially Filled |
| `2` | Filled |
| `4` | Canceled |
| `8` | Rejected |

### CxlRejResponseTo (tag 434)

| Value | Name |
|---|---|
| `1` | Order Cancel Request |
| `2` | Order Cancel Replace Request |

### CxlRejReason (tag 102)

| Value | Name |
|---|---|
| `0` | Too Late to Cancel |
| `1` | Unknown Order |

### QuoteAckStatus (tag 297)

| Value | Name |
|---|---|
| `0` | Accepted |
| `5` | Rejected (Quote) |

---

## 23. Restrictions and Limitations

### Not Supported

| Feature | Notes |
|---|---|
| SSL / TLS | Plain TCP only |
| FIX versions other than 4.4 | Hard-coded |
| ~~Market orders from client~~ | Supported: `OrdType=1` (MARKET) is accepted, including with `TimeInForce=4` (FOK) for all-or-nothing market orders |
| Short selling controls | No net-position tracking |
| Position limits | No quantity, notional, or position limit checks |
| Price limits (circuit breakers) | No tick-size validation or price banding |
| Order expiry (GTD, GTX) | `TimeInForce=6` (Good Till Date) not supported |
| Market-at-open / market-on-close | `TimeInForce=2` (OPG) and `OrdType=5` not supported |
| Commission and fees | Not calculated |
| Multiple exchanges / routing | Single virtual exchange only |
| Market data subscriptions | No `MarketDataRequest (V)` support |
| Trade Capture Reports | No `TradeCaptureReport (AE)` |
| Drop Copy | Not supported |
| Order status request | No `OrderStatusRequest (H)` support |
| Mass order cancel | No `OrderMassCancelRequest (q)` |
| Persistence of order state | Orders exist **only in memory**; restarting the server clears all orders; only FIX message sequence numbers are persisted |
| Fractional quantities | `OrderQty` is truncated to integer |
| Pegged orders | Not supported |
| Iceberg / reserve orders | Not supported |
| Cross orders | Not supported |
| Multi-leg / list orders | Not supported |

### Behavioural Notes

- Order books are **global** — all sessions see the same liquidity. A GTC order from session A will match against an order from session B.
- Quote legs are treated as anonymous resting limit orders (no special quote cancel message is required; use `OrderCancelRequest` with the internal `ClOrdID` that the server would have assigned).
- There is no concept of pre-market or after-hours. The server accepts orders at any time.
- The `AvgPx (6)` field in `ExecutionReport` reflects the price of the **current fill only**, not a cumulative weighted average. A true VWAP calculation across multiple partial fills is not implemented.
- Stop triggers are evaluated **synchronously** within the processing of each fill. Under very high throughput this means a cascade of triggered stops from a single fill is processed entirely before the next incoming message.

---

---

## 24. Admin HTTP API

The simulator embeds an HTTP server on port **8080** (configurable via JVM system property `admin.port`).

All endpoints:
- Respond with **JSON** (`Content-Type: application/json`).
- Require **no authentication** — intended for internal / local use only.
- Allow **any origin** (`Access-Control-Allow-Origin: *`).

A React-based admin UI is served from `/` when the build artefact is present in `admin-ui/dist/`.

---

### 24.1 Orders

#### `GET /api/orders`

Returns all **currently active** (open) orders.

| Query Parameter | Type | Description |
|---|---|---|
| `symbol` | string | Filter by symbol (exact match); omit for all symbols |

**Response** — array of order objects:

| Field | Type | Description |
|---|---|---|
| `orderId` | integer | Internal sequential order ID |
| `clOrdId` | string | Client's `ClOrdID` (tag 11) |
| `sessionId` | string | FIX SessionID string |
| `symbol` | string | Instrument symbol (tag 55) |
| `side` | string | `BUY` or `SELL` |
| `type` | string | `LIMIT`, `MARKET`, `STOP`, `STOP_LIMIT` |
| `tif` | string | `DAY`, `GTC`, `IOC`, `FOK` |
| `status` | string | `NEW`, `PARTIALLY_FILLED` |
| `openQty` | integer | Remaining unfilled quantity |
| `filledQty` | integer | Quantity filled so far |
| `totalQty` | integer | Original order quantity |
| `limitPrice` | string\|null | Decimal string; null for MARKET/STOP orders |
| `stopPrice` | string\|null | Decimal string; null for LIMIT/MARKET orders |
| `createTime` | string | ISO-8601 UTC timestamp |

---

#### `GET /api/orders/history`

Returns **completed** orders (filled, cancelled, rejected). Accepts the same `?symbol=` filter. Response schema identical to `GET /api/orders`.

---

#### `DELETE /api/orders/{orderId}`

Administratively cancels a single order by its internal `orderId`. The server sends an `ExecutionReport (ExecType=4)` to the order owner's FIX session.

**Response:**
```json
{"status": "ok", "orderId": 42}
```

---

#### `DELETE /api/orders?symbol=AAPL`

Administratively cancels **all active orders** on the given symbol. Each order owner receives an `ExecutionReport (ExecType=4)`.

| Query Parameter | Required | Description |
|---|---|---|
| `symbol` | Yes | Instrument symbol |

**Response:**
```json
{"cancelled": 5, "symbol": "AAPL"}
```

---

### 24.2 Trades

#### `GET /api/trades`

Returns the trade log (all fills that produced a matched trade).

| Query Parameter | Type | Description |
|---|---|---|
| `symbol` | string | Filter by symbol; omit for all |

**Response** — array of trade objects:

| Field | Type | Description |
|---|---|---|
| `time` | string | ISO-8601 UTC timestamp of the fill |
| `symbol` | string | Instrument symbol |
| `price` | string | Decimal fill price |
| `qty` | integer | Fill quantity |
| `aggressorSide` | string | `BUY` or `SELL` — side of the incoming (aggressor) order |

---

### 24.3 Executions

#### `GET /api/executions`

Returns the execution log: all `ExecutionReport` entries generated by the engine, including fills, cancels, and replaces.

| Query Parameter | Type | Description |
|---|---|---|
| `symbol` | string | Filter by symbol; omit for all |
| `clientId` | string | Filter by CompID; omit for all |

**Response** — array of execution objects:

| Field | Type | Description |
|---|---|---|
| `execId` | string | Internal execution ID |
| `time` | string | ISO-8601 UTC timestamp |
| `symbol` | string | Instrument symbol |
| `execType` | string | `FILL`, `PARTIAL_FILL`, `CANCELLED`, `REPLACED`, `REJECTED` |
| `side` | string\|null | `BUY` or `SELL`; null for admin-generated events |
| `price` | string\|null | Decimal fill price; null for non-fill events |
| `qty` | integer | Fill quantity (0 for non-fill events) |
| `clOrdId` | string | Client's `ClOrdID` |
| `sessionId` | string | FIX SessionID string |
| `orderId` | integer | Internal order ID |

---

### 24.4 Instruments

#### `GET /api/instruments`

Returns the trading state of every configured instrument.

**Response:**
```json
{"AAPL": "OPEN", "MSFT": "HALT", "GOOGL": "QUOTES_ONLY"}
```

| State | Meaning |
|---|---|
| `OPEN` | Full order matching active |
| `HALT` | All order submission rejected; existing book retained |
| `QUOTES_ONLY` | Only Quote / MassQuote messages accepted |

---

#### `POST /api/instruments/{symbol}/state`

Changes the trading state for a symbol. Also triggers an `instrument_state` WebSocket event.

**Request body:**
```json
{"state": "HALT"}
```

**Response:**
```json
{"symbol": "MSFT", "state": "HALT"}
```

---

### 24.5 Clients

#### `GET /api/clients`

Returns **currently connected** FIX clients (active sessions only).

**Response** — array of:

| Field | Type | Description |
|---|---|---|
| `sessionId` | string | FIX SessionID string |
| `compId` | string | Target CompID (the exchange side) |
| `senderCompId` | string | Sender CompID (the client's CompID) |

---

#### `DELETE /api/clients/{compId}`

Administratively disconnects all active FIX sessions matching the given `compId`. The server sends a `Logout (5)` before closing the TCP connection.

**Response:**
```json
{"status": "ok"}
```

---

#### `GET /api/clients/blocked`

Returns the blocked-client list. Clients on this list are rejected at `Logon`.

**Response:**
```json
{"blocked": ["BAD_FIRM", "TEST99"]}
```

---

#### `POST /api/clients/blocked`

Adds a CompID to the blocked list.

**Request body:** `{"compId": "BAD_FIRM"}`  
**Response:** `{"status": "ok", "compId": "BAD_FIRM"}`

---

#### `DELETE /api/clients/blocked/{compId}`

Removes a CompID from the blocked list.

**Response:** `{"status": "ok"}`

---

#### `GET /api/clients/allowed`

Returns the allow list. When the allow list is **non-empty**, only listed CompIDs may log on; all others are rejected. When the list **is empty**, any CompID not in the blocked list is permitted.

**Response:**
```json
{"allowed": ["FIRM_A", "FIRM_B"]}
```

---

#### `POST /api/clients/allowed`

Adds a CompID to the allow list.

**Request body:** `{"compId": "FIRM_A"}`  
**Response:** `{"status": "ok", "compId": "FIRM_A"}`

---

#### `DELETE /api/clients/allowed/{compId}`

Removes a CompID from the allow list.

**Response:** `{"status": "ok"}`

---

### 24.6 Sessions

#### `GET /api/sessions`

Returns the full session history — all CompIDs that have ever connected, whether currently active or not. Results are sorted newest-first by `loginTime`.

**Response** — array of:

| Field | Type | Description |
|---|---|---|
| `sessionId` | string | FIX SessionID string |
| `compId` | string | Client's CompID |
| `loginTime` | string | ISO-8601 UTC timestamp of most-recent logon |
| `logoutTime` | string\|null | ISO-8601 UTC timestamp of most-recent logout; `null` if still active |
| `active` | boolean | `true` if the session is currently connected |
| `orders` | integer | Total orders submitted by this session |
| `open` | integer | Currently open (unfilled) orders |
| `cancelled` | integer | Cancelled orders |
| `filled` | integer | Fully-filled orders |

---

### 24.7 FIX Message Log

#### `GET /api/messages`

Returns a paginated log of raw FIX messages exchanged with clients. The log is held in memory (capacity 20 000 entries); oldest entries are evicted when full. Results are returned newest-first.

| Query Parameter | Type | Default | Description |
|---|---|---|---|
| `clientId` | string | — | Filter by CompID; omit for all |
| `symbol` | string | — | Filter by Symbol (tag 55); omit for all |
| `page` | integer | `1` | 1-based page number |
| `pageSize` | integer | `100` | Records per page; capped at 500 |

**Response:**
```json
{
  "total": 24,
  "page": 1,
  "pageSize": 5,
  "pageCount": 5,
  "messages": [
    {
      "id": 24,
      "sessionId": "FIX.4.4:EXCHANGE->CLIENT:0:0:0",
      "compId": "CLIENT",
      "direction": "IN",
      "msgType": "D",
      "symbol": "AAPL",
      "time": "2026-02-15T12:34:56.123Z",
      "tags": [
        {"tag": 8,  "val": "FIX.4.4"},
        {"tag": 35, "val": "D"},
        {"tag": 49, "val": "CLIENT"},
        {"tag": 55, "val": "AAPL"},
        {"tag": 54, "val": "1"},
        {"tag": 44, "val": "150.00"},
        {"tag": 38, "val": "100"}
      ]
    }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `id` | integer | Monotonically increasing log entry ID |
| `sessionId` | string | FIX SessionID string |
| `compId` | string | Client's CompID |
| `direction` | string | `IN` (client→server) or `OUT` (server→client) |
| `msgType` | string | Value of tag 35 (`D`, `8`, `F`, `A`, etc.) |
| `symbol` | string\|null | Value of tag 55 if present in the message |
| `time` | string | ISO-8601 UTC timestamp |
| `tags` | array | Full list of `{tag: N, val: "..."}` objects covering every FIX field in the message |

---

### 24.8 WebSocket Feed

#### `WS /api/ws/events`

A WebSocket endpoint for real-time admin UI updates. Multiple concurrent connections are supported.

**On connect**, the server immediately sends a state snapshot:
```json
{"type": "snapshot", "AAPL": "OPEN", "MSFT": "HALT"}
```

#### `book_update`

Emitted whenever the order book for a symbol changes: after any fill, after an order rests in the book, after a cancel, and after a replace.

```json
{
  "type": "book_update",
  "symbol": "AAPL",
  "bids": [
    {"price": "150.00", "qty": 500, "orders": 3},
    {"price": "149.90", "qty": 200, "orders": 1}
  ],
  "asks": [
    {"price": "150.10", "qty": 300, "orders": 2},
    {"price": "150.50", "qty": 100, "orders": 1}
  ]
}
```

Each price level:

| Field | Type | Description |
|---|---|---|
| `price` | string | Decimal price |
| `qty` | integer | Aggregate open quantity at this price |
| `orders` | integer | Number of resting orders at this price |

Up to 100 levels per side are included. `bids` are sorted best (**highest**) first; `asks` are sorted best (**lowest**) first.

#### `trade`

Emitted once per aggressor fill (not once per passive counterparty).

```json
{
  "type": "trade",
  "time": "2026-02-15T12:34:56.789Z",
  "symbol": "AAPL",
  "price": "150.05",
  "qty": 100,
  "aggressorSide": "BUY"
}
```

#### `order_event`

Emitted when an order is accepted into the book or cancelled by the engine.

```json
{
  "type": "order_event",
  "orderId": 42,
  "clOrdId": "ORD-001",
  "sessionId": "FIX.4.4:EXCHANGE->CLIENT:0:0:0",
  "symbol": "AAPL",
  "side": "BUY",
  "status": "NEW",
  "openQty": 100,
  "filledQty": 0
}
```

#### `session_connect`

Emitted when a FIX client completes Logon.

```json
{"type": "session_connect", "compId": "CLIENT", "sessionId": "FIX.4.4:EXCHANGE->CLIENT:0:0:0"}
```

#### `session_disconnect`

Emitted when a FIX client disconnects or logs out.

```json
{"type": "session_disconnect", "compId": "CLIENT", "sessionId": "FIX.4.4:EXCHANGE->CLIENT:0:0:0"}
```

#### `instrument_state`

Emitted when a state change is applied via `POST /api/instruments/{symbol}/state`.

```json
{"type": "instrument_state", "symbol": "MSFT", "state": "HALT"}
```

---

*Document version: 1.2 — February 2026*  
*Corresponds to `fix-simulator` version `1.0.0-SNAPSHOT`*  
*Changes in 1.1: FOK implemented as true all-or-nothing pre-check (§17, §18.5); MARKET orders accepted from clients (§16, §23).*  
*Changes in 1.2: Added section 24 — Admin HTTP API (REST endpoints + WebSocket feed); documents `/api/sessions`, `/api/messages`, `aggressorSide` in trade events, and all WebSocket event types.*
