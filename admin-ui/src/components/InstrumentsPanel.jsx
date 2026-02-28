import { useState, useEffect } from 'react';

const API = '/api';
const STATES = ['OPEN', 'HALT', 'QUOTES_ONLY'];

export default function InstrumentsPanel() {
  const [instruments, setInstruments] = useState({});
  const [pending, setPending] = useState({});

  const load = () =>
    fetch(`${API}/instruments`).then(r => r.json()).then(setInstruments).catch(() => {});

  useEffect(() => { load(); const id = setInterval(load, 5000); return () => clearInterval(id); }, []);

  const setState = (symbol, state) => {
    setPending(p => ({ ...p, [symbol]: true }));
    fetch(`${API}/instruments/${symbol}/state`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ state }),
    })
      .then(() => load())
      .finally(() => setPending(p => { const n = { ...p }; delete n[symbol]; return n; }));
  };

  const symbols = Object.keys(instruments);

  return (
    <div className="card">
      <h2>Instruments</h2>
      <button className="btn-subtle" style={{ marginBottom: 12 }} onClick={load}>↺ Refresh</button>

      {symbols.length === 0
        ? <p className="empty">No instruments registered</p>
        : (
          <table>
            <thead>
              <tr><th>Symbol</th><th>State</th><th>Change State</th></tr>
            </thead>
            <tbody>
              {symbols.sort().map(sym => (
                <tr key={sym}>
                  <td><strong>{sym}</strong></td>
                  <td><StateBadge state={instruments[sym]} /></td>
                  <td>
                    <select
                      className="state-select"
                      value={instruments[sym]}
                      disabled={!!pending[sym]}
                      onChange={e => setState(sym, e.target.value)}
                    >
                      {STATES.map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </div>
  );
}

function StateBadge({ state }) {
  const cls = { OPEN: 'badge-green', HALT: 'badge-red', QUOTES_ONLY: 'badge-yellow' }[state] ?? 'badge-gray';
  return <span className={`badge ${cls}`}>{state}</span>;
}
