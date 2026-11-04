// Logo ZENER : anneau bleu / vert + mot-symbole
function arc(cx, cy, r, a0, a1) {
  const rad = (a) => ((a - 90) * Math.PI) / 180;
  const x0 = cx + r * Math.cos(rad(a0));
  const y0 = cy + r * Math.sin(rad(a0));
  const x1 = cx + r * Math.cos(rad(a1));
  const y1 = cy + r * Math.sin(rad(a1));
  const large = a1 - a0 > 180 ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

export function ZenerMark({ size = 34 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 40 40" aria-hidden="true">
      <defs>
        <linearGradient id="zg-blue" x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#1b4fa0" />
          <stop offset="1" stopColor="#1f8fd0" />
        </linearGradient>
        <linearGradient id="zg-green" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#2fae4a" />
          <stop offset="1" stopColor="#a6cf2a" />
        </linearGradient>
      </defs>
      <path d={arc(20, 20, 14, 150, 390)} stroke="url(#zg-blue)" strokeWidth="7.5" fill="none" strokeLinecap="round" />
      <path d={arc(20, 20, 14, -30, 140)} stroke="url(#zg-green)" strokeWidth="7.5" fill="none" strokeLinecap="round" />
    </svg>
  );
}

export function ZenerWordmark({ size = 22, color = '#1b4fa0' }) {
  return (
    <span
      style={{
        fontWeight: 900,
        fontSize: size,
        color,
        letterSpacing: '-0.02em',
        lineHeight: 1,
        position: 'relative',
      }}
    >
      ZENER
      <sup style={{ fontSize: size * 0.32, fontStyle: 'normal', fontWeight: 600, marginLeft: 1 }}>®</sup>
    </span>
  );
}

export default function ZenerLogo({ size = 34 }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <ZenerMark size={size} />
      <ZenerWordmark size={size * 0.62} />
    </span>
  );
}
