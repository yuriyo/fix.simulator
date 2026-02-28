import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

// ── Context ────────────────────────────────────────────────────────────────
const ConfirmContext = createContext(null);

/**
 * Wrap the application with this provider, then call `useConfirm()` in any
 * child component to get a `confirm(message)` function that returns a Promise<boolean>.
 *
 * Shift+Click bypass: the caller should check `e.shiftKey` before calling
 * `confirm()` – if true, skip conf entirely.
 */
export function ConfirmProvider({ children }) {
  const [state, setState] = useState(null); // { message, resolve }

  const confirm = useCallback((message) =>
    new Promise(resolve => setState({ message, resolve })), []);

  const respond = (ok) => {
    if (state) { state.resolve(ok); setState(null); }
  };

  // ESC closes (= cancel)
  useEffect(() => {
    if (!state) return;
    const onKey = (e) => { if (e.key === 'Escape') respond(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [state]); // eslint-disable-line

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {state && (
        <div className="confirm-overlay" onClick={() => respond(false)}>
          <div className="confirm-box" onClick={e => e.stopPropagation()}>
            <p className="confirm-msg">{state.message}</p>
            <div className="confirm-actions">
              <button className="btn-subtle" onClick={() => respond(false)}>Cancel</button>
              <button className="btn-danger" onClick={() => respond(true)} autoFocus>Confirm</button>
            </div>
            <p className="confirm-hint">Tip: hold <kbd>Shift</kbd> while clicking to skip this dialog</p>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}

/** Returns a `confirm(message) → Promise<boolean>` function. */
export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used inside <ConfirmProvider>');
  return ctx;
}
