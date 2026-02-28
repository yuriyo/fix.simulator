import { useEffect, useState, useMemo } from 'react';
import { useConfirm } from './ConfirmDialog.jsx';

const API = '/api';

function isRegex(s) {
  return /[.*+?^${}()|[\]\\]/.test(s);
}

function fmtTime(iso) {
  if (!iso) return '\u2013';
  try {
    const d = new Date(iso);
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
      + ' ' + d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch { return iso; }
}

export default function ClientsPanel({ onViewMessages }) {
  const confirm = useConfirm();

  const [sessions, setSessions] = useState([]);
  const [blocked,  setBlocked]  = useState([]);
  const [allowed,  setAllowed]  = useState([]);
  const [newBlock, setNewBlock] = useState('');
  const [newAllow, setNewAllow] = useState('');
  const [filter,   setFilter]   = useState('');
  const [viewTab,  setViewTab]  = useState('all');   // all | active | done

  const load = () => {
    fetch(`${API}/sessions`).then(r => r.json()).then(setSessions).catch(() => {});
    fetch(`${API}/clients/blocked`).then(r => r.json()).then(d => setBlocked(d.blocked ?? [])).catch(() => {});
    fetch(`${API}/clients/allowed`).then(r => r.json()).then(d => setAllowed(d.allowed ?? [])).catch(() => {});
  };

  useEffect(() => { load(); const id = setInterval(load, 4000); return () => clearInterval(id); }, []);

  const disconnect = async (e, compId) => {
    if (!e.shiftKey && !(await confirm(`Force disconnect ${compId}?`))) return;
    fetch(`${API}/clients/${compId}`, { method: 'DELETE' }).then(load);
  };

  const addBlocked = async () => {
    if (!newBlock.trim()) return;
    if (!(await confirm(`Block ${newBlock.trim()}?`))) return;
    fetch(`${API}/clients/blocked`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ compId: newBlock.trim() }),
    }).then(() => { setNewBlock(''); load(); });
  };

  const removeBlocked = async (e, compId) => {
    if (!e.shiftKey && !(await confirm(`Unblock ${compId}?`))) return;
    fetch(`${API}/clients/blocked/${compId}`, { method: 'DELETE' }).then(load);
  };

  const addAllowed = async () => {
    if (!newAllow.trim()) return;
    if (!(await confirm(`Add ${newAllow.trim()} to allow list?`))) return;
    fetch(`${API}/clients/allowed`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ compId: newAllow.trim() }),
    }).then(() => { setNewAllow(''); load(); });
  };

  const removeAllowed = async (e, compId) => {
    if (!e.shiftKey && !(await confirm(`Remove ${compId} from allow list?`))) return;
    fetch(`${API}/clients/allowed/${compId}`, { method: 'DELETE' }).then(load);
  };

  const displayedSessions = useMemo(() => {
    let list = sessions;
    if (viewTab === 'active') list = list.filter(s => s.active);
    else if (viewTab === 'done') list = list.filter(s => !s.active);
    if (!filter.trim()) return list;
    try {
      const re = new RegExp(filter.trim(), 'i');
      return list.filter(s => re.test(s.compId));
    } catch {
      return list.filter(s => s.compId.toLowerCase().includes(filter.trim().toLowerCase()));
    }
  }, [sessions, viewTab, filter]);

  const activeCount = sessions.filter(s => s.active).length;

  return (
    <div>
      {/* Sessions table */}
      <div className="card">
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 10, flexWrap: 'wrap' }}>
          <h2 style={{ margin: 0 }}>
            Sessions
            <span style={{ marginLeft: 8, fontSize: 13, fontWeight: 400, color: '#64748b' }}>
              {activeCount} active / {sessions.length} total
            </span>
          </h2>
          <button className="btn-subtle" onClick={load}>↺ Refresh</button>
        </div>

        {/* Tab strip + filter */}
        <div style={{ display: 'flex', gap: 6, marginBottom: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          {[['all', 'All'], ['active', 'Active'], ['done', 'Done']].map(([val, label]) => (
            <button
              key={val}
              className={viewTab === val ? 'tab-pill active' : 'tab-pill'}
              onClick={() => setViewTab(val)}
            >
              {label}
              {val === 'active' && activeCount > 0 &&
                <span style={{ marginLeft: 5, background: '#22c55e', color: '#000', borderRadius: 8, padding: '1px 6px', fontSize: 10 }}>
                  {activeCount}
                </span>
              }
            </button>
          ))}
          <input
            className="filter-input"
            placeholder="Filter CompID\u2026"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            style={{ marginLeft: 'auto' }}
          />
          {filter && isRegex(filter) && <span className="badge badge-yellow">regex</span>}
        </div>

        {displayedSessions.length === 0 ? (
          <p className="empty">No sessions match</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>CompID</th>
                <th>Status</th>
                <th>Login</th>
                <th>Logout</th>
                <th style={{ textAlign: 'right' }}>Orders</th>
                <th style={{ textAlign: 'right' }}>Open</th>
                <th style={{ textAlign: 'right' }}>Cancelled</th>
                <th style={{ textAlign: 'right' }}>Filled</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {displayedSessions.map(s => (
                <tr key={s.sessionId}>
                  <td><strong>{s.compId}</strong></td>
                  <td>
                    {s.active
                      ? <span className="session-active-badge">&#9679; active</span>
                      : <span style={{ color: '#64748b', fontSize: 12 }}>offline</span>
                    }
                  </td>
                  <td style={{ fontSize: 11, fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{fmtTime(s.loginTime)}</td>
                  <td style={{ fontSize: 11, fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{fmtTime(s.logoutTime)}</td>
                  <td style={{ textAlign: 'right' }}>{s.orders}</td>
                  <td style={{ textAlign: 'right' }}>{s.open}</td>
                  <td style={{ textAlign: 'right' }}>{s.cancelled}</td>
                  <td style={{ textAlign: 'right' }}>{s.filled}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn-subtle" style={{ marginRight: 4 }}
                      onClick={() => onViewMessages && onViewMessages(s.compId)}>
                      Messages
                    </button>
                    {s.active && (
                      <button className="btn-danger" onClick={e => disconnect(e, s.compId)}>
                        Disconnect
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="grid-2">
        {/* Blocked list */}
        <div className="card">
          <h2>Blocked CompIDs</h2>
          <p style={{ fontSize: 11, color: '#64748b', marginBottom: 10 }}>
            Clients matching any entry are rejected at Logon (exact or regex).
          </p>
          <div className="inline-form">
            <input
              placeholder="COMPID or regex…"
              value={newBlock}
              onChange={e => setNewBlock(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && addBlocked()}
            />
            <button className="btn-danger" onClick={addBlocked}>Block</button>
          </div>
          {blocked.length === 0
            ? <p className="empty" style={{ marginTop: 10 }}>Block list is empty</p>
            : (
              <table style={{ marginTop: 10 }}>
                <thead><tr><th>Pattern</th><th>Type</th><th></th></tr></thead>
                <tbody>
                  {[...blocked].sort().map(id => (
                    <tr key={id}>
                      <td style={{ fontFamily: 'monospace' }}>{id}</td>
                      <td>
                        {isRegex(id)
                          ? <span className="badge badge-yellow">regex</span>
                          : <span className="badge badge-gray">exact</span>}
                      </td>
                      <td><button className="btn-subtle" onClick={e => removeBlocked(e, id)}>Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </div>

        {/* Allowed list */}
        <div className="card">
          <h2>Allowed CompIDs</h2>
          <p style={{ fontSize: 11, color: '#64748b', marginBottom: 10 }}>
            When non-empty, only matching CompIDs may connect (allowlist mode).
            Leave empty to allow all (except blocked). Supports regex.
          </p>
          <div className="inline-form">
            <input
              placeholder="COMPID or regex…"
              value={newAllow}
              onChange={e => setNewAllow(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && addAllowed()}
            />
            <button className="btn-primary" onClick={addAllowed}>Allow</button>
          </div>
          {allowed.length === 0
            ? <p className="empty" style={{ marginTop: 10 }}>Allowed list is empty (open to all)</p>
            : (
              <table style={{ marginTop: 10 }}>
                <thead><tr><th>Pattern</th><th>Type</th><th></th></tr></thead>
                <tbody>
                  {[...allowed].sort().map(id => (
                    <tr key={id}>
                      <td style={{ fontFamily: 'monospace' }}>{id}</td>
                      <td>
                        {isRegex(id)
                          ? <span className="badge badge-yellow">regex</span>
                          : <span className="badge badge-gray">exact</span>}
                      </td>
                      <td><button className="btn-subtle" onClick={e => removeAllowed(e, id)}>Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </div>
      </div>
    </div>
  );
}
