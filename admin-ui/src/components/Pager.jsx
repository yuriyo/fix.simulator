import { useEffect, useRef, useState } from 'react';
import './pager.css';

export const PAGE_SIZES = [10, 25, 50, 100, 250, 500];

/**
 * Offset-based pager. Rows are counted from the newest (offset 0 = newest row).
 * The offset box is editable: type a number and press Enter or leave the box.
 */
export default function Pager({ offset, limit, total, onOffset, onLimit }) {
  const lastOffset = Math.max(0, total - 1);
  const shownFrom  = total === 0 ? 0 : offset + 1;
  const shownTo    = Math.min(offset + limit, total);

  const [draft, setDraft] = useState(String(offset));
  useEffect(() => setDraft(String(offset)), [offset]);

  const commit = () => {
    const n = parseInt(draft, 10);
    if (Number.isNaN(n)) { setDraft(String(offset)); return; }
    const clamped = Math.max(0, Math.min(n, lastOffset));
    onOffset(clamped);
    setDraft(String(clamped));
  };

  const atStart = offset <= 0;
  const atEnd   = offset + limit >= total;

  return (
    <div className="pager">
      <button className="btn-subtle" disabled={atStart} onClick={() => onOffset(0)} title="First page">«</button>
      <button className="btn-subtle" disabled={atStart} onClick={() => onOffset(Math.max(0, offset - limit))}>‹ Prev</button>

      <label className="toolbar-label" htmlFor="pager-offset">Offset</label>
      <input
        id="pager-offset"
        className="offset-input"
        type="number"
        min={0}
        max={lastOffset}
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={e => { if (e.key === 'Enter') commit(); }}
      />

      <button className="btn-subtle" disabled={atEnd} onClick={() => onOffset(offset + limit)}>Next ›</button>
      <button className="btn-subtle" disabled={atEnd} onClick={() => onOffset(Math.max(0, total - limit))} title="Last page">»</button>

      <span className="page-info">Rows {shownFrom}–{shownTo} of {total}</span>

      <label className="toolbar-label" htmlFor="pager-limit">Page size</label>
      <select id="pager-limit" className="filter-select" value={limit} onChange={e => onLimit(Number(e.target.value))}>
        {PAGE_SIZES.map(n => <option key={n} value={n}>{n}</option>)}
      </select>
    </div>
  );
}

/**
 * Like useState, but the value survives switching tabs (the panel unmounts) and page reloads.
 * Stored per browser in localStorage. Storage can be unavailable or full, so every access is guarded
 * and the page works without it.
 */
export function usePersisted(key, initial) {
  const [value, setValue] = useState(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? initial : JSON.parse(raw);
    } catch (_) {
      return initial;
    }
  });

  useEffect(() => {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch (_) {
      // Storage unavailable or full: keep working with in-memory state.
    }
  }, [key, value]);

  return [value, setValue];
}

/**
 * Returns a counter that changes at most once per `ms` while `trigger` keeps changing.
 * Used to refetch a page on live events without a request for every event under load.
 */
export function useThrottledTick(trigger, ms = 1000) {
  const [tick, setTick]     = useState(0);
  const lastRef             = useRef(0);
  const pendingRef          = useRef(null);

  useEffect(() => {
    // A refresh is already scheduled: let it run rather than restarting its timer,
    // so a steady stream of events still refreshes once per `ms`.
    if (pendingRef.current) return;
    const wait = Math.max(0, ms - (Date.now() - lastRef.current));
    pendingRef.current = setTimeout(() => {
      pendingRef.current = null;
      lastRef.current = Date.now();
      setTick(t => t + 1);
    }, wait);
  }, [trigger, ms]);

  useEffect(() => () => clearTimeout(pendingRef.current), []);

  return tick;
}
