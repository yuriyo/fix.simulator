import { useState, useEffect } from 'react';
import { fmtMs } from '../App.jsx';
import Pager, { useThrottledTick, usePersisted } from './Pager.jsx';

const API = '/api';

export default function TradesPanel({ orderEvents }) {
  const [symbols, setSymbols]     = useState([]);
  const [symFilter, setSymFilter] = usePersisted('fixsim.trades.symbol', '');
  const [offset, setOffset]       = usePersisted('fixsim.trades.offset', 0);
  const [limit, setLimit]         = usePersisted('fixsim.trades.limit', 25);
  const [page, setPage]           = useState({ total: 0, rows: [] });
  const tick = useThrottledTick(orderEvents);

  // Symbol choices come from the instrument list, so a symbol shows up before it trades.
  useEffect(() => {
    fetch(`${API}/instruments`)
      .then(r => r.json())
      .then(m => setSymbols(Object.keys(m).sort()))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const qs = new URLSearchParams({ offset: String(offset), limit: String(limit) });
    if (symFilter) qs.set('symbol', symFilter);
    fetch(`${API}/trades?${qs}`)
      .then(r => r.json())
      .then(p => {
        if (!p || p.error || !Array.isArray(p.rows)) return;
        setPage(p);
        if (p.total > 0 && offset >= p.total) {
          setOffset(Math.floor((p.total - 1) / limit) * limit);
        }
      })
      .catch(() => {});
  }, [symFilter, offset, limit, tick]);

  return (
    <div className="card">
      <h2>Trade Tape</h2>
      <div className="toolbar">
        <label className="toolbar-label">Symbol</label>
        <select
          className="filter-select"
          value={symFilter}
          onChange={e => { setSymFilter(e.target.value); setOffset(0); }}
        >
          <option value="">All</option>
          {symbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <Pager
        offset={offset}
        limit={limit}
        total={page.total}
        onOffset={setOffset}
        onLimit={n => { setLimit(n); setOffset(0); }}
      />
      <p className="pager-note">
        Offset counts from the newest trade (0 = newest). Only trades still held in memory are listed.
      </p>

      {page.total === 0 ? (
        <p className="empty">No trades yet</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Symbol</th>
              <th>Aggressor</th>
              <th>Price</th>
              <th>Qty</th>
            </tr>
          </thead>
          <tbody>
            {page.rows.map((t, i) => (
              <tr key={`${t.time}-${t.symbol}-${i}`}>
                <td className="mono-time">{t.time ? fmtMs(new Date(t.time)) : '–'}</td>
                <td><strong className="sym-label">{t.symbol}</strong></td>
                <td>
                  <span className={`badge ${t.aggressorSide === 'BUY' ? 'badge-green' : 'badge-red'}`}>
                    {t.aggressorSide ?? '–'}
                  </span>
                </td>
                <td>{t.price}</td>
                <td className="num-cell">{t.qty}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
