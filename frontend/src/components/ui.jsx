import { useState } from 'react';
import { Check, ChevronDown, TrendingUp, TrendingDown } from 'lucide-react';
import { ZenerMark } from './ZenerLogo.jsx';
import { avatarColor, initials as initialsOf } from '../lib/format.js';

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
  Expirée: ['red', true],
  Aucune: ['mutedgray', false],
  'À activer': ['orange', true],
  'À commenter': ['orange', false],
  Brouillon: ['blue', false],
  Commenté: ['green', false],
  Incomplet: ['red', false],
  'Attente Comptable': ['orange', true],
  'Attente DAF': ['orange', true],
  'Attente Admin / DAF': ['orange', true],
  Créditée: ['green', true],
  'En validation': ['orange', true],
  Clos: ['mutedgray', true],
  'Clos · reste à remettre': ['orange', true],
  'Clos · excédent': ['blue', true],
  'En pause': ['orange', true],
  Désactivé: ['mutedgray', true],
  Demandée: ['orange', true],
  Validée: ['blue', true],
  'En route': ['green', true],
  Livrée: ['mutedgray', true],
  Annulée: ['red', true],
  'À clôturer': ['orange', true],
  Refusé: ['red', true],
  'Montant invalide': ['red', true],
  'Carte inactive': ['red', true],
  Doublon: ['red', true],
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

/**
 * Liste de choix. Avec `options` ([{ value, label }] ou chaînes) : vraie liste déroulante ;
 * sans options : simple bouton d'apparence identique.
 */
export function Select({ icon: Icon, children, style, options, value, onChange, placeholder }) {
  if (!options) {
    return (
      <button className="select" style={style} type="button">
        {Icon && <Icon size={16} strokeWidth={1.8} />}
        {children}
        <ChevronDown size={15} className="chev" />
      </button>
    );
  }
  return (
    <label className="select" style={style}>
      {Icon && <Icon size={16} strokeWidth={1.8} />}
      <select value={value ?? ''} onChange={(e) => onChange?.(e.target.value)}>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => {
          const opt = typeof o === 'string' ? { value: o, label: o } : o;
          return <option key={opt.value} value={opt.value}>{opt.label}</option>;
        })}
      </select>
      <ChevronDown size={15} className="chev" />
    </label>
  );
}

export function SearchInput({ placeholder, icon: Icon, style, value, onChange }) {
  return (
    <label className="input" style={style}>
      <Icon size={17} strokeWidth={1.8} />
      <input placeholder={placeholder} value={value ?? ''} onChange={onChange} />
    </label>
  );
}

/* ---------- États de chargement ---------- */
export function Loading({ label = 'Chargement…', style }) {
  return (
    <div className="state-box" style={style}>
      <span className="spinner" /> <span style={{ marginLeft: 8 }}>{label}</span>
    </div>
  );
}

export function ErrorState({ error, onRetry, style }) {
  return (
    <div className="state-box error" style={style}>
      {error?.message || 'Erreur de chargement'}
      {onRetry && (
        <div style={{ marginTop: 12 }}>
          <button className="btn btn-sm" onClick={onRetry}>Réessayer</button>
        </div>
      )}
    </div>
  );
}

export function Empty({ children = 'Aucun élément', style }) {
  return <div className="state-box" style={style}>{children}</div>;
}

/** Affiche le chargement / l'erreur d'un useApi, sinon le contenu. */
export function Async({ state, children, minHeight = 120 }) {
  if (state.error && !state.data) return <ErrorState error={state.error} onRetry={state.reload} style={{ minHeight }} />;
  if (!state.data) return <Loading style={{ minHeight }} />;
  return children(state.data);
}

export function Checkbox({ checked, onChange }) {
  return (
    <button type="button" className={`checkbox ${checked ? 'on' : ''}`} onClick={() => onChange?.(!checked)}>
      {checked && <Check size={13} strokeWidth={3} />}
    </button>
  );
}

/** Interrupteur : contrôlé (on / onChange) ou autonome (defaultOn). */
export function Toggle({ defaultOn = true, on, onChange, disabled }) {
  const [local, setLocal] = useState(defaultOn);
  const value = on ?? local;
  return (
    <button
      type="button"
      disabled={disabled}
      className={`toggle ${value ? 'on' : ''}`}
      onClick={() => (onChange ? onChange(!value) : setLocal(!value))}
    />
  );
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
export function Avatar({ initials, color = 'blue', size = 36, photo }) {
  if (photo) {
    return (
      <img className="avatar" src={photo} alt={initials} style={{ width: size, height: size, objectFit: 'cover' }} />
    );
  }
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, fontSize: size * 0.36, background: AVATAR_COLORS[color] || color }}
    >
      {initials}
    </span>
  );
}

/** Personne : photo de profil (ou initiales) suivie du nom, pour les tableaux et listes. */
export function Person({ name, photo, id, size = 26, sub, strong }) {
  if (!name) return <span className="muted">—</span>;
  return (
    <span className="row" style={{ gap: 8, minWidth: 0 }}>
      <Avatar initials={initialsOf(name)} color={avatarColor(id ?? name)} size={size} photo={photo} />
      <span style={{ minWidth: 0 }}>
        <span className={strong ? 'strong' : undefined} style={{ display: 'block', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</span>
        {sub && <span className="muted" style={{ display: 'block', fontSize: 12 }}>{sub}</span>}
      </span>
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

/* ---------- Pagination (réponses { items, total, page, size } de l'API) ---------- */
export function Pager({ data, page, onPage, label = 'éléments' }) {
  if (!data || data.total <= data.size) {
    return data ? <div className="pager"><span>{data.total} {label}</span></div> : null;
  }
  const pages = Math.ceil(data.total / data.size);
  const from = page * data.size + 1;
  const to = Math.min(data.total, from + data.items.length - 1);
  return (
    <div className="pager">
      <span>Affichage de {from} à {to} sur {data.total.toLocaleString('fr-FR')} {label}</span>
      <div className="row" style={{ gap: 8 }}>
        <button className="btn btn-sm" disabled={page === 0} onClick={() => onPage(page - 1)}>Précédent</button>
        <span className="num">{page + 1} / {pages}</span>
        <button className="btn btn-sm" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>Suivant</button>
      </div>
    </div>
  );
}
