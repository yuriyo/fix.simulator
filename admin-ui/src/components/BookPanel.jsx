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

  return (
    <div className="card">
      <h2>{symbol}</h2>
      <div className="book-cols">
        <div className="book-col">
          <div className="book-header bids">Bids</div>
          {bids.length === 0 && <div className="empty">–</div>}
          {bids.map((l, i) => (
            <div key={i} className="book-row">
              <span className="book-px bid">{l.price}</span>
              <span className="book-qty">{l.qty} <small>({l.orders})</small></span>
            </div>
          ))}
        </div>
        <div className="book-col">
          <div className="book-header asks">Asks</div>
          {asks.length === 0 && <div className="empty">–</div>}
          {asks.map((l, i) => (
            <div key={i} className="book-row">
              <span className="book-px ask">{l.price}</span>
              <span className="book-qty">{l.qty} <small>({l.orders})</small></span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
