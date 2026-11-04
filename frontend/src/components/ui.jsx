import { useState } from 'react';
import { Check, ChevronDown, TrendingUp, TrendingDown } from 'lucide-react';
import { ZenerMark } from './ZenerLogo.jsx';

/* ---------- Mise en page de page ---------- */
export function PageHeader({ title, subtitle, actions, breadcrumb, tags }) {
  return (
    <div className="page-head">
      <div>
        {breadcrumb}
        <h1 className="page-title">{title}</h1>
        {subtitle && <p className="page-sub">{subtitle}</p>}
        {tags && <div className="tags">{tags}</div>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Card({ children, className = '', style, ...rest }) {
  return (
    <div className={`card ${className}`} style={style} {...rest}>
      {children}
    </div>
  );
}

export function CardHead({ title, sub, right, style }) {
  return (
    <div className="card-head" style={style}>
      <div>
        <div className="card-title">{title}</div>
        {sub && <div className="card-sub">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

/* ---------- KPI ---------- */
export function Kpi({ icon: Icon, tone = 'blue', label, value, unit, foot }) {
  return (
    <Card className="kpi">
      <div className="kpi-top">
        <span className={`kpi-icon tone-${tone}`}>
          <Icon size={18} strokeWidth={1.8} />
        </span>
        {label}
      </div>
      <div className="kpi-value num">
        {value}
        {unit && <small>{unit}</small>}
      </div>
      <div className="kpi-foot">{foot}</div>
    </Card>
  );
}

export function Trend({ value, down = false, good }) {
  const Icon = down ? TrendingDown : TrendingUp;
  return (
    <span className={(good ?? !down) ? 'trend-up' : 'trend-down'}>
      <Icon size={14} strokeWidth={2.2} /> {value}
    </span>
  );
}

/* ---------- Badges ---------- */
export function Badge({ children, tone = 'gray', dot = false, className = '', style }) {
  return (
    <span className={`badge ${tone} ${dot ? 'dot' : ''} ${className}`} style={style}>
      {children}
    </span>
  );
}

// Statuts récurrents
const STATUS = {
  'En ligne': ['green', true],
  'Hors ligne': ['red', true],
  Instable: ['orange', true],
  Active: ['green', true],
  Bloquée: ['red', true],
  Inactive: ['mutedgray', true],
  Prête: ['green', true],
  'Validation requise': ['orange', true],
  'Carte introuvable': ['red', true],
  Approuvée: ['green', true],
  Approuvé: ['green', true],
  Refusée: ['red', true],
  'En attente': ['orange', true],
  Critique: ['red', true],
  Bas: ['orange', true],
  Normal: ['green', true],
  Ouvert: ['green', true],
  'À valider': ['mutedgray', true],
  'En poste': ['green', true],
  Pause: ['orange', true],
  'Expire bientôt': ['orange', true],
  Résiliée: ['red', true],
};
export function StatusBadge({ status, children }) {
  const [tone, dot] = STATUS[status] || ['gray', false];
  return (
    <Badge tone={tone} dot={dot}>
      {children || status}
    </Badge>
  );
}

/* ---------- Contrôles ---------- */
export function Button({ variant, size, icon: Icon, iconRight: IconRight, children, className = '', block, ...rest }) {
  const cls = ['btn', variant && `btn-${variant}`, size && `btn-${size}`, block && 'btn-block', className]
    .filter(Boolean)
    .join(' ');
  return (
    <button className={cls} {...rest}>
      {Icon && <Icon size={size === 'sm' ? 15 : 17} strokeWidth={1.9} />}
      {children}
      {IconRight && <IconRight size={16} strokeWidth={1.9} />}
    </button>
  );
}

export function Select({ icon: Icon, children, style }) {
  return (
    <button className="select" style={style}>
      {Icon && <Icon size={16} strokeWidth={1.8} />}
      {children}
      <ChevronDown size={15} className="chev" />
    </button>
  );
}

export function SearchInput({ placeholder, icon: Icon, style, value, onChange }) {
  return (
    <label className="input" style={style}>
      <Icon size={17} strokeWidth={1.8} />
      <input placeholder={placeholder} value={value} onChange={onChange} />
    </label>
  );
}

export function Checkbox({ checked, onChange }) {
  return (
    <button type="button" className={`checkbox ${checked ? 'on' : ''}`} onClick={() => onChange?.(!checked)}>
      {checked && <Check size={13} strokeWidth={3} />}
    </button>
  );
}

export function Toggle({ defaultOn = true }) {
  const [on, setOn] = useState(defaultOn);
  return <button type="button" className={`toggle ${on ? 'on' : ''}`} onClick={() => setOn(!on)} />;
}

export function Segmented({ items, value, onChange }) {
  return (
    <div className="segmented">
      {items.map((it) => {
        const key = typeof it === 'string' ? it : it.label;
        const Icon = it.icon;
        return (
          <button key={key} className={value === key ? 'active' : ''} onClick={() => onChange(key)}>
            {Icon && <Icon size={16} strokeWidth={1.8} />}
            {key}
            {it.count != null && <span className="count">{it.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

/* ---------- Divers ---------- */
const AVATAR_COLORS = {
  blue: '#1b66b8',
  navy: '#283c8f',
  green: '#5aa935',
  teal: '#13917d',
  orange: '#c2410c',
  purple: '#8b5cf6',
  pink: '#c2185b',
  forest: '#2e7d32',
  indigo: '#4f5bb5',
};
export function Avatar({ initials, color = 'blue', size = 36 }) {
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, fontSize: size * 0.36, background: AVATAR_COLORS[color] || color }}
    >
      {initials}
    </span>
  );
}

export function Progress({ value, color = 'var(--green-500)', style }) {
  return (
    <div className="progress" style={style}>
      <span style={{ width: `${value}%`, background: color }} />
    </div>
  );
}

export function KV({ k, v }) {
  return (
    <div className="kv">
      <span className="k">{k}</span>
      <span className="v">{v}</span>
    </div>
  );
}

export function IcoBox({ icon: Icon, tone = 'blue', size = 36 }) {
  return (
    <span className={`ico-box tone-${tone}`} style={{ width: size, height: size }}>
      <Icon size={size * 0.47} strokeWidth={1.8} />
    </span>
  );
}

/* ---------- Carte bancaire ZENER ---------- */
export function BankCard({ number, holder, exp, kind = 'FLOTTE', large = false }) {
  return (
    <div className="bank-card" style={{ height: large ? 225 : 175, padding: large ? '24px 26px' : '18px 20px' }}>
      <div className="arc" style={large ? { width: 340, height: 340, right: -110, bottom: -190 } : { width: 260, height: 260, right: -80, bottom: -150, borderWidth: 20 }} />
      <div className="row between" style={{ position: 'relative' }}>
        <span className="row" style={{ gap: 8 }}>
          <span style={{ background: '#fff', borderRadius: 9, padding: 3, display: 'inline-flex' }}>
            <ZenerMark size={large ? 34 : 28} />
          </span>
          <span style={{ fontWeight: 900, fontStyle: 'italic', fontSize: large ? 20 : 17 }}>ZENER</span>
        </span>
        <span className="small" style={{ fontWeight: 600 }}>{kind}</span>
      </div>
      <div className="chip" style={{ marginTop: large ? 18 : 10, position: 'relative', ...(large ? {} : { width: 32, height: 24 }) }} />
      <div className="number num" style={{ marginTop: large ? 30 : 18, position: 'relative', fontSize: large ? 21 : 16 }}>
        {number}
      </div>
      <div className="row between small" style={{ marginTop: large ? 16 : 10, position: 'relative', fontSize: large ? 11.5 : 10.5 }}>
        <span>{holder}</span>
        <span>EXP. {exp}</span>
      </div>
    </div>
  );
}
