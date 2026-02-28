import { useState, useEffect, useMemo } from 'react';
import { fmtMs } from '../App.jsx';
import { useConfirm } from './ConfirmDialog.jsx';

const API = '/api';
const PAGE_SIZES = [10, 25, 100];

export default function OrdersPanel({ orderEvents }) {
  const confirm = useConfirm();
  const [orders, setOrders]     = useState([]);
  const [history, setHistory]   = useState([]);
  const [tab, setTab]           = useState('active');
  const [symbolFilter, setSymFilter] = useState('');
  const [page, setPage]         = useState(0);
  const [pageSize, setPageSize] = useState(25);

  const loadOrders = () =>
    fetch(`${API}/orders${symbolFilter ? `?symbol=${symbolFilter}` : ''}`)
      .then(r => r.json()).then(setOrders).catch(() => {});

  const loadHistory = () =>
    fetch(`${API}/orders/history${symbolFilter ? `?symbol=${symbolFilter}` : ''}`)
      .then(r => r.json()).then(setHistory).catch(() => {});

  useEffect(() => {
    loadOrders();
    loadHistory();
    setPage(0); // reset page when data refreshes
  }, [orderEvents, symbolFilter]);

  const cancelOrder = async (e, id) => {
    if (!e.shiftKey && !await confirm(`Cancel order ${id}?`)) return;
    fetch(`${API}/orders/${id}`, { method: 'DELETE' })
      .then(() => { loadOrders(); loadHistory(); });
  };

  const cancelAll = async (e) => {
    if (!symbolFilter) return alert('Set a symbol filter first');
    if (!e.shiftKey && !await confirm(`Cancel all active orders on ${symbolFilter}?`)) return;
    fetch(`${API}/orders?symbol=${symbolFilter}`, { method: 'DELETE' })
      .then(() => { loadOrders(); loadHistory(); });
  };

  const base     = tab === 'active' ? orders : history;
  const total    = base.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage  = Math.min(page, pageCount - 1);
  const displayed = useMemo(
    () => base.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [base, safePage, pageSize]
  );

  return (
    <div className="card">
      <h2>Orders</h2>

      <div className="toolbar" style={{ marginBottom: 12 }}>
        <button className={`tab${tab === 'active' ? ' active' : ''}`} onClick={() => { setTab('active'); setPage(0); }}>
          Active ({orders.length})
        </button>
        <button className={`tab${tab === 'history' ? ' active' : ''}`} onClick={() => { setTab('history'); setPage(0); }}>
          History ({history.length})
        </button>
        <input
          className="filter-input"
          placeholder="Filter by symbol…"
          value={symbolFilter}
          onChange={e => setSymFilter(e.target.value)}
        />
        {tab === 'active' && (
          <button className="btn-danger" onClick={cancelAll}>Cancel All on Symbol</button>
        )}
        <button className="btn-subtle" onClick={() => { loadOrders(); loadHistory(); }}>↺ Refresh</button>

        <div style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center' }}>
          <label className="toolbar-label">Page size</label>
          <select
            className="filter-select"
            value={pageSize}
            onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
          >
            {PAGE_SIZES.map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
      </div>

      {base.length === 0 ? (
        <p className="empty">No orders</p>
      ) : (
        <>
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>ID</th><th>ClOrdID</th><th>Client</th><th>Symbol</th>
                <th>Side</th><th>Type</th><th>TIF</th><th>Status</th>
                <th>Open</th><th>Filled</th><th>Limit Px</th>
                {tab === 'active' && <th>Action</th>}
              </tr>
            </thead>
            <tbody>
              {displayed.map(o => (
                <tr key={o.orderId}>
                  <td className="mono-time">{o.createTime ? fmtMs(new Date(o.createTime)) : '–'}</td>
                  <td>{o.orderId}</td>
                  <td style={{ fontFamily: 'monospace', fontSize: 11 }}>{o.clOrdId}</td>
                  <td style={{ fontSize: 10, color: '#64748b' }}>{clientOf(o.sessionId)}</td>
                  <td><strong>{o.symbol}</strong></td>
                  <td><span className={`badge ${o.side === 'BUY' ? 'badge-green' : 'badge-red'}`}>{o.side}</span></td>
                  <td>{o.type}</td>
                  <td>{o.tif}</td>
                  <td><StatusBadge status={o.status} /></td>
                  <td>{o.openQty}</td>
                  <td>{o.filledQty}</td>
                  <td>{o.limitPrice ?? '–'}</td>
                  {tab === 'active' && (
                    <td><button className="btn-danger" onClick={e => cancelOrder(e, o.orderId)}>Cancel</button></td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination controls */}
          <div className="pagination">
            <button className="btn-subtle" disabled={safePage === 0} onClick={() => setPage(0)}>«</button>
            <button className="btn-subtle" disabled={safePage === 0} onClick={() => setPage(p => Math.max(0, p - 1))}>‹</button>
            <span className="page-info">Page {safePage + 1} / {pageCount} ({total} rows)</span>
            <button className="btn-subtle" disabled={safePage >= pageCount - 1} onClick={() => setPage(p => Math.min(pageCount - 1, p + 1))}>›</button>
            <button className="btn-subtle" disabled={safePage >= pageCount - 1} onClick={() => setPage(pageCount - 1)}>»</button>
          </div>
        </>
      )}
    </div>
  );
}

/** Extract TargetCompID from a QFJ session-id string. */
function clientOf(sessionId) {
  if (!sessionId) return '–';
  const m = sessionId.match(/->(.+)$/);
  return m ? m[1] : sessionId;
}

function StatusBadge({ status }) {
  const cls = {
    NEW: 'badge-blue', PARTIALLY_FILLED: 'badge-yellow', FILLED: 'badge-green',
    CANCELLED: 'badge-gray', REJECTED: 'badge-red',
  }[status] ?? 'badge-gray';
  return <span className={`badge ${cls}`}>{status}</span>;
}
