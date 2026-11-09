// Graphiques SVG légers (aucune dépendance)

function smoothPath(pts) {
  if (pts.length < 2) return '';
  let d = `M ${pts[0][0]} ${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] || p2;
    const t = 0.18;
    const c1 = [p1[0] + (p2[0] - p0[0]) * t, p1[1] + (p2[1] - p0[1]) * t];
    const c2 = [p2[0] - (p3[0] - p1[0]) * t, p2[1] - (p3[1] - p1[1]) * t];
    d += ` C ${c1[0]} ${c1[1]}, ${c2[0]} ${c2[1]}, ${p2[0]} ${p2[1]}`;
  }
  return d;
}

/* Courbe de recette cumulée : aujourd'hui (plein) vs hier (pointillés) */
export function AreaChart({ today, yesterday, max = 20, height = 300, xLabels, yLabels, points }) {
  const W = 680;
  const H = height;
  const padL = 44;
  const padR = 14;
  const padT = 10;
  const padB = 28;
  const iw = W - padL - padR;
  const ih = H - padT - padB;
  // points : nombre total de positions sur l'axe (la courbe du jour peut s'arrêter avant la fin)
  const count = Math.max(points || 0, today.length, yesterday.length, 2);
  const toPts = (arr) =>
    arr.map((v, i) => [padL + (i / (count - 1)) * iw, padT + ih - (Math.min(v, max) / max) * ih]);
  const pT = toPts(today);
  const pY = toPts(yesterday);
  if (!pT.length) pT.push([padL, padT + ih]);
  const line = smoothPath(pT.length > 1 ? pT : [...pT, pT[0]]);
  const last = pT[pT.length - 1];
  const area = `${line} L ${last[0]} ${padT + ih} L ${pT[0][0]} ${padT + ih} Z`;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} preserveAspectRatio="none" style={{ display: 'block' }}>
      <defs>
        <linearGradient id="area-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1b66b8" stopOpacity="0.2" />
          <stop offset="1" stopColor="#1b66b8" stopOpacity="0.02" />
        </linearGradient>
      </defs>
      {yLabels.map((l, i) => {
        const y = padT + ih - (i / (yLabels.length - 1)) * ih;
        return (
          <g key={l}>
            <line x1={padL} x2={W - padR} y1={y} y2={y} stroke="#edf1f5" />
            <text x={padL - 10} y={y + 4} textAnchor="end" fontSize="11" fill="#94a3b8">
              {l}
            </text>
          </g>
        );
      })}
      {xLabels.map((l, i) => (
        <text
          key={`${l}-${i}`}
          x={padL + (i / (xLabels.length - 1)) * iw}
          y={H - 6}
          textAnchor="middle"
          fontSize="11"
          fill="#94a3b8"
        >
          {l}
        </text>
      ))}
      <path d={area} fill="url(#area-fill)" />
      <path d={smoothPath(pY)} fill="none" stroke="#8db4e2" strokeWidth="2" strokeDasharray="5 4" />
      <path d={line} fill="none" stroke="#1b5aa5" strokeWidth="2.4" />
      <line x1={last[0]} x2={last[0]} y1={padT} y2={padT + ih} stroke="#1b66b8" strokeDasharray="3 3" />
      <circle cx={last[0]} cy={last[1]} r="5" fill="#fff" stroke="#1b66b8" strokeWidth="2.5" />
    </svg>
  );
}

/* Barres groupées (Super / Gasoil) */
export function GroupedBars({ data, labels, colors = ['#1b66b8', '#8cc63f'], height = 200 }) {
  const max = Math.max(1, ...data.flat()) * 1.05;
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', height, gap: 0 }}>
      {data.map((group, i) => (
        <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%' }}>
          <div style={{ flex: 1, display: 'flex', alignItems: 'flex-end', gap: 4 }}>
            {group.map((v, j) => (
              <div
                key={j}
                style={{
                  width: 13,
                  height: `${(v / max) * 100}%`,
                  background: colors[j],
                  borderRadius: '4px 4px 2px 2px',
                }}
              />
            ))}
          </div>
          <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 10 }}>{labels[i]}</div>
        </div>
      ))}
    </div>
  );
}

/* Anneau à segments */
export function Donut({ segments, size = 140, thickness = 18, gap = 1.2, children }) {
  const total = segments.reduce((s, x) => s + x.value, 0) || 1;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  let offset = 0;
  return (
    <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        {segments.map((s, i) => {
          const len = (s.value / total) * c;
          const el = (
            <circle
              key={i}
              cx={size / 2}
              cy={size / 2}
              r={r}
              fill="none"
              stroke={s.color}
              strokeWidth={thickness}
              strokeDasharray={`${Math.max(len - gap, 0.5)} ${c}`}
              strokeDashoffset={-offset}
            />
          );
          offset += len;
          return el;
        })}
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', textAlign: 'center' }}>
        {children}
      </div>
    </div>
  );
}

/* Jauge circulaire (jours restants) */
export function Ring({ value, max, size = 110, color = '#f29e1f', children }) {
  const thickness = 8;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  return (
    <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#eef2f6" strokeWidth={thickness} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={thickness}
          strokeLinecap="round"
          strokeDasharray={`${Math.max(0, Math.min(1, value / max)) * c} ${c}`}
        />
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', textAlign: 'center' }}>
        {children}
      </div>
    </div>
  );
}

/** Échelle « ronde » pour un axe : 0 → max arrondi, avec n graduations. */
export function niceScale(maxValue, steps = 4) {
  const raw = Math.max(maxValue, 1) / steps;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const unit = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((m) => m >= raw);
  return { max: unit * steps, ticks: Array.from({ length: steps + 1 }, (_, i) => unit * i) };
}

/** Litres par heure (24 valeurs par carburant) → barres groupées de 6 h à 22 h, par tranches de 2 h. */
export function hourlyBars(byHour, fuels = ['SUPER', 'GASOIL'], from = 6, to = 22, step = 2) {
  const data = [];
  const labels = [];
  for (let h = from; h <= to; h += step) {
    labels.push(`${h}h`);
    data.push(fuels.map((f) => {
      const arr = byHour?.[f] || [];
      let sum = 0;
      for (let k = h; k < Math.min(h + step, 24); k++) sum += Number(arr[k] || 0);
      return sum;
    }));
  }
  return { data, labels };
}
