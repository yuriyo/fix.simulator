import { useState, useEffect, useMemo } from 'react';

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
  const [all, setAll]             = useState([]);     // full list from server
  const [symFilter, setSymFilter] = useState('');
  const [cliFilter, setCliFilter] = useState('');

  const load = () =>
    fetch(`${API}/executions`)
      .then(r => r.json())
      .then(setAll)
      .catch(() => {});

  // Reload whenever an order event fires (new orders / fills)
  useEffect(() => { load(); }, [orderEvents]);

  // Derive dropdown options from the full list (conjunctive AND pre-applied to the OTHER filter)
  const symbols  = useMemo(() => [...new Set(all.map(e => e.symbol))].sort(), [all]);
  const clients  = useMemo(() => [...new Set(all.map(e => clientOf(e.sessionId)).filter(Boolean))].sort(), [all]);

  // Apply both filters (AND)
  const displayed = useMemo(() =>
    all.filter(e =>
      (!symFilter || e.symbol === symFilter) &&
      (!cliFilter || clientOf(e.sessionId) === cliFilter)
    ),
    [all, symFilter, cliFilter]
  );

  return (
    <div className="card">
      <h2>Executions</h2>
      <div className="toolbar">
        <label className="toolbar-label">Symbol</label>
        <select className="filter-select" value={symFilter} onChange={e => setSymFilter(e.target.value)}>
          <option value="">All</option>
          {symbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>

        <label className="toolbar-label">Client</label>
        <select className="filter-select" value={cliFilter} onChange={e => setCliFilter(e.target.value)}>
          <option value="">All</option>
          {clients.map(c => <option key={c} value={c}>{c}</option>)}
        </select>

        <button className="btn-subtle" onClick={load}>↺ Refresh</button>
        <span className="toolbar-count">{displayed.length} exec{displayed.length !== 1 ? 's' : ''}</span>
      </div>

      {displayed.length === 0 ? (
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
            {displayed.map((e, i) => (
              <tr key={i}>
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
