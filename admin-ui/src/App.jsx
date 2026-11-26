import { useState, useEffect, useRef, useCallback } from 'react';
import DashboardPanel  from './components/DashboardPanel.jsx';
import BookPanel       from './components/BookPanel.jsx';
import TradesPanel     from './components/TradesPanel.jsx';
import ExecutionsPanel from './components/ExecutionsPanel.jsx';
import OrdersPanel     from './components/OrdersPanel.jsx';
import InstrumentsPanel from './components/InstrumentsPanel.jsx';
import ClientsPanel    from './components/ClientsPanel.jsx';
import MessagesPanel   from './components/MessagesPanel.jsx';
import { ConfirmProvider } from './components/ConfirmDialog.jsx';

const TABS = ['Dashboard', 'Book', 'Trades', 'Executions', 'Orders', 'Instruments', 'Clients', 'Messages'];

/** Format a Date (or ms timestamp) as HH:MM:SS.mmm */
export function fmtMs(d) {
  const dt = d instanceof Date ? d : new Date(d);
  return dt.toLocaleTimeString('en-US', {
    hour12: false,
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    fractionalSecondDigits: 3,
  });
}

export default function App() {
  // Check URL params for initial tab/clientId (e.g. ?clientId=CLIENT)
  const initTab = () => {
    const p = new URLSearchParams(window.location.search);
    if (p.get('clientId')) return 7; // Messages tab
    return 0;
  };

  const [tab, setTab]       = useState(initTab);
  const [wsStatus, setWsStatus] = useState('disconnected');

  const [books, setBooks]             = useState({});   // symbol → { bids, asks }
  const [orderEvents, setOrderEvents] = useState([]);   // triggers refresh in child panels (trades, orders, executions)

  const wsRef = useRef(null);
  const reconnectTimer = useRef(null);

  const connectWs = useCallback(() => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url   = `${proto}//${location.host}/api/ws/events`;
    const ws    = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      setWsStatus('connected');
      clearTimeout(reconnectTimer.current);
    };

    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === 'book_update') {
          setBooks(prev => ({ ...prev, [msg.symbol]: { bids: msg.bids, asks: msg.asks } }));
        } else if (msg.type === 'order_event') {
          setOrderEvents(prev => [{ ...msg, _ts: Date.now() }, ...prev].slice(0, 500));
        }
      } catch (_) {}
    };

    ws.onclose = () => {
      setWsStatus('disconnected');
      reconnectTimer.current = setTimeout(connectWs, 3000);
    };

    ws.onerror = () => ws.close();
  }, []);

  useEffect(() => {
    connectWs();
    return () => {
      clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connectWs]);

  const navigateToMessages = (compId) => {
    const p = new URLSearchParams(window.location.search);
    if (compId) p.set('clientId', compId); else p.delete('clientId');
    window.history.replaceState({}, '', `${window.location.pathname}?${p.toString()}`);
    setTab(7);
  };

  return (
    <ConfirmProvider>
      <header className="app-header">
        <h1>FIX Simulator Admin</h1>
        <span className={`ws-badge ${wsStatus}`}>{wsStatus === 'connected' ? '● Live' : '○ Reconnecting…'}</span>
      </header>

      <nav className="tabs">
        {TABS.map((t, i) => (
          <button key={t} className={`tab${tab === i ? ' active' : ''}`} onClick={() => setTab(i)}>
            {t}
          </button>
        ))}
      </nav>

      <div className="tab-content">
        {tab === 0 && <DashboardPanel orderEvents={orderEvents} wsStatus={wsStatus} />}
        {tab === 1 && <BookPanel books={books} />}
        {tab === 2 && <TradesPanel orderEvents={orderEvents} />}
        {tab === 3 && <ExecutionsPanel orderEvents={orderEvents} />}
        {tab === 4 && <OrdersPanel orderEvents={orderEvents} />}
        {tab === 5 && <InstrumentsPanel />}
        {tab === 6 && <ClientsPanel onViewMessages={navigateToMessages} />}
        {tab === 7 && <MessagesPanel onBack={() => setTab(6)} />}
      </div>
    </ConfirmProvider>
  );
}
