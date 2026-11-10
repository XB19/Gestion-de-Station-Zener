import { TOGO_BORDER } from '../data/togo.js';

// Carte SVG sans dépendance : même projection que la carte des stations, cadrée sur les points
// affichés (une ville comme le pays entier). Les traits gardent leur épaisseur quel que soit le zoom.
const LON0 = -0.95;
const LAT0 = 11.42;
const SCALE = 100;
const KX = Math.cos((8.6 * Math.PI) / 180);
export const project = (lat, lon) => [(lon - LON0) * SCALE * KX, (LAT0 - lat) * SCALE];
const path = (pts, close) => 'M ' + pts.map(([la, lo]) => project(la, lo).map((v) => v.toFixed(2)).join(' ')).join(' L ') + (close ? ' Z' : '');

const BORDER = path(TOGO_BORDER, true);
const COAST = [
  [5.55, -0.95], [5.62, -0.4], [5.77, 0.3], [5.79, 0.63], [5.9, 0.9], [5.98, 1.06], [6.1, 1.2],
  [6.12, 1.24], [6.13, 1.28], [6.15, 1.35], [6.17, 1.45], [6.23, 1.63], [6.28, 1.8], [6.33, 2.1], [6.36, 2.45],
];
const SEA = `${path(COAST, false)} L 400 1200 L -200 1200 Z`;

/**
 * markers : [{ id, lat, lon, color, label, shape: 'dot' | 'truck' | 'flag' | 'station', heading, faded }]
 * lines : [{ points: [[lat, lon]…], color, dashed }]
 */
export default function GeoMap({ markers = [], lines = [], height = 420, selected, onSelect, minSpan = 0.06 }) {
  const all = [...markers.map((m) => [m.lat, m.lon]), ...lines.flatMap((l) => l.points)].filter(
    ([a, b]) => Number.isFinite(a) && Number.isFinite(b),
  );
  const pts = all.length ? all : [[6.17, 1.22]];
  let minLat = Math.min(...pts.map((p) => p[0]));
  let maxLat = Math.max(...pts.map((p) => p[0]));
  let minLon = Math.min(...pts.map((p) => p[1]));
  let maxLon = Math.max(...pts.map((p) => p[1]));
  const padLat = Math.max((maxLat - minLat) * 0.25, minSpan / 2);
  const padLon = Math.max((maxLon - minLon) * 0.3, minSpan / 2);
  minLat -= padLat;
  maxLat += padLat;
  minLon -= padLon;
  maxLon += padLon;
  const [x0, y0] = project(maxLat, minLon);
  const [x1, y1] = project(minLat, maxLon);
  const w = x1 - x0;
  const h = y1 - y0;
  const unit = Math.max(w, h) / 60; // taille de référence des repères

  return (
    <div style={{ position: 'relative', background: '#eef2f6', borderRadius: 10, height, overflow: 'hidden' }}>
      <svg viewBox={`${x0} ${y0} ${w} ${h}`} width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        <rect x={x0 - w} y={y0 - h} width={w * 3} height={h * 3} fill="#f8fafc" />
        <path d={SEA} fill="#dce9f6" />
        <path d={path(COAST, false)} fill="none" stroke="#b9cfe6" strokeWidth="1.2" vectorEffect="non-scaling-stroke" />
        <path d={BORDER} fill="none" stroke="#8fb0d6" strokeWidth="1.3" vectorEffect="non-scaling-stroke" strokeDasharray="4 3" />
        {lines.map((l, i) => (
          <path
            key={i}
            d={path(l.points, false)}
            fill="none"
            stroke={l.color || '#1b66b8'}
            strokeWidth={l.width || 2.4}
            strokeDasharray={l.dashed ? '6 5' : undefined}
            strokeLinecap="round"
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        ))}
        {markers.map((m) => {
          const [x, y] = project(m.lat, m.lon);
          const active = selected != null && m.id === selected;
          const r = unit * (m.shape === 'truck' ? 1.25 : 0.85) * (active ? 1.3 : 1);
          return (
            <g key={m.id} style={{ cursor: onSelect ? 'pointer' : 'default', opacity: m.faded ? 0.45 : 1 }} onClick={() => onSelect?.(m.id)}>
              {m.shape === 'flag' ? (
                <>
                  <line x1={x} y1={y} x2={x} y2={y - r * 2.4} stroke={m.color} strokeWidth="2" vectorEffect="non-scaling-stroke" />
                  <path d={`M ${x} ${y - r * 2.4} L ${x + r * 1.6} ${y - r * 1.9} L ${x} ${y - r * 1.4} Z`} fill={m.color} />
                </>
              ) : m.shape === 'truck' ? (
                <>
                  <circle cx={x} cy={y} r={r * 1.9} fill={m.color} opacity="0.16" />
                  <circle cx={x} cy={y} r={r} fill={m.color} stroke="#fff" strokeWidth="2" vectorEffect="non-scaling-stroke" />
                  {m.heading != null && (
                    <path
                      d={`M ${x} ${y - r * 0.6} L ${x + r * 0.45} ${y + r * 0.45} L ${x} ${y + r * 0.2} L ${x - r * 0.45} ${y + r * 0.45} Z`}
                      fill="#fff"
                      transform={`rotate(${m.heading} ${x} ${y})`}
                    />
                  )}
                </>
              ) : (
                <>
                  <circle cx={x} cy={y} r={r * 1.5} fill="#fff" stroke={active ? '#1b66b8' : '#dfe6ee'} strokeWidth={active ? 2 : 1} vectorEffect="non-scaling-stroke" />
                  <circle cx={x} cy={y} r={r * 0.8} fill={m.color} />
                </>
              )}
              {m.label && (
                <text x={x + r * 2} y={y + r * 0.6} fontSize={unit * 1.7} fontWeight="600" fill="#0f172a"
                  style={{ paintOrder: 'stroke', stroke: '#fff', strokeWidth: unit * 0.5 }}>
                  {m.label}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
