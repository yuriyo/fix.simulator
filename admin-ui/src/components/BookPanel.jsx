import { useState } from 'react';

const DEPTH_OPTIONS = [
  { label: 'BBO', value: 1 },
  { label: '5',   value: 5 },
  { label: '10',  value: 10 },
  { label: '100', value: 100 },
  { label: 'All', value: Infinity },
];

export default function BookPanel({ books }) {
  const symbols = Object.keys(books);
  const [symFilter, setSymFilter] = useState('');
  const [depth, setDepth]         = useState(5);

  const displayed = symFilter ? symbols.filter(s => s === symFilter) : symbols;

  return (
    <div>
      <div className="toolbar">
        <label className="toolbar-label">Symbol</label>
        <select className="filter-select" value={symFilter} onChange={e => setSymFilter(e.target.value)}>
          <option value="">All</option>
          {symbols.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <label className="toolbar-label">Depth</label>
        <select
          className="filter-select"
          value={isFinite(depth) ? depth : 'all'}
          onChange={e => setDepth(e.target.value === 'all' ? Infinity : Number(e.target.value))}
        >
          {DEPTH_OPTIONS.map(o => (
            <option key={o.label} value={isFinite(o.value) ? o.value : 'all'}>{o.label}</option>
          ))}
        </select>
      </div>

      <div className={displayed.length <= 2 ? 'grid-2' : 'grid-3'}>
        {displayed.length === 0 && (
          <div className="card"><p className="empty">No book data yet – waiting for orders…</p></div>
        )}
        {displayed.map(sym => (
          <SymbolBook key={sym} symbol={sym} book={books[sym]} depth={depth} />
        ))}
      </div>
    </div>
  );
}

function SymbolBook({ symbol, book, depth }) {
  const limit = isFinite(depth) ? depth : undefined;
  const bids  = (book?.bids ?? []).slice(0, limit);
  const asks  = (book?.asks ?? []).slice(0, limit);

  // ── Level edit dialog ───────────────────────────────────────────────────
  // dialog: null | { side:'BUY'|'SELL', price:string, qty:string, warn:string }
  const [dialog, setDialog] = useState(null);
  const [busy, setBusy]     = useState(false);

  const openDialog = (side, price = '', qty = '') => {
    const warn = getCrossingWarn(side, price, book);
    setDialog({ side, price: String(price), qty: String(qty), warn });
  };

  const openAddDialog = () => openDialog('BUY', '', '');

  const openEditDialog = (side, level) => openDialog(side, level.price, level.qty);

  const updateField = (field, value) => {
    setDialog(d => {
      const next = { ...d, [field]: value };
      next.warn = getCrossingWarn(next.side, next.price, book);
      return next;
    });
  };

  const submitLevel = () => {
    if (!dialog) return;
    const qty = parseInt(dialog.qty, 10);
    if (isNaN(qty) || qty < 0) return;
    if (!dialog.price || isNaN(parseFloat(dialog.price))) return;
    setBusy(true);
    fetch(`/api/instruments/${encodeURIComponent(symbol)}/book`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ side: dialog.side, price: dialog.price, qty }),
    })
      .then(() => { setBusy(false); setDialog(null); })
      .catch(() => setBusy(false));
  };

  return (
    <div className="card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h2 style={{ margin: 0 }}>{symbol}</h2>
        <button className="btn-subtle" style={{ fontSize: 11 }} onClick={openAddDialog}>+ Level</button>
      </div>
      <div className="book-cols">
        <div className="book-col">
          <div className="book-header bids">Bids</div>
          {bids.length === 0 && <div className="empty">–</div>}
          {bids.map((l, i) => (
            <div key={i} className="book-row" style={{ cursor: 'pointer' }}
                 onClick={() => openEditDialog('BUY', l)}>
              <span className="book-px bid">{l.price}</span>
              <span className="book-qty">{l.qty} <small>({l.orders})</small></span>
            </div>
          ))}
        </div>
        <div className="book-col">
          <div className="book-header asks">Asks</div>
          {asks.length === 0 && <div className="empty">–</div>}
          {asks.map((l, i) => (
            <div key={i} className="book-row" style={{ cursor: 'pointer' }}
                 onClick={() => openEditDialog('SELL', l)}>
              <span className="book-px ask">{l.price}</span>
              <span className="book-qty">{l.qty} <small>({l.orders})</small></span>
            </div>
          ))}
        </div>
      </div>

      {/* Level add/edit dialog */}
      {dialog && (
        <div className="confirm-overlay" onClick={() => !busy && setDialog(null)}>
          <div className="confirm-box" onClick={e => e.stopPropagation()} style={{ minWidth: 320 }}>
            <p className="confirm-msg">
              {dialog.price ? 'Edit' : 'Add'} price level — <strong>{symbol}</strong>
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 12 }}>
              <label style={{ fontSize: 12, color: '#94a3b8' }}>
                Side
                <select
                  className="filter-select"
                  style={{ display: 'block', width: '100%', marginTop: 4 }}
                  value={dialog.side}
                  onChange={e => updateField('side', e.target.value)}
                  disabled={busy}
                >
                  <option value="BUY">BUY (bid)</option>
                  <option value="SELL">SELL (ask)</option>
                </select>
              </label>
              <label style={{ fontSize: 12, color: '#94a3b8' }}>
                Price
                <input
                  className="filter-input"
                  type="number"
                  min="0"
                  step="any"
                  style={{ display: 'block', width: '100%', marginTop: 4 }}
                  value={dialog.price}
                  onChange={e => updateField('price', e.target.value)}
                  disabled={busy}
                />
              </label>
              <label style={{ fontSize: 12, color: '#94a3b8' }}>
                Quantity <small style={{ color: '#64748b' }}>(0 = remove level)</small>
                <input
                  className="filter-input"
                  type="number"
                  min="0"
                  step="1"
                  style={{ display: 'block', width: '100%', marginTop: 4 }}
                  value={dialog.qty}
                  onChange={e => updateField('qty', e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && submitLevel()}
                  disabled={busy}
                />
              </label>
              {dialog.warn && (
                <p style={{ color: '#fbbf24', fontSize: 12, margin: 0, background: '#1c1a10', padding: '6px 10px', borderRadius: 6 }}>
                  ⚠ {dialog.warn}
                </p>
              )}
            </div>
            <div className="confirm-actions">
              <button className="btn-subtle" onClick={() => setDialog(null)} disabled={busy}>Cancel</button>
              <button className="btn-primary" onClick={submitLevel} disabled={busy}>
                {busy ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function getCrossingWarn(side, priceStr, book) {
  const price = parseFloat(priceStr);
  if (!priceStr || isNaN(price)) return '';
  if (side === 'BUY') {
    const bestAsk = parseFloat(book?.asks?.[0]?.price);
    if (!isNaN(bestAsk) && price >= bestAsk) {
      return `BUY price ${price} ≥ best ask ${bestAsk} — this level crosses the book`;
    }
  } else {
    const bestBid = parseFloat(book?.bids?.[0]?.price);
    if (!isNaN(bestBid) && price <= bestBid) {
      return `SELL price ${price} ≤ best bid ${bestBid} — this level crosses the book`;
    }
  }
  return '';
}
