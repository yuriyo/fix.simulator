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
/** Trade prices of one symbol, oldest on the left, with a price axis and a time axis. */
function PriceChart({ trades = [], symbol = '', width = 520, height = 200 }) {
  const series = trades
    .map(t => ({ p: parseFloat(t.price), time: t.time }))
    .filter(x => !isNaN(x.p))
    .reverse();                       // the endpoint returns newest first
  if (series.length < 2) {
    return <div className="empty" style={{ height }}>{symbol ? `No trades in ${symbol} yet` : 'Waiting for trades…'}</div>;
  }
  const prices = series.map(x => x.p);
  let lo = Math.min(...prices), hi = Math.max(...prices);
  const pad = (hi - lo) * 0.08 || Math.abs(hi) * 0.001 || 1;
  lo -= pad; hi += pad;
  const ml = 58, mr = 14, mt = 10, mb = 28;
  const w = width - ml - mr, h = height - mt - mb;
  const x = i => ml + (i / (series.length - 1)) * w;
  const y = p => mt + h - ((p - lo) / (hi - lo)) * h;
  const ticks = [hi, (hi + lo) / 2, lo];
  const fmtTime = iso => iso ? new Date(iso).toLocaleTimeString('en-GB', { hour12: false }) : '';
  const last = series[series.length - 1];
  const first = series[0];
  const pts = series.map((s, i) => `${x(i)},${y(s.p)}`).join(' ');

  return (
    <div>
      <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
        <span><strong style={{ color: '#e2e8f0' }}>{symbol}</strong> · last {series.length} trades</span>
        <span>last <strong style={{ color: '#e2e8f0' }}>{last.p.toFixed(2)}</strong></span>
        <span>low {Math.min(...prices).toFixed(2)} · high {Math.max(...prices).toFixed(2)}</span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} style={{ width: '100%', height: 'auto', display: 'block' }}
           role="img" aria-label={`${symbol} trade prices`}>
        {ticks.map((t, i) => (
          <g key={i}>
            <line x1={ml} x2={ml + w} y1={y(t)} y2={y(t)} stroke="#2d3148" strokeWidth="1" />
            <text x={ml - 6} y={y(t) + 4} textAnchor="end" fontSize="11" fill="#64748b">{t.toFixed(2)}</text>
          </g>
        ))}
        <text x={ml} y={height - 8} fontSize="11" fill="#64748b">{fmtTime(first.time)}</text>
        <text x={ml + w} y={height - 8} fontSize="11" fill="#64748b" textAnchor="end">{fmtTime(last.time)}</text>
        <polyline points={pts} fill="none" stroke="#a5b4fc" strokeWidth="1.5" strokeLinejoin="round" />
        {series.map((sp, i) => <circle key={i} cx={x(i)} cy={y(sp.p)} r="2" fill="#a5b4fc" />)}
        <circle cx={x(series.length - 1)} cy={y(last.p)} r="4" fill="#fbbf24" />
      </svg>
    </div>
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

  // Recent trades of the symbol shown in the price chart
  const [chartSymbol, setChartSymbol] = useState('');
  const [chartTrades, setChartTrades] = useState([]);

  // Default to the symbol with the most trades
  useEffect(() => {
    if (chartSymbol || !dash) return;
    const byTrades = Object.entries(dash.volumeBySymbol || {}).sort((a, b) => (b[1].trades || 0) - (a[1].trades || 0));
    const symbols = Object.keys(dash.instrumentStates || {}).sort();
    setChartSymbol(byTrades.length && byTrades[0][1].trades > 0 ? byTrades[0][0] : (symbols[0] || ''));
  }, [dash, chartSymbol]);

  useEffect(() => {
    if (!chartSymbol) return;
    fetch(`/api/trades?symbol=${encodeURIComponent(chartSymbol)}&limit=100`)
      .then(r => r.json())
      .then(d => setChartTrades(d.rows || []))
      .catch(() => {});
  }, [chartSymbol, refreshTs]);

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
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>Trade Prices</h2>
            <select className="filter-select" value={chartSymbol} aria-label="Instrument for the price chart"
                    onChange={e => { setChartSymbol(e.target.value); setChartTrades([]); }}>
              {Object.keys(dash.instrumentStates || {}).sort().map(sym => <option key={sym} value={sym}>{sym}</option>)}
            </select>
          </div>
          <PriceChart trades={chartTrades} symbol={chartSymbol} width={520} height={200} />
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
