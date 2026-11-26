import { useState, useEffect, useCallback } from 'react';

/* ── Tiny SVG chart helpers (no external deps) ─────────────────────── */

/** Arc-gauge: 0-1 fraction rendered as a 180° arc using stroke-dasharray */
function Gauge({ value = 0, max = 1, label, color = '#a5b4fc', size = 120 }) {
  const pct   = max > 0 ? Math.min(value / max, 1) : 0;
  const r     = (size - 16) / 2;
  const cx    = size / 2;
  const cy    = size / 2 + 8;
  // Semicircle path: left → top → right
  const arcPath       = `M ${cx - r} ${cy} A ${r} ${r} 0 1 1 ${cx + r} ${cy}`;
  const halfCircum    = Math.PI * r;
  const filledLen     = halfCircum * pct;

  return (
    <div className="gauge-wrap">
      <svg width={size} height={size / 2 + 24} viewBox={`0 0 ${size} ${size / 2 + 24}`}>
        {/* Background arc (track) */}
        <path d={arcPath}
          fill="none" stroke="#2d3148" strokeWidth="10" strokeLinecap="round" />
        {/* Value arc — dasharray clips the same path to the filled portion */}
        {pct > 0 && (
          <path d={arcPath}
            fill="none" stroke={color} strokeWidth="10" strokeLinecap="round"
            strokeDasharray={`${filledLen} ${halfCircum}`} />
        )}
        {/* Numeric value */}
        <text x={cx} y={cy - 8} textAnchor="middle" fill="#e2e8f0"
              fontSize="22" fontWeight="700">{value}</text>
        <text x={cx} y={cy + 10} textAnchor="middle" fill="#64748b"
              fontSize="10">{label}</text>
      </svg>
    </div>
  );
}

/** Donut chart with centered label */
function DonutChart({ data = [], size = 160, label }) {
  const total = data.reduce((s, d) => s + d.value, 0);
  if (total === 0) {
    return (
      <div className="donut-wrap">
        <svg width={size} height={size}>
          <circle cx={size/2} cy={size/2} r={size/2 - 16} fill="none" stroke="#2d3148" strokeWidth="20"/>
          <text x={size/2} y={size/2 - 4} textAnchor="middle" fill="#64748b" fontSize="12">No data</text>
        </svg>
        {label && <div className="chart-label">{label}</div>}
      </div>
    );
  }
  const cx = size / 2, cy = size / 2, r = size / 2 - 16;
  let cumAngle = -Math.PI / 2;

  const arcs = data.filter(d => d.value > 0).map((d, i) => {
    const frac = d.value / total;
    const startAngle = cumAngle;
    cumAngle += 2 * Math.PI * frac;
    const endAngle = cumAngle;
    const large = frac > 0.5 ? 1 : 0;
    const x1 = cx + r * Math.cos(startAngle);
    const y1 = cy + r * Math.sin(startAngle);
    const x2 = cx + r * Math.cos(endAngle);
    const y2 = cy + r * Math.sin(endAngle);
    return (
      <path key={i}
        d={`M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`}
        fill="none" stroke={d.color} strokeWidth="20" />
    );
  });

  return (
    <div className="donut-wrap">
      <svg width={size} height={size}>
        {arcs}
        <text x={cx} y={cy - 4} textAnchor="middle" fill="#e2e8f0"
              fontSize="20" fontWeight="700">{total}</text>
        <text x={cx} y={cy + 12} textAnchor="middle" fill="#64748b"
              fontSize="10">TOTAL</text>
      </svg>
      {/* Legend */}
      <div className="donut-legend">
        {data.filter(d => d.value > 0).map((d, i) => (
          <span key={i} className="legend-item">
            <span className="legend-dot" style={{ background: d.color }} />
            {d.label} {d.value}
          </span>
        ))}
      </div>
      {label && <div className="chart-label">{label}</div>}
    </div>
  );
}

/** Horizontal bar chart */
function BarChart({ data = [], maxVal, label }) {
  const mx = maxVal || Math.max(...data.map(d => d.value), 1);
  return (
    <div className="bar-chart-wrap">
      {label && <div className="chart-label">{label}</div>}
      {data.map((d, i) => (
        <div key={i} className="bar-row">
          <span className="bar-label">{d.label}</span>
          <div className="bar-track">
            <div className="bar-fill" style={{
              width: `${Math.max((d.value / mx) * 100, 1)}%`,
              background: d.color || '#a5b4fc'
            }} />
          </div>
          <span className="bar-val">{d.value.toLocaleString()}</span>
        </div>
      ))}
      {data.length === 0 && <div className="empty">No data</div>}
    </div>
  );
}

/** Mini sparkline (last N trade prices) */
function Sparkline({ points = [], width = 260, height = 48, color = '#a5b4fc' }) {
  if (points.length < 2) return <div className="empty" style={{height}}>Waiting for trades…</div>;
  const minY = Math.min(...points);
  const maxY = Math.max(...points);
  const rangeY = maxY - minY || 1;
  const pad = 4;
  const w = width - pad * 2;
  const h = height - pad * 2;
  const pts = points.map((p, i) => {
    const x = pad + (i / (points.length - 1)) * w;
    const y = pad + h - ((p - minY) / rangeY) * h;
    return `${x},${y}`;
  }).join(' ');

  return (
    <svg width={width} height={height} className="sparkline-svg">
      <polyline points={pts} fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" />
      {/* End dot */}
      {(() => {
        const lastX = pad + w;
        const lastY = pad + h - ((points[points.length-1] - minY) / rangeY) * h;
        return <circle cx={lastX} cy={lastY} r="3" fill={color} />;
      })()}
    </svg>
  );
}

/* ── Status indicator ───────────────────────────────────────────────── */
function InstrumentStatusGrid({ states = {} }) {
  const entries = Object.entries(states);
  if (entries.length === 0) return <div className="empty">No instruments</div>;
  return (
    <div className="inst-status-grid">
      {entries.map(([sym, st]) => (
        <div key={sym} className={`inst-chip ${st.toLowerCase()}`}>
          <span className="inst-chip-dot" />
          <span className="inst-chip-sym">{sym}</span>
          <span className="inst-chip-st">{st}</span>
        </div>
      ))}
    </div>
  );
}

/* ── Main panel ─────────────────────────────────────────────────────── */
const STATUS_COLORS = {
  NEW:              '#93c5fd',
  PARTIALLY_FILLED: '#fde68a',
  FILLED:           '#86efac',
  CANCELLED:        '#94a3b8',
  REJECTED:         '#fca5a5',
  PENDING_CANCEL:   '#c4b5fd',
  PENDING_REPLACE:  '#c4b5fd',
};

const SYMBOL_COLORS = [
  '#a5b4fc', '#86efac', '#fde68a', '#fca5a5', '#c4b5fd',
  '#67e8f9', '#f9a8d4', '#fdba74',
];

export default function DashboardPanel({ orderEvents, wsStatus }) {
  const [dash, setDash] = useState(null);
  const [uptime, setUptime] = useState('');
  const [refreshTs, setRefreshTs] = useState(null);

  const fetchDashboard = useCallback(() => {
    fetch('/api/dashboard')
      .then(r => r.json())
      .then(d => { setDash(d); setRefreshTs(new Date()); })
      .catch(() => {});
  }, []);

  // Initial fetch + periodic refresh
  useEffect(() => {
    fetchDashboard();
    const iv = setInterval(fetchDashboard, 5000);
    return () => clearInterval(iv);
  }, [fetchDashboard]);

  // Uptime clock
  useEffect(() => {
    const start = Date.now();
    const tick = () => {
      const s = Math.floor((Date.now() - start) / 1000);
      const h = Math.floor(s / 3600);
      const m = Math.floor((s % 3600) / 60);
      const sec = s % 60;
      setUptime(`${h}h ${String(m).padStart(2,'0')}m ${String(sec).padStart(2,'0')}s`);
    };
    tick();
    const iv = setInterval(tick, 1000);
    return () => clearInterval(iv);
  }, []);

  if (!dash) return <div className="empty">Loading dashboard…</div>;

  // Prepare donut data
  const statusData = Object.entries(dash.ordersByStatus || {})
    .map(([st, val]) => ({ label: st.replace('_', ' '), value: Number(val), color: STATUS_COLORS[st] || '#475569' }));

  // Volume by symbol bar data
  const volEntries = Object.entries(dash.volumeBySymbol || {});
  const barData = volEntries.map(([sym, v], i) => ({
    label: sym,
    value: v.volume || 0,
    color: SYMBOL_COLORS[i % SYMBOL_COLORS.length],
  }));

  const tradeCountData = volEntries.map(([sym, v], i) => ({
    label: sym,
    value: v.trades || 0,
    color: SYMBOL_COLORS[i % SYMBOL_COLORS.length],
  }));

  // Sparkline from recent trades (price values)
  const sparkPrices = (dash.recentTrades || []).map(t => parseFloat(t.price)).filter(p => !isNaN(p));

  const symbolCount = Object.keys(dash.instrumentStates || {}).length;

  return (
    <div className="dashboard">
      {/* ── KPI row ──────────────────────────── */}
      <div className="dash-kpi-row">
        <div className="kpi-card">
          <div className="kpi-value">{dash.totalOrders}</div>
          <div className="kpi-label">Total Orders</div>
        </div>
        <div className="kpi-card accent-blue">
          <div className="kpi-value">{dash.openOrders}</div>
          <div className="kpi-label">Open Orders</div>
        </div>
        <div className="kpi-card accent-green">
          <div className="kpi-value">{dash.tradeCount}</div>
          <div className="kpi-label">Trades</div>
        </div>
        <div className="kpi-card accent-purple">
          <div className="kpi-value">{dash.execCount}</div>
          <div className="kpi-label">Executions</div>
        </div>
        <div className="kpi-card accent-yellow">
          <div className="kpi-value">{dash.messagesLogged}</div>
          <div className="kpi-label">FIX Messages</div>
        </div>
      </div>

      {/* ── Gauges row ──────────────────────── */}
      <div className="dash-gauges-row">
        <div className="card">
          <h2>Active Sessions</h2>
          <Gauge value={dash.activeSessions} max={Math.max(dash.totalSessions, 1)}
                 label={`of ${dash.totalSessions}`} color="#86efac" />
        </div>
        <div className="card">
          <h2>Open Orders</h2>
          <Gauge value={dash.openOrders} max={Math.max(dash.totalOrders, 1)}
                 label={`of ${dash.totalOrders}`} color="#93c5fd" />
        </div>
        <div className="card">
          <h2>Symbols Active</h2>
          <Gauge value={volEntries.filter(([,v]) => v.trades > 0).length}
                 max={symbolCount} label={`of ${symbolCount}`} color="#fde68a" />
        </div>
        <div className="card">
          <h2>WebSocket</h2>
          <div className="ws-gauge-center">
            <div className={`ws-indicator ${wsStatus}`}>
              <span className="ws-pulse" />
              {wsStatus === 'connected' ? 'LIVE' : 'DOWN'}
            </div>
            <div className="ws-uptime">{uptime}</div>
          </div>
        </div>
      </div>

      {/* ── Charts row ──────────────────────── */}
      <div className="dash-charts-row">
        <div className="card">
          <h2>Order Status</h2>
          <DonutChart data={statusData} size={170} />
        </div>
        <div className="card">
          <h2>Volume by Symbol</h2>
          <BarChart data={barData} />
        </div>
        <div className="card">
          <h2>Trades by Symbol</h2>
          <BarChart data={tradeCountData} />
        </div>
      </div>

      {/* ── Sparkline + Instruments ──────────── */}
      <div className="dash-bottom-row">
        <div className="card" style={{flex: 2}}>
          <h2>Recent Trade Prices</h2>
          <Sparkline points={sparkPrices} width={520} height={80} />
        </div>
        <div className="card" style={{flex: 1}}>
          <h2>Instrument Status</h2>
          <InstrumentStatusGrid states={dash.instrumentStates || {}} />
        </div>
      </div>

      {/* ── Footer ──────────────────────────── */}
      <div className="dash-footer">
        {refreshTs && <span>Last refresh: {refreshTs.toLocaleTimeString()}</span>}
        <button className="btn-subtle" onClick={fetchDashboard}>↻ Refresh</button>
      </div>
    </div>
  );
}
