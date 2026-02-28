import { useCallback, useEffect, useRef, useState } from 'react';
import { fmtMs } from '../App.jsx';

const API = '/api';

/** Human-readable FIX 4.4 message type names (tag 35 values). */
const MSG_TYPE_NAMES = {
  '0': 'Heartbeat', '1': 'Test Request', '2': 'Resend Request',
  '3': 'Reject', '4': 'Sequence Reset', '5': 'Logout',
  'A': 'Logon', '8': 'Execution Report', '9': 'Cancel Reject',
  'D': 'New Order Single', 'F': 'Order Cancel', 'G': 'Cancel/Replace',
  'V': 'Market Data Request', 'W': 'Market Data Snapshot',
  'X': 'Market Data Incremental', 'i': 'Mass Quote', 'S': 'Quote',
};

/** Well-known FIX tag numbers → readable names. */
const TAG_NAMES = {
  8: 'BeginString', 9: 'BodyLength', 35: 'MsgType', 34: 'MsgSeqNum',
  49: 'SenderCompID', 56: 'TargetCompID', 52: 'SendingTime', 10: 'CheckSum',
  11: 'ClOrdID', 37: 'OrderID', 17: 'ExecID', 150: 'ExecType',
  39: 'OrdStatus', 55: 'Symbol', 54: 'Side', 38: 'OrderQty',
  40: 'OrdType', 44: 'Price', 99: 'StopPx', 59: 'TimeInForce',
  60: 'TransactTime', 58: 'Text', 103: 'OrdRejReason',
  41: 'OrigClOrdID', 14: 'CumQty', 6: 'AvgPx', 151: 'LeavesQty',
  1: 'Account', 207: 'SecurityExchange', 15: 'Currency',
};

function msgTypeName(t) {
  return MSG_TYPE_NAMES[t] ? `${t} – ${MSG_TYPE_NAMES[t]}` : t;
}
function tagName(tag) {
  return TAG_NAMES[tag] ? `${tag} (${TAG_NAMES[tag]})` : String(tag);
}

// ── Read / write URL query params ─────────────────────────────────────────
function getParams() {
  const p = new URLSearchParams(window.location.search);
  return {
    clientId: p.get('clientId') || '',
    symbol:   p.get('symbol')   || '',
  };
}
function setParams(clientId, symbol) {
  const p = new URLSearchParams(window.location.search);
  if (clientId) p.set('clientId', clientId); else p.delete('clientId');
  if (symbol)   p.set('symbol', symbol);     else p.delete('symbol');
  window.history.replaceState({}, '', `${window.location.pathname}?${p.toString()}`);
}

const PAGE_SIZES = [25, 50, 100];

export default function MessagesPanel({ onBack }) {
  const init = getParams();
  const [clientId, setClientId] = useState(init.clientId);
  const [symbol,   setSymbol]   = useState(init.symbol);
  const [data,     setData]     = useState({ total: 0, page: 1, pageSize: 50, pageCount: 0, messages: [] });
  const [pageSize, setPageSize] = useState(50);
  const [page,     setPage]     = useState(1);
  const [expanded, setExpanded] = useState(null); // message id that is expanded
  const [loading,  setLoading]  = useState(false);

  // clientId list for dropdown (populated from sessions API)
  const [clientIds, setClientIds] = useState([]);
  // symbol list for dropdown (from instruments API)
  const [symbols, setSymbols]     = useState([]);

  // Load filter options once
  useEffect(() => {
    fetch(`${API}/sessions`).then(r => r.json()).then(sessions => {
      const ids = [...new Set(sessions.map(s => s.compId))].sort();
      setClientIds(ids);
    }).catch(() => {});
    fetch(`${API}/instruments`).then(r => r.json()).then(inst => {
      setSymbols(Object.keys(inst).sort());
    }).catch(() => {});
  }, []);

  const fetchPage = useCallback((cid, sym, pg, ps) => {
    setLoading(true);
    const q = new URLSearchParams();
    if (cid) q.set('clientId', cid);
    if (sym) q.set('symbol',   sym);
    q.set('page', pg);
    q.set('pageSize', ps);
    fetch(`${API}/messages?${q}`)
      .then(r => r.json())
      .then(d => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  // (Re-)fetch whenever filters or page/size change
  useEffect(() => {
    setParams(clientId, symbol);
    fetchPage(clientId, symbol, page, pageSize);
  }, [clientId, symbol, page, pageSize, fetchPage]);

  const applyFilter = () => {
    setPage(1);
    setExpanded(null);
    setParams(clientId, symbol);
    fetchPage(clientId, symbol, 1, pageSize);
  };

  const handlePageSize = (ps) => {
    setPageSize(ps);
    setPage(1);
    setExpanded(null);
  };

  const toggleExpand = (id) => setExpanded(prev => prev === id ? null : id);

  const { messages, total, pageCount } = data;

  return (
    <div className="card">
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
        {onBack && (
          <button className="btn-subtle" onClick={onBack}>← Sessions</button>
        )}
        <h2 style={{ margin: 0 }}>FIX Message History</h2>
        {loading && <span style={{ fontSize: 11, color: '#64748b' }}>loading…</span>}
      </div>

      {/* Filters */}
      <div className="toolbar" style={{ marginBottom: 8 }}>
        <span className="toolbar-label">Client</span>
        <select className="filter-select" value={clientId} onChange={e => { setClientId(e.target.value); setPage(1); }}>
          <option value="">All clients</option>
          {clientIds.map(id => <option key={id} value={id}>{id}</option>)}
        </select>

        <span className="toolbar-label">Symbol</span>
        <select className="filter-select" value={symbol} onChange={e => { setSymbol(e.target.value); setPage(1); }}>
          <option value="">All symbols</option>
          {symbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>

        <button className="btn-subtle" onClick={applyFilter}>Apply</button>

        <span className="toolbar-count">{total} messages</span>
      </div>

      {/* Table */}
      {messages.length === 0
        ? <p className="empty">No messages{(clientId || symbol) ? ' matching filters' : ''}</p>
        : (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Time</th>
                <th>Dir</th>
                <th>Client</th>
                <th>MsgType</th>
                <th>Symbol</th>
              </tr>
            </thead>
            <tbody>
              {messages.map((msg, idx) => {
                const isExp = expanded === msg.id;
                const rowNum = (page - 1) * pageSize + idx + 1;
                return [
                  <tr key={msg.id} className={`msg-row${isExp ? ' msg-row-active' : ''}`}
                      onClick={() => toggleExpand(msg.id)} style={{ cursor: 'pointer' }}>
                    <td className="num-cell">{rowNum}</td>
                    <td className="mono-time">{fmtMs(new Date(msg.time))}</td>
                    <td>
                      <span className={`badge ${msg.direction === 'IN' ? 'badge-blue' : 'badge-gray'}`}>
                        {msg.direction}
                      </span>
                    </td>
                    <td style={{ fontWeight: 600, color: '#a5b4fc' }}>{msg.compId}</td>
                    <td style={{ fontFamily: 'monospace', fontSize: 11 }}>{msgTypeName(msg.msgType)}</td>
                    <td className="sym-label">{msg.symbol || <span style={{ color: '#475569' }}>—</span>}</td>
                  </tr>,
                  isExp && (
                    <tr key={`${msg.id}-expand`}>
                      <td colSpan={6} style={{ padding: 0 }}>
                        <div className="msg-expand">
                          <table className="tag-table">
                            <thead>
                              <tr>
                                <th>Tag</th>
                                <th>Value</th>
                              </tr>
                            </thead>
                            <tbody>
                              {msg.tags.map((tv, i) => (
                                <tr key={i}>
                                  <td className="tag-num">{tagName(tv.tag)}</td>
                                  <td className="tag-val">{tv.val}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </td>
                    </tr>
                  ),
                ];
              })}
            </tbody>
          </table>
        )}

      {/* Pagination */}
      {pageCount > 1 && (
        <div className="pagination">
          <button className="btn-subtle" onClick={() => setPage(1)}        disabled={page <= 1}>«</button>
          <button className="btn-subtle" onClick={() => setPage(p => p-1)} disabled={page <= 1}>‹</button>
          <span className="page-info">Page {page} / {pageCount} ({total} rows)</span>
          <button className="btn-subtle" onClick={() => setPage(p => p+1)} disabled={page >= pageCount}>›</button>
          <button className="btn-subtle" onClick={() => setPage(pageCount)} disabled={page >= pageCount}>»</button>
          <select className="filter-select" value={pageSize}
                  onChange={e => handlePageSize(Number(e.target.value))}>
            {PAGE_SIZES.map(ps => <option key={ps} value={ps}>{ps} / page</option>)}
          </select>
        </div>
      )}
    </div>
  );
}
