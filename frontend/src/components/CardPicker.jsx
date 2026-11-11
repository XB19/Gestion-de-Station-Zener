import { useEffect, useRef, useState } from 'react';
import { CreditCard, Search, X, Car } from 'lucide-react';
import { Badge } from './ui.jsx';
import { api, qs } from '../api/client.js';
import { fcfa } from '../lib/format.js';

const STATUS_TONES = { ACTIVE: 'green', A_ACTIVER: 'orange', BLOQUEE: 'red', INACTIVE: 'gray', STOCK: 'blue', PDV: 'blue' };

const vehicle = (c) => [c.vehiclePlate, c.vehicleLabel].filter(Boolean).join(' · ');

/** Ligne d'une carte : numéro, état, titulaire, groupe, véhicule, solde. */
function CardLine({ c }) {
  return (
    <span style={{ minWidth: 0, display: 'block' }}>
      <span className="row" style={{ gap: 8 }}>
        <span className="strong num" style={{ letterSpacing: '.03em' }}>{c.numberFormatted}</span>
        <Badge tone={STATUS_TONES[c.status] || 'gray'}>{c.statusLabel}</Badge>
        {c.typeLabel && <span className="muted" style={{ fontSize: 12 }}>{c.typeLabel}</span>}
      </span>
      <span className="muted" style={{ display: 'block', fontSize: 12.5, marginTop: 2 }}>
        {[c.holderName || 'Sans titulaire', c.groupName].filter(Boolean).join(' · ')}
        {vehicle(c) && <> · <Car size={12} style={{ verticalAlign: '-2px' }} /> {vehicle(c)}</>}
        {c.balance != null && <> · solde {fcfa(c.balance)} F</>}
      </span>
    </span>
  );
}

/**
 * Choix d'une carte, partagé par tous les écrans : numéro complet, 5 derniers chiffres ou fragment.
 * Une seule carte correspond : elle est choisie ; plusieurs : liste de choix (jamais de choix automatique).
 * Un numéro complet de 12 chiffres (lecteur, copier-coller) lance la recherche tout seul.
 *
 * value : carte choisie (objet de /cards/search ou /cards/lookup) ; onChange(carte | null).
 * compact : affichage en étiquette (barres de filtres) ; height : hauteur du champ.
 */
export default function CardPicker({ value, onChange, placeholder = 'N° de carte ou 5 derniers chiffres', compact, height = 40,
  autoFocus, suffix, width, disabled }) {
  const [q, setQ] = useState('');
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [active, setActive] = useState(0);
  const [busy, setBusy] = useState(false);
  const box = useRef(null);
  const seq = useRef(0);

  const run = async (text = q) => {
    const digits = text.replace(/\D/g, '');
    if (!digits) return;
    const n = ++seq.current;
    setBusy(true);
    setError(null);
    try {
      const r = await api(`/cards/search${qs({ q: digits })}`);
      if (n !== seq.current) return;
      if (r.length === 1) {
        onChange(r[0]);
        setQ('');
        setItems(null);
      } else if (r.length === 0) {
        setItems(null);
        setError(digits.length === 5 ? 'Aucune carte ne se termine par ces 5 chiffres' : 'Aucune carte ne correspond');
      } else {
        setItems(r);
        setActive(0);
      }
    } catch (e) {
      if (n === seq.current) { setItems(null); setError(e.message); }
    } finally {
      if (n === seq.current) setBusy(false);
    }
  };

  // lecteur de cartes ou copier-coller d'un numéro complet
  useEffect(() => {
    if (q.replace(/\D/g, '').length === 12) run(q);
  }, [q]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const close = (e) => box.current && !box.current.contains(e.target) && setItems(null);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const pick = (c) => {
    onChange(c);
    setQ('');
    setItems(null);
  };

  const onKey = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault(); // ne soumet pas le formulaire englobant
      if (items?.length) pick(items[active]);
      else run();
    } else if (e.key === 'ArrowDown' && items?.length) {
      e.preventDefault();
      setActive((a) => Math.min(items.length - 1, a + 1));
    } else if (e.key === 'ArrowUp' && items?.length) {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === 'Escape') {
      setItems(null);
    }
  };

  if (value) {
    if (compact) {
      return (
        <span className="select" style={{ background: 'var(--primary-50)', borderColor: '#bcd3ee', color: 'var(--primary)', maxWidth: '100%' }}
          title={[value.holderName, value.groupName, vehicle(value)].filter(Boolean).join(' · ')}>
          <CreditCard size={15} strokeWidth={1.8} />
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {value.numberFormatted}{value.holderName ? ` · ${value.holderName}` : ''}
          </span>
          {!disabled && (
            <button type="button" onClick={() => onChange(null)} title="Changer de carte"
              style={{ border: 0, background: 'none', color: 'inherit', padding: 0, display: 'flex' }}>
              <X size={15} />
            </button>
          )}
        </span>
      );
    }
    return (
      <div className="row" style={{ gap: 12, border: '1.5px solid #bcd3ee', background: 'var(--primary-50)', borderRadius: 10, padding: '8px 12px', minHeight: height }}>
        <CreditCard size={18} color="var(--primary)" style={{ flexShrink: 0 }} />
        <div style={{ flex: 1, minWidth: 0 }}><CardLine c={value} /></div>
        {!disabled && (
          <button type="button" className="btn btn-sm" onClick={() => onChange(null)}>Changer</button>
        )}
      </div>
    );
  }

  return (
    <div ref={box} style={{ position: 'relative', width, maxWidth: '100%', flex: width ? undefined : 1 }}>
      <div className="row" style={{ gap: 8 }}>
        <label className="input" style={{ height, flex: 1 }}>
          <CreditCard size={17} strokeWidth={1.8} />
          <input className="num" value={q} onChange={(e) => { setQ(e.target.value); setError(null); }} onKeyDown={onKey}
            placeholder={placeholder} autoFocus={autoFocus} disabled={disabled} inputMode="numeric" style={{ letterSpacing: '.03em' }} />
          {suffix}
          <button type="button" className="link-more" title="Chercher (Entrée)" onClick={() => run()} disabled={busy || disabled}
            style={{ display: 'flex' }}>
            <Search size={16} />
          </button>
        </label>
      </div>
      {error && <div style={{ color: 'var(--red)', fontSize: 12.5, marginTop: 5 }}>{error}</div>}
      {items && (
        <div className="dropdown" style={{ left: 0, right: 'auto', width: '100%', minWidth: 'min(380px, calc(100vw - 24px))' }}>
          <div className="dd-section">
            {items.length >= 30 ? '30 premières cartes · précisez le numéro' : `${items.length} cartes correspondent · choisissez`}
          </div>
          {items.map((c, i) => (
            <button key={c.id} type="button" className="dd-item" onMouseEnter={() => setActive(i)} onClick={() => pick(c)}
              style={i === active ? { background: 'var(--surface-2)' } : undefined}>
              <CardLine c={c} />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
