import { useState, useEffect } from 'react';
import Pager, { useThrottledTick, usePersisted } from './Pager.jsx';

const API = '/api';

/** Format ISO-8601 instant as HH:MM:SS.mmm */
function fmtTime(isoStr) {
  if (!isoStr) return '–';
  const d = new Date(isoStr);
  return d.toLocaleTimeString('en-US', {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
    fractionalSecondDigits: 3,
  });
}

/** Extract the TargetCompID (client side) from a QFJ session-id string. */
function clientOf(sessionId) {
  if (!sessionId) return '–';
  const m = sessionId.match(/->(.+)$/);
  return m ? m[1] : sessionId;
}

export default function ExecutionsPanel({ orderEvents }) {
  const [symbols, setSymbols]     = useState([]);
  const [symFilter, setSymFilter] = usePersisted('fixsim.executions.symbol', '');
  const [cliFilter, setCliFilter] = usePersisted('fixsim.executions.client', '');   // substring of the session id, applied by the server
  const [offset, setOffset]       = usePersisted('fixsim.executions.offset', 0);
  const [limit, setLimit]         = usePersisted('fixsim.executions.limit', 25);
  const [page, setPage]           = useState({ total: 0, rows: [] });
  const [reload, setReload]       = useState(0);
  const tick = useThrottledTick(orderEvents);

  useEffect(() => {
    fetch(`${API}/instruments`)
      .then(r => r.json())
      .then(m => setSymbols(Object.keys(m).sort()))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const qs = new URLSearchParams({ offset: String(offset), limit: String(limit) });
    if (symFilter) qs.set('symbol', symFilter);
    if (cliFilter) qs.set('clientId', cliFilter);
    fetch(`${API}/executions?${qs}`)
      .then(r => r.json())
      .then(p => {
        if (!p || p.error || !Array.isArray(p.rows)) return;
        setPage(p);
        if (p.total > 0 && offset >= p.total) {
          setOffset(Math.floor((p.total - 1) / limit) * limit);
        }
      })
      .catch(() => {});
  }, [symFilter, cliFilter, offset, limit, tick, reload]);

  return (
    <div className="card">
      <h2>Executions</h2>
      <div className="toolbar">
        <label className="toolbar-label">Symbol</label>
        <select className="filter-select" value={symFilter} onChange={e => { setSymFilter(e.target.value); setOffset(0); }}>
          <option value="">All</option>
          {symbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>

        <label className="toolbar-label">Client</label>
        <input
          className="filter-input"
          placeholder="Client contains…"
          value={cliFilter}
          onChange={e => { setCliFilter(e.target.value.trim()); setOffset(0); }}
        />

        <button className="btn-subtle" onClick={() => setReload(r => r + 1)}>↺ Refresh</button>
      </div>

      <Pager
        offset={offset}
        limit={limit}
        total={page.total}
        onOffset={setOffset}
        onLimit={n => { setLimit(n); setOffset(0); }}
      />
      <p className="pager-note">
        Offset counts from the newest execution (0 = newest). Only executions still held in memory are listed.
      </p>

      {page.total === 0 ? (
        <p className="empty">No executions</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>ExecID</th>
              <th>Symbol</th>
              <th>Side</th>
              <th>Type</th>
              <th>Price</th>
              <th>Qty</th>
              <th>ClOrdID</th>
              <th>Client</th>
            </tr>
          </thead>
          <tbody>
            {page.rows.map((e, i) => (
              <tr key={`${e.execId}-${i}`}>
                <td className="mono-time">{fmtTime(e.time)}</td>
                <td style={{ fontFamily: 'monospace', fontSize: 10, color: '#475569' }}>{e.execId}</td>
                <td><strong className="sym-label">{e.symbol}</strong></td>
                <td>
                  {e.side
                    ? <span className={`badge ${e.side === 'BUY' ? 'badge-green' : 'badge-red'}`}>{e.side}</span>
                    : <span className="badge badge-gray">–</span>}
                </td>
                <td><ExecTypeBadge type={e.execType} /></td>
                <td>{e.price ?? '–'}</td>
                <td className="num-cell">{e.qty > 0 ? e.qty : '–'}</td>
                <td style={{ fontFamily: 'monospace', fontSize: 11 }}>{e.clOrdId}</td>
                <td style={{ fontSize: 11, color: '#64748b' }}>{clientOf(e.sessionId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ExecTypeBadge({ type }) {
  const cls = {
    NEW:          'badge-blue',
    PARTIAL_FILL: 'badge-yellow',
    FILL:         'badge-green',
    CANCELLED:    'badge-gray',
    REPLACED:     'badge-gray',
    REJECTED:     'badge-red',
  }[type] ?? 'badge-gray';
  return <span className={`badge ${cls}`}>{type}</span>;
}
