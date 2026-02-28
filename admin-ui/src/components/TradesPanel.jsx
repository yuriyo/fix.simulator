import { useState, useMemo } from 'react';

export default function TradesPanel({ trades }) {
  const [symFilter, setSymFilter] = useState('');

  // Only symbols that have actually traded appear in the selector
  const tradedSymbols = useMemo(
    () => [...new Set(trades.map(t => t.symbol))].sort(),
    [trades]
  );

  const displayed = symFilter ? trades.filter(t => t.symbol === symFilter) : trades;

  return (
    <div className="card">
      <h2>Trade Tape</h2>
      <div className="toolbar">
        <label className="toolbar-label">Symbol</label>
        <select
          className="filter-select"
          value={symFilter}
          onChange={e => setSymFilter(e.target.value)}
        >
          <option value="">All</option>
          {tradedSymbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <span className="toolbar-count">{displayed.length} trade{displayed.length !== 1 ? 's' : ''}</span>
      </div>

      {displayed.length === 0 ? (
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
            {displayed.map((t, i) => (
              <tr key={i}>
                <td className="mono-time">{t.displayTime}</td>
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
