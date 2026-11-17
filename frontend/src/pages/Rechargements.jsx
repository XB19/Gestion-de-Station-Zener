import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  History, Wallet, Send, FileSpreadsheet, Download, Upload, Check, CircleCheck, CircleX, Smartphone, Search,
  Plus, X, Users, SlidersHorizontal, ListPlus,
} from 'lucide-react';
import { PageHeader, Card, Badge, StatusBadge, Button, Segmented, Checkbox, KV, Async, Empty, Pager } from '../components/ui.jsx';
import CardPicker from '../components/CardPicker.jsx';
import OperatorSelect from '../components/OperatorSelect.jsx';
import { useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, API_URL, download, getToken, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { cardNumber, dateTime, fcfa, money, PAYMENT_LABELS, time } from '../lib/format.js';

export function ValidationSteps({ steps }) {
  return (
    <div>
      {steps.map((st, i) => (
        <div key={st.t} className="row" style={{ alignItems: 'flex-start', gap: 14, paddingBottom: 16 }}>
          <span
            style={{
              width: 30, height: 30, borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center',
              fontWeight: 600, fontSize: 13,
              background: st.refused ? 'var(--red-500)' : st.done ? 'var(--green-500)' : st.current ? 'var(--primary-50)' : 'var(--chip)',
              color: st.done || st.refused ? '#fff' : st.current ? 'var(--primary)' : 'var(--muted)',
            }}
          >
            {st.refused ? <CircleX size={16} /> : st.done ? <Check size={16} strokeWidth={3} /> : i + 1}
          </span>
          <div>
            <div className="strong">{st.t}</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>{st.s}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

const AMOUNTS = [5000, 10000, 25000, 50000];
const PAYMENTS = [
  { value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' },
  { value: 'VIREMENT', label: 'Virement' }, { value: 'CARTE_BANCAIRE', label: 'Carte bancaire' },
];

/** Choix du montant : boutons rapides + saisie libre. */
function AmountPicker({ value, onChange }) {
  const [other, setOther] = useState(!AMOUNTS.includes(value));
  return (
    <>
      <div className="grid auto-cols" style={{ gridTemplateColumns: 'repeat(5, 1fr)', gap: 8, '--col': '96px' }}>
        {AMOUNTS.map((a) => (
          <button
            key={a}
            type="button"
            className="btn num"
            onClick={() => { setOther(false); onChange(a); }}
            style={{ padding: 0, fontWeight: 500, ...(!other && value === a ? { borderColor: 'var(--primary)', borderWidth: 1.5, color: 'var(--primary)', background: 'var(--primary-50)' } : {}) }}
          >
            {fcfa(a)}
          </button>
        ))}
        <button type="button" className="btn" onClick={() => setOther(true)}
          style={{ padding: 0, fontWeight: 500, ...(other ? { borderColor: 'var(--primary)', borderWidth: 1.5, color: 'var(--primary)', background: 'var(--primary-50)' } : {}) }}>
          Autre
        </button>
      </div>
      {other && (
        <label className="input" style={{ height: 42, marginTop: 8 }}>
          <input className="num strong" type="number" min="1" value={value || ''} onChange={(e) => onChange(Number(e.target.value))} placeholder="Montant en FCFA" autoFocus />
          <span className="muted">F</span>
        </label>
      )}
    </>
  );
}

/** Recharge unitaire au guichet. */
function UnitRecharge({ initialCard, threshold, onDone }) {
  const notify = useToast();
  const [card, setCard] = useState(null);
  const [error, setError] = useState(null);
  const [amount, setAmount] = useState(25000);
  const [payment, setPayment] = useState('ESPECES');
  const [ref, setRef] = useState('');
  const [busy, setBusy] = useState(false);

  const lookup = async (n = card?.number) => {
    setError(null);
    if (!n || n.replace(/\D/g, '').length !== 12) return;
    try {
      setCard(await api(`/cards/lookup/${n.replace(/\D/g, '')}`));
    } catch (e) {
      setError(e.message);
    }
  };
  useEffect(() => {
    if (initialCard) lookup(initialCard);
  }, [initialCard]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await post('/recharges', { cardNumber: card.number, amount, paymentMethod: payment, paymentRef: ref || null });
      notify(r.status === 'CREDITEE' ? `Carte créditée de ${money(amount)}` : `Au-dessus du palier : demande de validation envoyée (${money(amount)})`,
        r.status === 'CREDITEE' ? 'success' : 'info');
      lookup();
      onDone();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="card-title">Recharge unitaire</div>
      <div className="card-sub">Au guichet · au-dessus de {fcfa(threshold)} F la recharge part en validation</div>
      <form onSubmit={submit}>
        <label className="field-label" style={{ marginTop: 18 }}>N° de carte</label>
        {/* numéro complet ou 5 derniers chiffres ; liste de choix si plusieurs cartes correspondent */}
        <CardPicker value={card} onChange={(c) => (c ? lookup(c.number) : setCard(null))} height={44} />
        {error && <div className="form-error">{error}</div>}
        {card && card.status !== 'ACTIVE' && <Badge tone="red" style={{ marginTop: 10 }}>Carte {card.statusLabel?.toLowerCase()} : recharge impossible</Badge>}
        <label className="field-label" style={{ marginTop: 16 }}>Montant</label>
        <AmountPicker value={amount} onChange={setAmount} />
        {amount > threshold && <Badge tone="orange" style={{ marginTop: 10 }}>Au-dessus du palier : validation Comptable{card?.holderKind === 'ENTREPRISE' ? ' puis DAF' : ''}</Badge>}
        <div className="form-grid" style={{ marginTop: 14 }}>
          <div>
            <label className="field-label">Paiement</label>
            <label className="input">
              <select value={payment} onChange={(e) => setPayment(e.target.value)}>
                {PAYMENTS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
              </select>
            </label>
          </div>
          <div>
            <label className="field-label">Référence</label>
            <label className="input"><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder={payment === 'ESPECES' ? 'Facultatif' : 'N° de transaction'} /></label>
          </div>
        </div>
        <Button variant="primary" icon={Wallet} block style={{ height: 42, marginTop: 16 }} disabled={busy || !card || card.status !== 'ACTIVE' || !amount} type="submit">
          Recharger {amount ? money(amount) : ''}
        </Button>
      </form>
    </Card>
  );
}

/** Recharge à distance : nom + numéro suffisent s'ils concordent. */
function RemoteRecharge({ onDone }) {
  const notify = useToast();
  const [number, setNumber] = useState('');
  const [name, setName] = useState('');
  const [match, setMatch] = useState(null);
  const [amount, setAmount] = useState(25000);
  const [ref, setRef] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setMatch(null);
    if (number.replace(/\D/g, '').length !== 12 || name.trim().length < 3) return undefined;
    const t = setTimeout(() => {
      api(`/recharges/remote-check${qs({ cardNumber: number.replace(/\D/g, ''), name })}`).then((r) => setMatch(r.match)).catch(() => setMatch(false));
    }, 350);
    return () => clearTimeout(t);
  }, [number, name]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await post('/recharges/remote', { cardNumber: number, holderName: name, amount, paymentMethod: 'MOBILE_MONEY', paymentRef: ref.trim() });
      notify(r.status === 'CREDITEE' ? `Recharge à distance de ${money(amount)} créditée` : 'Recharge à distance en attente de validation',
        r.status === 'CREDITEE' ? 'success' : 'info');
      onDone();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="card-title">Recharge à distance</div>
      <div className="card-sub">Nom + numéro de carte suffisent</div>
      <form onSubmit={submit}>
        <label className="field-label" style={{ marginTop: 18 }}>N° de carte</label>
        <label className="input" style={{ height: 42 }}><input className="num strong" value={number} onChange={(e) => setNumber(e.target.value)} required /></label>
        <label className="field-label" style={{ marginTop: 14 }}>Nom de l’utilisateur</label>
        <label className="input" style={{ height: 42 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} required />
          {match === true && <CircleCheck size={18} color="var(--green)" />}
          {match === false && <CircleX size={18} color="var(--red)" />}
        </label>
        <div className="muted" style={{ fontSize: 13, marginTop: 8, color: match === false ? 'var(--red)' : undefined }}>
          {match === null ? 'Saisissez le nom tel qu’il figure sur la carte' : match ? 'Nom et numéro concordent' : 'Le nom ne correspond pas au titulaire'}
        </div>
        <label className="field-label" style={{ marginTop: 14 }}>Montant</label>
        <AmountPicker value={amount} onChange={setAmount} />
        <label className="field-label" style={{ marginTop: 14 }}>Référence mobile money <span style={{ color: 'var(--red)' }}>*</span></label>
        <label className="input" style={{ height: 42 }}>
          <input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="N° de transaction TMoney / Flooz" required minLength={4} />
        </label>
        <div className="muted" style={{ fontSize: 13, marginTop: 8 }}>Obligatoire : seule preuve du paiement quand le client n’est pas au guichet</div>
        <Button icon={Smartphone} variant="primary" block style={{ height: 42, marginTop: 14 }} type="submit" disabled={busy || !match || !amount || ref.trim().length < 4}>
          Envoyer via mobile money
        </Button>
      </form>
    </Card>
  );
}

/** Recharge en masse : import d'un fichier, contrôle ligne par ligne, crédit et validation. */
/**
 * Recharge en masse préparée dans l'application. On constitue une liste de cartes à recharger en les ajoutant
 * de plusieurs façons (recherche d'une carte par nom, plaque ou numéro ; une flotte entière ; des critères
 * facultatifs ; une liste de numéros collée), puis on indique le montant de chacune (ou de toutes d'un coup),
 * et on envoie la liste au contrôle du lot ou on l'exporte en fichier (montant compris).
 */
const SPENT_DAYS = 30;
const ADD_MODES = [
  { label: 'Rechercher', icon: Search },
  { label: 'Une flotte', icon: Users },
  { label: 'Par critères', icon: SlidersHorizontal },
  { label: 'Liste de numéros', icon: ListPlus },
];
const NO_CRITERIA = { type: '', spentDays: '30', minSpent: '', maxSpent: '', balanceBelow: '', inactiveDays: '' };

/** Cartes trouvées, à ajouter une par une ou toutes d'un coup à la liste à recharger. */
function FoundCards({ items, have, busy, onAdd }) {
  if (busy) return <div className="muted" style={{ marginTop: 10, fontSize: 13 }}>Recherche…</div>;
  if (!items) return null;
  if (!items.length) return <div className="muted" style={{ marginTop: 10, fontSize: 13 }}>Aucune carte active ne correspond.</div>;
  const fresh = items.filter((c) => !have.has(c.number));
  return (
    <div style={{ marginTop: 10, border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
      <div className="row between" style={{ padding: '8px 12px', background: 'var(--surface-2)', gap: 8, flexWrap: 'wrap' }}>
        <span className="strong" style={{ fontSize: 13 }}>
          {items.length} carte(s) trouvée(s){items.length > fresh.length ? ` · ${items.length - fresh.length} déjà dans la liste` : ''}
        </span>
        {fresh.length > 1 && <Button size="sm" variant="primary" icon={Plus} onClick={() => onAdd(fresh)}>Ajouter les {fresh.length}</Button>}
      </div>
      <div style={{ maxHeight: 240, overflow: 'auto' }}>
        {items.slice(0, 100).map((c) => (
          <div key={c.number} className="row" style={{ gap: 10, padding: '7px 12px', borderTop: '1px solid var(--border)', fontSize: 13 }}>
            <span className="num strong" style={{ width: 128, flexShrink: 0 }}>{c.numberFormatted}</span>
            <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {c.holderName || 'Sans titulaire'}
              <span className="muted">{[c.typeLabel, c.groupName, c.vehiclePlate].filter(Boolean).map((x) => ` · ${x}`).join('')}</span>
            </span>
            <span className="muted num" style={{ whiteSpace: 'nowrap' }}>solde {fcfa(c.balance)} F</span>
            {have.has(c.number)
              ? <Badge tone="green">Dans la liste</Badge>
              : <Button size="sm" icon={Plus} onClick={() => onAdd([c])}>Ajouter</Button>}
          </div>
        ))}
        {items.length > 100 && (
          <div className="muted" style={{ padding: '7px 12px', borderTop: '1px solid var(--border)', fontSize: 13 }}>
            … et {items.length - 100} autre(s), comprises dans « Ajouter les {fresh.length} »
          </div>
        )}
      </div>
    </div>
  );
}

function PrepareBulk({ onBatch, payment, paymentRef }) {
  const notify = useToast();
  const groups = useApi('/card-groups');
  const [mode, setMode] = useState(ADD_MODES[0].label);
  const [q, setQ] = useState('');
  const [groupId, setGroupId] = useState('');
  const [crit, setCrit] = useState(NO_CRITERIA);
  const [numbers, setNumbers] = useState('');
  const [found, setFound] = useState(null);
  const [finding, setFinding] = useState(false);
  const [rows, setRows] = useState([]);
  const [amounts, setAmounts] = useState({});
  const [bulk, setBulk] = useState({ mode: 'FIXE', value: '' });
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);
  const have = new Set(rows.map((c) => c.number));

  const find = async (params) => {
    const n = ++seq.current;
    setFinding(true);
    try {
      const r = await api(`/recharges/selection${qs({ spentDays: SPENT_DAYS, ...params, limit: 2000 })}`);
      if (n === seq.current) setFound(r.map((c) => ({ ...c, spentDays: Number(params.spentDays) || SPENT_DAYS })));
    } catch (e) {
      if (n === seq.current) { setFound(null); notify(e.message, 'error'); }
    } finally {
      if (n === seq.current) setFinding(false);
    }
  };
  const switchMode = (m) => {
    seq.current += 1;
    setMode(m);
    setFound(null);
    setFinding(false);
  };

  // recherche au fil de la frappe : nom du titulaire, plaque, chauffeur, ou chiffres du numéro
  useEffect(() => {
    if (mode !== 'Rechercher') return undefined;
    const t = q.trim();
    const digits = t.replace(/[\s.-]/g, '');
    const numeric = /^\d+$/.test(digits);
    if (!t || (numeric ? digits.length < 4 : t.length < 2)) {
      seq.current += 1;
      setFound(null);
      setFinding(false);
      return undefined;
    }
    const h = setTimeout(() => find(numeric ? { numbers: digits } : { q: t }), 300);
    return () => clearTimeout(h);
  }, [q, mode]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (mode === 'Une flotte' && groupId) find({ groupId });
  }, [groupId, mode]); // eslint-disable-line react-hooks/exhaustive-deps

  const add = (cards) => {
    setRows((r) => {
      const known = new Set(r.map((c) => c.number));
      return [...r, ...cards.filter((c) => !known.has(c.number))];
    });
    notify(cards.length > 1 ? `${cards.length} cartes ajoutées à la liste` : `Carte ${cards[0].numberFormatted} ajoutée`);
  };
  const remove = (number) => setRows((r) => r.filter((c) => c.number !== number));
  const clearAll = () => {
    setRows([]);
    setAmounts({});
  };
  const apply = () => {
    const v = Number(bulk.value);
    if (!rows.length || !(v > 0)) return notify('Indiquez une valeur', 'error');
    setAmounts((a) => {
      const next = { ...a };
      rows.forEach((c) => {
        const amount = bulk.mode === 'FIXE' ? v
          : bulk.mode === 'POURCENT' ? Math.round((Number(c.spent) * v) / 100 / 1000) * 1000
            : Math.max(v - Number(c.balance), 0);
        next[c.number] = amount > 0 ? String(amount) : '';
      });
      return next;
    });
    return undefined;
  };

  const lines = rows.filter((c) => Number(amounts[c.number]) > 0)
    .map((c) => ({ cardNumber: c.number, amount: Number(amounts[c.number]), label: label || null }));
  const missing = rows.length - lines.length;
  const total = lines.reduce((t, l) => t + l.amount, 0);

  const exportFile = async (format) => {
    if (!rows.length) return notify('Ajoutez des cartes à la liste', 'error');
    const all = rows.map((c) => ({ cardNumber: c.number, amount: Number(amounts[c.number]) || null, label: label || null }));
    const res = await fetch(`${API_URL}/api/recharges/selection/export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ lines: all, format, spentDays: SPENT_DAYS }),
    });
    if (!res.ok) return notify('Export impossible', 'error');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(await res.blob());
    a.download = `recharges.${format}`;
    a.click();
    URL.revokeObjectURL(a.href);
    return undefined;
  };
  const submit = async () => {
    if (!lines.length) return notify('Indiquez un montant pour au moins une carte', 'error');
    setBusy(true);
    try {
      const b = await post('/recharges/batches', { lines: lines.map((l) => ({ ...l, amount: String(l.amount) })), paymentMethod: payment, paymentRef: paymentRef || null });
      notify(`${b.lines} lignes préparées et contrôlées`);
      onBatch(b);
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
    return undefined;
  };
  const setC = (k) => (e) => setCrit((x) => ({ ...x, [k]: e.target.value }));
  const anyCriteria = Object.entries(crit).some(([k, v]) => v !== NO_CRITERIA[k]);

  return (
    <Card className="card-pad">
      <div className="card-title">Préparer dans l'application</div>
      <div className="card-sub">1. Ajoutez les cartes à recharger · 2. indiquez le montant de chacune (ou de toutes d'un coup) · 3. envoyez au contrôle ou exportez</div>

      <div style={{ marginTop: 14 }}>
        <Segmented items={ADD_MODES} value={mode} onChange={switchMode} />
      </div>

      {mode === 'Rechercher' && (
        <div style={{ marginTop: 12 }}>
          <label className="input" style={{ height: 42 }}>
            <Search size={17} strokeWidth={1.8} />
            <input value={q} onChange={(e) => setQ(e.target.value)} autoFocus
              placeholder="Nom du titulaire, plaque, chauffeur, ou chiffres du numéro (5 derniers suffisent)" />
          </label>
        </div>
      )}
      {mode === 'Une flotte' && (
        <div style={{ marginTop: 12 }}>
          <label className="input" style={{ height: 42 }}>
            <select value={groupId} onChange={(e) => setGroupId(e.target.value)}>
              <option value="">Choisissez une flotte…</option>
              {(groups.data || []).map((g) => <option key={g.id} value={g.id}>{g.holderName} · {g.name}</option>)}
            </select>
          </label>
        </div>
      )}
      {mode === 'Par critères' && (
        <div style={{ marginTop: 12 }}>
          <div className="muted" style={{ fontSize: 13 }}>Tous les critères sont facultatifs : sans critère, toutes les cartes actives sont proposées.</div>
          <div className="form-grid" style={{ marginTop: 8 }}>
            <div><label className="field-label">Type de carte</label><label className="input"><select value={crit.type} onChange={setC('type')}>
              <option value="">Tous</option><option value="CLASSIQUE">Classique</option><option value="CLUB">Club</option><option value="FLOTTE">Flotte</option></select></label></div>
            <div><label className="field-label">Dépenses calculées sur (jours)</label><label className="input"><input type="number" min="1" value={crit.spentDays} onChange={setC('spentDays')} /></label></div>
            <div><label className="field-label">A dépensé au moins (F)</label><label className="input"><input type="number" min="0" value={crit.minSpent} onChange={setC('minSpent')} placeholder="—" /></label></div>
            <div><label className="field-label">A dépensé au plus (F)</label><label className="input"><input type="number" min="0" value={crit.maxSpent} onChange={setC('maxSpent')} placeholder="—" /></label></div>
            <div><label className="field-label">Solde inférieur à (F)</label><label className="input"><input type="number" min="0" value={crit.balanceBelow} onChange={setC('balanceBelow')} placeholder="—" /></label></div>
            <div><label className="field-label">Sans achat depuis (jours)</label><label className="input"><input type="number" min="1" value={crit.inactiveDays} onChange={setC('inactiveDays')} placeholder="—" /></label></div>
          </div>
          <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 10 }}>
            {anyCriteria && <Button size="sm" onClick={() => setCrit(NO_CRITERIA)}>Effacer les critères</Button>}
            <Button icon={Search} disabled={finding} onClick={() => find(crit)}>Trouver les cartes</Button>
          </div>
        </div>
      )}
      {mode === 'Liste de numéros' && (
        <div style={{ marginTop: 12 }}>
          <textarea className="textarea" rows={3} value={numbers} onChange={(e) => setNumbers(e.target.value)}
            placeholder={'Collez des numéros (complets ou 5 derniers chiffres), un par ligne ou séparés par des virgules\n6011 2847 0093\n44102'} />
          <div className="row" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
            <Button icon={Search} disabled={finding || !numbers.trim()} onClick={() => find({ numbers })}>Retrouver ces cartes</Button>
          </div>
        </div>
      )}
      <FoundCards items={found} have={have} busy={finding} onAdd={add} />

      <div className="row between" style={{ marginTop: 20, gap: 8, flexWrap: 'wrap' }}>
        <div className="strong">Cartes à recharger <Badge tone={rows.length ? 'blue' : 'gray'}>{rows.length}</Badge></div>
        {rows.length > 0 && <Button size="sm" icon={X} onClick={clearAll}>Vider la liste</Button>}
      </div>
      {rows.length === 0 ? (
        <div className="muted" style={{ marginTop: 8, border: '1.5px dashed var(--border)', borderRadius: 10, padding: '18px 14px', textAlign: 'center', fontSize: 13 }}>
          Aucune carte pour l'instant : recherchez une carte, choisissez une flotte, filtrez par critères ou collez une liste de numéros, puis « Ajouter ».
        </div>
      ) : (
        <>
          <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap', background: 'var(--surface-2)', borderRadius: 10, padding: '10px 12px' }}>
            <span className="strong">Montant pour toutes :</span>
            <label className="input" style={{ width: 210 }}><select value={bulk.mode} onChange={(e) => setBulk({ ...bulk, mode: e.target.value })}>
              <option value="FIXE">Même montant (F)</option><option value="POURCENT">% des dépenses récentes</option><option value="CIBLE">Compléter le solde jusqu'à (F)</option></select></label>
            <label className="input" style={{ width: 140 }}><input type="number" min="1" value={bulk.value} onChange={(e) => setBulk({ ...bulk, value: e.target.value })} /></label>
            <Button size="sm" onClick={apply}>Appliquer</Button>
            <Button size="sm" onClick={() => setAmounts({})}>Effacer les montants</Button>
            <label className="input" style={{ width: 220, marginLeft: 'auto' }}><input placeholder="Libellé (facultatif)" value={label} onChange={(e) => setLabel(e.target.value)} /></label>
          </div>
          <div className="table-wrap" style={{ maxHeight: 420, overflow: 'auto', marginTop: 10 }}><table className="table">
            <thead><tr>
              <th>Carte</th><th>Titulaire</th><th>Flotte</th><th className="r">Solde</th><th className="r">Dépenses récentes</th><th>Dernière recharge</th><th className="r">Montant (F)</th><th style={{ width: 36 }} />
            </tr></thead>
            <tbody>{rows.map((c) => (
              <tr key={c.number}>
                <td className="num strong">{c.numberFormatted}</td>
                <td>{c.holderName || '—'}{c.vehiclePlate ? <span className="muted"> · {c.vehiclePlate}</span> : ''}</td>
                <td>{c.groupName || '—'}</td>
                <td className="r num">{fcfa(c.balance)}</td>
                <td className="r num">{fcfa(c.spent)}<span className="muted"> / {c.spentDays || SPENT_DAYS} j</span></td>
                <td className="muted num">{c.lastRechargeAt ? `${dateTime(c.lastRechargeAt)} · ${fcfa(c.lastRechargeAmount)} F` : '—'}</td>
                <td className="r" style={{ width: 140 }}>
                  <label className="input" style={{ height: 32 }}><input type="number" min="0" step="1000" value={amounts[c.number] ?? ''} placeholder="0"
                    onChange={(e) => setAmounts({ ...amounts, [c.number]: e.target.value })} /></label>
                </td>
                <td><button type="button" className="link-more" title="Retirer de la liste" onClick={() => remove(c.number)}><X size={15} /></button></td>
              </tr>
            ))}</tbody>
          </table></div>
          <div className="row between" style={{ marginTop: 12, flexWrap: 'wrap', rowGap: 8 }}>
            <span>
              <span className="strong">{lines.length} carte(s) · {money(total)}</span>
              {missing > 0 && <span className="muted"> · {missing} sans montant (ignorée{missing > 1 ? 's' : ''})</span>}
            </span>
            <span className="row" style={{ gap: 8 }}>
              <Button size="sm" icon={Download} onClick={() => exportFile('csv')}>Exporter CSV</Button>
              <Button size="sm" icon={Download} onClick={() => exportFile('xlsx')}>Exporter Excel</Button>
              <Button variant="primary" icon={Check} disabled={busy || !lines.length} onClick={submit}>Contrôler et préparer le lot</Button>
            </span>
          </div>
        </>
      )}
    </Card>
  );
}

function BulkRecharge({ onDone }) {
  const notify = useToast();
  const fileRef = useRef(null);
  const [batchId, setBatchId] = useState(null);
  const [page, setPage] = useState(0);
  const [payment, setPayment] = useState('VIREMENT');
  const [paymentRef, setPaymentRef] = useState('');
  const [busy, setBusy] = useState(false);
  const batch = useApi(batchId ? `/recharges/batches/${batchId}?page=${page}&size=10` : null);
  const validation = useApi(batch.data?.validation ? `/validations/${batch.data.validation.id}` : null);

  const importFile = async (file) => {
    if (!file) return;
    setBusy(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const b = await api(`/recharges/batches${qs({ paymentMethod: payment, paymentRef })}`, { method: 'POST', form });
      setBatchId(b.id);
      setPage(0);
      notify(`${b.lines} lignes importées et contrôlées`);
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };
  const toggle = async (line, included) => {
    try {
      await put(`/recharges/batches/${batchId}/lines/${line.id}`, { included });
      batch.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const run = async (path, message) => {
    setBusy(true);
    try {
      await post(`/recharges/batches/${batchId}/${path}`);
      notify(message);
      batch.reload();
      onDone();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const b = batch.data;
  const v = validation.data;
  const steps = b ? [
    { t: 'Import & contrôle', s: `${b.createdByName} · ${time(b.createdAt)}`, done: true },
    ...(v?.steps || [{ roles: ['DAF'], status: 'A_VENIR' }]).map((st) => ({
      t: `Validation ${st.roles.includes('DAF') && st.roles.length === 1 ? 'DAF' : st.roles.includes('COMPTABLE') ? 'Comptable' : st.roles.join(' / ')}`,
      s: st.decidedByName ? `${st.decidedByName} · ${time(st.decidedAt)}` : st.approvers || (b.toValidate ? 'À la soumission' : 'Non requise'),
      done: st.status === 'APPROUVE', refused: st.status === 'REFUSE', current: st.status === 'EN_ATTENTE',
    })),
    { t: 'Crédit des cartes', s: b.status === 'TERMINE' ? 'Terminé' : 'Automatique après approbation', done: b.status === 'TERMINE' },
  ] : [];

  return (
    <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
      <div className="stack">
        <PrepareBulk payment={payment} paymentRef={paymentRef} onBatch={(nb) => { setBatchId(nb.id); setPage(0); }} />
        <Card className="card-pad">
          <div className="card-title">Rechargement en masse</div>
          <div className="card-sub">Ou par fichier (.xlsx, .csv) : numéro de carte ; montant ; libellé (les colonnes suivantes sont ignorées, comme dans l'export)</div>
          <input ref={fileRef} type="file" accept=".csv,.xlsx,.xls" style={{ display: 'none' }} onChange={(e) => importFile(e.target.files[0])} />
          <div className="row" style={{ gap: 14, marginTop: 18, border: '1.5px dashed #b9cde6', background: '#f7faff', borderRadius: 10, padding: '16px 18px' }}>
            <span className="ico-box tone-green" style={{ width: 44, height: 44 }}><FileSpreadsheet size={21} strokeWidth={1.7} /></span>
            <div style={{ flex: 1 }}>
              <div className="strong">{b?.fileName || 'Aucun fichier importé'}</div>
              <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
                {b ? `${b.lines} lignes · importé ${time(b.createdAt)} par ${b.createdByName}` : 'Téléchargez le modèle, complétez-le puis importez-le'}
              </div>
            </div>
            <Button icon={Download} onClick={() => download('/recharges/template', 'modele_recharges.csv').catch((e) => notify(e.message, 'error'))}>Modèle de fichier</Button>
            <Button icon={Upload} variant={b ? undefined : 'primary'} disabled={busy} onClick={() => fileRef.current?.click()}>{b ? 'Remplacer' : 'Importer'}</Button>
          </div>
          <div className="form-grid" style={{ marginTop: 14 }}>
            <div>
              <label className="field-label">Paiement du lot</label>
              <label className="input">
                <select value={payment} onChange={(e) => setPayment(e.target.value)}>
                  {PAYMENTS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                </select>
              </label>
            </div>
            <div>
              <label className="field-label">Référence</label>
              <label className="input"><input value={paymentRef} onChange={(e) => setPaymentRef(e.target.value)} placeholder="Virement Ecobank…" /></label>
            </div>
          </div>
        </Card>

        {b && (
          <Card>
            <div className="card-head" style={{ paddingBottom: 16 }}>
              <div>
                <div className="card-title">Aperçu de l’import</div>
                <div className="card-sub">Contrôle automatique de chaque ligne avant envoi</div>
              </div>
              <div className="row" style={{ gap: 8 }}>
                <Badge tone="green">{b.ready} prêtes</Badge>
                <Badge tone="orange">{b.toValidate} à valider</Badge>
                <Badge tone="red">{b.errors} erreur{b.errors > 1 ? 's' : ''}</Badge>
              </div>
            </div>
            <div className="table-wrap"><table className="table">
              <thead>
                <tr><th style={{ width: 40 }} /><th>N° de carte</th><th>Titulaire</th><th className="r">Montant (F)</th><th>Contrôle</th></tr>
              </thead>
              <tbody>
                {b.items.map((l) => {
                  const editable = b.status === 'BROUILLON' && !l.rechargeStatus && ['PRETE', 'VALIDATION_REQUISE'].includes(l.checkStatus);
                  return (
                    <tr key={l.id}>
                      <td>{editable ? <Checkbox checked={l.included} onChange={(x) => toggle(l, x)} /> : <Checkbox checked={l.included && !!l.rechargeStatus} />}</td>
                      <td className="num strong" style={{ letterSpacing: '.02em' }}>{l.cardNumberFormatted}</td>
                      <td>{l.holderLabel || '—'}</td>
                      <td className="r num strong">{l.amount != null ? fcfa(l.amount) : '—'}</td>
                      <td>{l.rechargeStatus === 'CREDITEE' ? <StatusBadge status="Créditée" /> : <StatusBadge status={l.checkLabel} />}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table></div>
            <Pager data={{ total: b.lines, size: b.size, items: b.items }} page={page} onPage={setPage} label="lignes" />
          </Card>
        )}
      </div>

      <div className="stack">
        <Card className="card-pad">
          <div className="card-title">Récapitulatif</div>
          {!b && <Empty>Importez un fichier pour voir le récapitulatif</Empty>}
          {b && (
            <>
              <div style={{ marginTop: 8 }}>
                <KV k="Cartes" v={`${b.validCards} retenues`} />
                <KV k="Montant total" v={money(b.totalAmount)} />
                <KV k="Dont soumis à validation" v={money(b.amountToValidate)} />
                <KV k="Palier de validation" v={`${fcfa(b.threshold)} F / carte`} />
                <KV k="Mode de paiement" v={[PAYMENT_LABELS[b.paymentMethod], b.paymentRef].filter(Boolean).join(' · ')} />
                {b.validation && <KV k="Demande" v={`${b.validation.ref}`} />}
              </div>
              <div className="section-label" style={{ marginTop: 16 }}>Circuit de validation</div>
              <ValidationSteps steps={steps} />
              {b.status === 'BROUILLON' && (
                <>
                  <Button variant="primary" icon={Send} block style={{ height: 42 }} disabled={busy || b.validCards === 0}
                    onClick={() => run('submit', b.toValidate ? 'Cartes prêtes créditées, lignes au-dessus du palier envoyées en validation' : 'Lot crédité')}>
                    Soumettre pour validation
                  </Button>
                  <Button block style={{ height: 42, marginTop: 10 }} disabled={busy || !b.readyToCredit}
                    onClick={() => run('credit-ready', `${b.readyToCredit} cartes créditées`)}>
                    Créditer les {b.readyToCredit} cartes prêtes
                  </Button>
                </>
              )}
              {b.status !== 'BROUILLON' && <Badge tone={b.status === 'TERMINE' ? 'green' : 'orange'}>{b.status === 'TERMINE' ? 'Lot terminé' : 'Lot soumis · en validation'}</Badge>}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

export default function Rechargements() {
  const [params] = useSearchParams();
  const { can } = useSession();
  const [mode, setMode] = useState(params.get('mode') === 'masse' ? 'En masse' : 'Recharge unitaire');
  const [page, setPage] = useState(0);
  const [operator, setOperator] = useState('');
  const history = useApi(`/recharges${qs({ employeeId: operator, page, size: 10 })}`);
  const settings = useApi('/settings');
  const historyRef = useRef(null);
  useLiveReload(history.reload, ['operation'], 4000);
  const threshold = Number(settings.data?.['validation.threshold'] || 500000);

  return (
    <>
      <PageHeader
        title="Rechargements"
        subtitle="Recharges unitaires, à distance et en masse, avec validation des montants élevés"
        actions={<Button icon={History} onClick={() => historyRef.current?.scrollIntoView({ behavior: 'smooth' })}>Historique</Button>}
      />

      <div className="mb20">
        <Segmented
          items={[{ label: 'Recharge unitaire', icon: Wallet }, { label: 'À distance', icon: Send }, { label: 'En masse', icon: FileSpreadsheet }]}
          value={mode}
          onChange={setMode}
        />
      </div>

      {can('RECHARGER') ? (
        <>
          {mode === 'En masse' && <BulkRecharge onDone={history.reload} />}
          {mode !== 'En masse' && (
            <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px', alignItems: 'start' }}>
              {mode === 'Recharge unitaire' ? <UnitRecharge initialCard={params.get('card')} threshold={threshold} onDone={history.reload} />
                : <RemoteRecharge onDone={history.reload} />}
              <Card className="card-pad">
                <div className="card-title">Circuit de validation</div>
                <div className="card-sub">Au-dessus du palier de {fcfa(threshold)} F par carte</div>
                <div style={{ marginTop: 16 }}>
                  <ValidationSteps steps={[
                    { t: 'Demande de recharge', s: 'Guichet, à distance ou en masse', done: true },
                    { t: 'Validation Comptable', s: 'Montant et paiement vérifiés' },
                    { t: 'Validation DAF', s: 'Cartes entreprises uniquement' },
                    { t: 'Crédit de la carte', s: 'Automatique après approbation' },
                  ]} />
                </div>
              </Card>
            </div>
          )}
        </>
      ) : (
        <Card className="card-pad mb20"><Empty>Consultation seule : vous n’avez pas le droit de recharger des cartes.</Empty></Card>
      )}

      <div ref={historyRef} />
      <Card style={{ marginTop: 20 }}>
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Historique des recharges</div>
          <OperatorSelect value={operator} onChange={(v) => { setOperator(v); setPage(0); }} />
        </div>
        <Async state={history}>
          {(data) => (data.items.length === 0 ? <Empty>Aucune recharge</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead>
                  <tr><th>Réf.</th><th>Quand</th><th>Carte</th><th>Titulaire</th><th className="r">Montant (F)</th><th>Canal · paiement</th><th>Opérateur</th><th>Lieu</th><th>Statut</th></tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id}>
                      <td className="strong num">{r.ref}</td>
                      <td className="muted num">{dateTime(r.createdAt)}</td>
                      <td className="num">{cardNumber(r.number)}</td>
                      <td>{r.holderName}</td>
                      <td className="r num strong">{fcfa(r.amount)}</td>
                      <td>{{ UNITAIRE: 'Guichet', DISTANCE: 'À distance', MASSE: 'En masse' }[r.channel]} · {PAYMENT_LABELS[r.paymentMethod]}</td>
                      <td>{r.requestedByName}</td>
                      <td className="muted">{r.place}</td>
                      <td><StatusBadge status={{ CREDITEE: 'Créditée', EN_VALIDATION: 'En validation', REFUSEE: 'Refusée' }[r.status]} /></td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="recharges" />
            </>
          ))}
        </Async>
      </Card>
    </>
  );
}

