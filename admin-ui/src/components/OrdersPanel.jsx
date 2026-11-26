import { useState, useEffect } from 'react';
import { fmtMs } from '../App.jsx';
import { useConfirm } from './ConfirmDialog.jsx';
import Pager, { useThrottledTick, usePersisted } from './Pager.jsx';

const API = '/api';

export default function OrdersPanel({ orderEvents }) {
  const confirm = useConfirm();
  const [tab, setTab]                = usePersisted('fixsim.orders.tab', 'active');   // 'active' | 'history'
  const [symbolFilter, setSymFilter] = usePersisted('fixsim.orders.symbol', '');
  const [offset, setOffset]          = usePersisted('fixsim.orders.offset', 0);
  const [limit, setLimit]            = usePersisted('fixsim.orders.limit', 25);
  const [page, setPage]              = useState({ total: 0, rows: [] });
  const [reload, setReload]          = useState(0);
  const tick = useThrottledTick(orderEvents);

  // Fetch only the requested window from the server.
  useEffect(() => {
    const qs = new URLSearchParams({ offset: String(offset), limit: String(limit) });
    if (symbolFilter) qs.set('symbol', symbolFilter);
    const path = tab === 'active' ? 'orders' : 'orders/history';
    fetch(`${API}/${path}?${qs}`)
      .then(r => r.json())
      .then(p => {
        if (!p || p.error || !Array.isArray(p.rows)) return;
        setPage(p);
        // Rows can disappear between refreshes; move back to the last page that still has rows.
        if (p.total > 0 && offset >= p.total) {
          setOffset(Math.floor((p.total - 1) / limit) * limit);
        }
      })
      .catch(() => {});
  }, [tab, symbolFilter, offset, limit, tick, reload]);

  const refresh = () => setReload(r => r + 1);

  const cancelOrder = async (e, id) => {
    if (!e.shiftKey && !await confirm(`Cancel order ${id}?`)) return;
    fetch(`${API}/orders/${id}`, { method: 'DELETE' }).then(refresh);
  };

  const cancelAll = async (e) => {
    if (!symbolFilter) return alert('Set a symbol filter first');
    if (!e.shiftKey && !await confirm(`Cancel all active orders on ${symbolFilter}?`)) return;
    fetch(`${API}/orders?symbol=${encodeURIComponent(symbolFilter)}`, { method: 'DELETE' }).then(refresh);
  };

  const switchTab = (t) => { setTab(t); setOffset(0); };

  return (
    <div className="card">
      <h2>Orders</h2>

      <div className="toolbar" style={{ marginBottom: 12 }}>
        <button className={`tab${tab === 'active' ? ' active' : ''}`} onClick={() => switchTab('active')}>
          Active
        </button>
        <button className={`tab${tab === 'history' ? ' active' : ''}`} onClick={() => switchTab('history')}>
          History
        </button>
        <input
          className="filter-input"
          placeholder="Filter by symbol…"
          value={symbolFilter}
          onChange={e => { setSymFilter(e.target.value.trim()); setOffset(0); }}
        />
        {tab === 'active' && (
          <button className="btn-danger" onClick={cancelAll}>Cancel All on Symbol</button>
        )}
        <button className="btn-subtle" onClick={refresh}>↺ Refresh</button>
      </div>

      <Pager
        offset={offset}
        limit={limit}
        total={page.total}
        onOffset={setOffset}
        onLimit={n => { setLimit(n); setOffset(0); }}
      />
      <p className="pager-note">
        Offset counts from the newest order (0 = newest). Only orders still held in memory are listed.
      </p>

      {page.total === 0 ? (
        <p className="empty">No orders</p>
      ) : (
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
            {page.rows.map(o => (
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
