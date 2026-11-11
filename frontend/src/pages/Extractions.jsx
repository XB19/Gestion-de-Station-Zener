import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Download, FileSpreadsheet, FileText, X, Building2, User, Calendar, Fuel, Store, Package, Layers,
  Timer, Database, ChevronDown,
} from 'lucide-react';
import { PageHeader, Card, Badge, Button, Select, Async, Empty, Pager } from '../components/ui.jsx';
import CardPicker from '../components/CardPicker.jsx';
import { useToast } from '../components/feedback.jsx';
import { useApi } from '../api/hooks.js';
import { api, download, qs } from '../api/client.js';
import { dateTime, fcfa, isoDay, num } from '../lib/format.js';

/** Libellés des filtres (noms renvoyés par l'API). */
const FILTER_LABELS = {
  period: 'Période', station: 'Station', pdv: 'Point de vente', card: 'Carte', holder: 'Client', group: 'Groupe de cartes',
  product: 'Produit', employee: 'Opérateur', shift: 'Poste',
};

const firstOfMonth = () => {
  const d = new Date();
  return isoDay(new Date(d.getFullYear(), d.getMonth(), 1));
};

const EMPTY = { from: firstOfMonth(), to: isoDay(), stationId: '', pdvId: '', card: null, holder: null, groupId: '', productId: '', employee: null, shiftId: '' };

/** Valeur d'une cellule selon le type de colonne. */
function cell(v, type) {
  if (v === null || v === undefined || v === '') return '';
  switch (type) {
    case 'DATETIME': return dateTime(v);
    case 'DATE': return new Date(v).toLocaleDateString('fr-FR');
    case 'MONEY': return Number(v) < 0 ? `−${fcfa(-v)}` : fcfa(v);
    case 'QTY': return num(v, 2);
    case 'INT': return num(v);
    default: return v;
  }
}
const isNum = (t) => t === 'MONEY' || t === 'QTY' || t === 'INT';

/** Valeur filtrée pour la requête : seulement les filtres que l'extraction sait appliquer. */
function params(f, supported) {
  const p = {};
  if (supported.includes('period')) { p.from = f.from; p.to = f.to; }
  if (supported.includes('station')) p.stationId = f.stationId;
  if (supported.includes('pdv')) p.pdvId = f.pdvId;
  if (supported.includes('card')) p.cardId = f.card?.id;
  if (supported.includes('holder')) p.holderId = f.holder?.id;
  if (supported.includes('group')) p.groupId = f.groupId;
  if (supported.includes('product')) p.productId = f.productId;
  if (supported.includes('employee')) p.employeeId = f.employee?.id;
  if (supported.includes('shift')) p.shiftId = f.shiftId;
  return p;
}

/** Filtres renseignés mais non applicables à l'extraction choisie. */
function unused(f, supported) {
  const set = {
    station: f.stationId, pdv: f.pdvId, card: f.card, holder: f.holder, group: f.groupId, product: f.productId,
    employee: f.employee, shift: f.shiftId,
  };
  return Object.entries(set).filter(([k, v]) => v && !supported.includes(k)).map(([k]) => FILTER_LABELS[k]);
}

/** Valeur choisie affichée comme une étiquette qu'on peut retirer. */
function Chip({ icon: Icon, children, onClear }) {
  return (
    <span className="select" style={{ background: 'var(--primary-50)', borderColor: '#bcd3ee', color: 'var(--primary)', maxWidth: '100%' }}>
      <Icon size={15} strokeWidth={1.8} />
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{children}</span>
      <button type="button" onClick={onClear} title="Retirer" style={{ border: 0, background: 'none', color: 'inherit', padding: 0, display: 'flex' }}>
        <X size={15} />
      </button>
    </span>
  );
}

/** Champ de recherche avec liste de choix (cartes, clients, opérateurs). */
function Picker({ icon: Icon, placeholder, width = 220, search, render, onPick, minLength = 2, hint }) {
  const [q, setQ] = useState('');
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const box = useRef(null);
  const seq = useRef(0);

  const run = async (value) => {
    const n = ++seq.current;
    try {
      const r = await search(value);
      if (n !== seq.current) return;
      setError(null);
      setItems(r);
    } catch (e) {
      if (n === seq.current) { setItems(null); setError(e.message); }
    }
  };

  useEffect(() => {
    if (q.trim().length < minLength) { setItems(null); return undefined; }
    const t = setTimeout(() => run(q.trim()), 250);
    return () => clearTimeout(t);
  }, [q]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const close = (e) => box.current && !box.current.contains(e.target) && setItems(null);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  return (
    <form ref={box} style={{ position: 'relative', width, maxWidth: '100%' }} onSubmit={(e) => { e.preventDefault(); if (q.trim()) run(q.trim()); }}>
      <label className="input" style={{ height: 38 }} title={hint}>
        <Icon size={16} strokeWidth={1.8} />
        <input placeholder={placeholder} value={q} onChange={(e) => { setQ(e.target.value); setError(null); }} />
      </label>
      {(items || error) && (
        <div className="dropdown" style={{ left: 0, right: 'auto', width: Math.max(width, 320) }}>
          {error && <div className="muted" style={{ padding: '10px 12px', color: 'var(--red)' }}>{error}</div>}
          {items && items.length === 0 && <div className="muted" style={{ padding: '10px 12px' }}>Aucun résultat</div>}
          {items && items.map((it) => (
            <button key={it.id} type="button" className="dd-item" onClick={() => { onPick(it); setQ(''); setItems(null); }}>
              {render(it)}
            </button>
          ))}
        </div>
      )}
    </form>
  );
}

function Filters({ supported, f, setF, options, scoped }) {
  const has = (k) => supported.includes(k);
  const set = (patch) => setF((cur) => ({ ...cur, ...patch }));
  const shifts = useApi(has('shift') && f.employee ? `/extractions/shifts${qs({ employeeId: f.employee.id, from: f.from, to: f.to })}` : null);
  const groups = (options?.groups || []).filter((g) => !f.holder || g.holderId === f.holder.id);
  const skipped = unused(f, supported);

  return (
    <div className="filters" style={{ borderBottom: '1px solid var(--border-2)', alignItems: 'center' }}>
      {has('period') && (
        <span className="row" style={{ gap: 6 }}>
          <label className="input" style={{ height: 38, width: 158 }} title="Du">
            <Calendar size={16} strokeWidth={1.8} />
            <input type="date" value={f.from} max={f.to || undefined} onChange={(e) => set({ from: e.target.value })} />
          </label>
          <span className="muted">→</span>
          <label className="input" style={{ height: 38, width: 158 }} title="Au">
            <Calendar size={16} strokeWidth={1.8} />
            <input type="date" value={f.to} min={f.from || undefined} onChange={(e) => set({ to: e.target.value })} />
          </label>
        </span>
      )}
      {has('card') && (
        <CardPicker compact value={f.card} onChange={(c) => set({ card: c })} width={270} height={38}
          placeholder="Carte : n° ou 5 derniers chiffres" />
      )}
      {has('holder') && (f.holder ? (
        <Chip icon={Building2} onClear={() => set({ holder: null, groupId: '' })}>{f.holder.name}</Chip>
      ) : (
        <Picker icon={Building2} placeholder="Client / titulaire" search={(q) => api(`/extractions/holders${qs({ q })}`)}
          onPick={(h) => set({ holder: h, groupId: '' })}
          render={(h) => (
            <span><span className="strong">{h.name}</span>
              <span className="muted" style={{ display: 'block', fontSize: 12.5 }}>
                {{ PARTICULIER: 'Particulier', ENTREPRISE: 'Entreprise', EMPLOYE: 'Employé' }[h.kind]} · {h.cards} carte(s)
              </span>
            </span>
          )} />
      ))}
      {has('group') && groups.length > 0 && (
        <Select icon={Layers} value={f.groupId} onChange={(v) => set({ groupId: v })} placeholder="Groupe de cartes"
          options={groups.map((g) => ({ value: g.id, label: f.holder ? g.name : `${g.holderName} · ${g.name}` }))} />
      )}
      {has('station') && (
        <Select icon={Fuel} value={f.stationId} onChange={(v) => set({ stationId: v })} placeholder={scoped ? undefined : 'Toutes les stations'}
          options={(options?.stations || []).map((s) => ({ value: s.id, label: s.name }))} />
      )}
      {has('pdv') && !scoped && (options?.pdvs || []).length > 0 && (
        <Select icon={Store} value={f.pdvId} onChange={(v) => set({ pdvId: v })} placeholder="Tous les PDV"
          options={options.pdvs.map((p) => ({ value: p.id, label: p.name }))} />
      )}
      {has('product') && (
        <Select icon={Package} value={f.productId} onChange={(v) => set({ productId: v })} placeholder="Tous les produits"
          options={(options?.products || []).map((p) => ({ value: p.id, label: p.name }))} />
      )}
      {has('employee') && (f.employee ? (
        <Chip icon={User} onClear={() => set({ employee: null, shiftId: '' })}>{f.employee.name}</Chip>
      ) : (
        <Picker icon={User} placeholder="Opérateur" search={(q) => api(`/extractions/employees${qs({ q })}`)}
          onPick={(e) => set({ employee: e, shiftId: '' })}
          render={(e) => (
            <span><span className="strong">{e.name}</span>
              <span className="muted" style={{ display: 'block', fontSize: 12.5 }}>{[e.roleLabel, e.stationName].filter(Boolean).join(' · ')}</span>
            </span>
          )} />
      ))}
      {has('shift') && f.employee && (shifts.data || []).length > 0 && (
        <Select icon={Timer} value={f.shiftId} onChange={(v) => set({ shiftId: v })} placeholder="Tous ses postes"
          options={shifts.data.map((s) => ({ value: s.id, label: `${s.label} · ${s.stationName}` }))} />
      )}
      <button type="button" className="link-more" style={{ marginLeft: 'auto', color: 'var(--primary)', fontWeight: 550 }}
        onClick={() => setF({ ...EMPTY, stationId: scoped || '' })}>Réinitialiser</button>
      {skipped.length > 0 && (
        <span className="muted" style={{ flexBasis: '100%', fontSize: 12.5 }}>
          Non applicable à cette extraction (ignoré) : {skipped.join(', ')}
        </span>
      )}
    </div>
  );
}

function ResultTable({ data }) {
  const cols = data.columns;
  const hasTotals = cols.some((c) => c.sum);
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>{cols.map((c) => <th key={c.key} className={isNum(c.type) ? 'r' : undefined}>{c.label}</th>)}</tr>
        </thead>
        <tbody>
          {data.items.map((row, i) => (
            <tr key={i}>
              {cols.map((c) => (
                <td key={c.key} className={isNum(c.type) ? 'r num' : c.type === 'DATETIME' ? 'num muted' : undefined}
                  style={c.type === 'TEXT' ? { maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } : { whiteSpace: 'nowrap' }}
                  title={c.type === 'TEXT' ? row[c.key] || '' : undefined}>
                  {cell(row[c.key], c.type)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        {hasTotals && (
          <tfoot>
            <tr>
              {cols.map((c, i) => (
                <td key={c.key} className={isNum(c.type) ? 'r num strong' : 'strong'} style={{ borderTop: '2px solid var(--border)', background: 'var(--surface-2)', whiteSpace: 'nowrap' }}>
                  {i === 0 ? `Total · ${num(data.total)} ligne(s)` : c.sum ? cell(data.totals[c.key] ?? 0, c.type) : ''}
                </td>
              ))}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}

export default function Extractions() {
  const notify = useToast();
  const catalog = useApi('/extractions');
  const options = useApi('/extractions/options');
  const [code, setCode] = useState('historique-carte');
  const [f, setF] = useState(EMPTY);
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(null);
  const scoped = catalog.data?.scopedStationId || null;

  useEffect(() => {
    if (scoped) setF((cur) => ({ ...cur, stationId: String(scoped) }));
  }, [scoped]);

  const all = useMemo(() => (catalog.data?.sections || []).flatMap((s) => s.items), [catalog.data]);
  const current = all.find((x) => x.code === code) || all[0];
  const supported = current?.filters || [];
  const query = current ? params(f, supported) : null;
  const queryKey = JSON.stringify(query);

  useEffect(() => setPage(0), [code, queryKey]);
  const result = useApi(current ? `/extractions/${current.code}${qs({ ...query, page, size: 50 })}` : null);

  const exportFile = async (format) => {
    setBusy(format);
    try {
      await download(`/extractions/${current.code}/export${qs({ ...query, format })}`, `zener-${current.code}-${isoDay()}.${format}`);
      notify(`${format === 'csv' ? 'CSV' : 'Fichier Excel'} téléchargé · ${current.label}`);
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <PageHeader
        title="Extractions"
        subtitle="Module Gestionnaire · historique des cartes et des clients, ventes, opérateurs, ordres de travail et audit, à l'écran ou en CSV / Excel"
        actions={current && (
          <>
            <Button icon={FileText} onClick={() => exportFile('csv')} disabled={!!busy || !result.data?.total}>{busy === 'csv' ? 'Export…' : 'CSV'}</Button>
            <Button variant="primary" icon={FileSpreadsheet} onClick={() => exportFile('xlsx')} disabled={!!busy || !result.data?.total}>
              {busy === 'xlsx' ? 'Export…' : 'Excel'}
            </Button>
          </>
        )}
      />

      {/* fenêtre étroite : liste déroulante à la place de la colonne de gauche */}
      <label className="select ex-picker mb20" style={{ height: 42, width: '100%' }}>
        <Database size={16} strokeWidth={1.8} />
        <select value={current?.code || ''} onChange={(e) => setCode(e.target.value)} style={{ maxWidth: 'none', flex: 1 }}>
          {(catalog.data?.sections || []).map((s) => (
            <optgroup key={s.code} label={s.label}>
              {s.items.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
            </optgroup>
          ))}
        </select>
        <ChevronDown size={15} className="chev" />
      </label>

      <div className="grid split ex-layout" style={{ gridTemplateColumns: '290px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad ex-list" style={{ padding: 14 }}>
          <Async state={catalog}>
            {(data) => data.sections.map((s) => (
              <div key={s.code} style={{ marginBottom: 10 }}>
                <div className="ex-section">{s.label}</div>
                {s.items.map((x) => (
                  <button key={x.code} type="button" onClick={() => setCode(x.code)} className="row"
                    style={{
                      width: '100%', textAlign: 'left', gap: 10, padding: '9px 10px', borderRadius: 8, border: 0, marginBottom: 2,
                      background: x.code === current?.code ? 'var(--primary-50)' : 'transparent',
                      color: x.code === current?.code ? 'var(--primary)' : 'var(--text-2)', fontWeight: x.code === current?.code ? 600 : 450,
                    }}>
                    <Database size={15} strokeWidth={1.8} style={{ flexShrink: 0, opacity: 0.7 }} />
                    <span style={{ fontSize: 13.5 }}>{x.label}</span>
                  </button>
                ))}
              </div>
            ))}
          </Async>
        </Card>

        <Card>
          {current && (
            <>
              <div className="card-head" style={{ paddingBottom: 14 }}>
                <div>
                  <div className="card-title">{current.label}</div>
                  <div className="card-sub">{current.description}</div>
                </div>
                <Badge tone="blue"><Download size={13} /> {result.data ? `${num(result.data.total)} ligne(s)` : '…'}</Badge>
              </div>
              <Filters supported={supported} f={f} setF={setF} options={options.data} scoped={scoped} />
              <Async state={result} minHeight={200}>
                {(data) => (data.items.length === 0 ? <Empty>Aucune donnée pour ces filtres</Empty> : (
                  <>
                    <ResultTable data={data} />
                    <Pager data={data} page={page} onPage={setPage} label="lignes" />
                  </>
                ))}
              </Async>
            </>
          )}
        </Card>
      </div>
    </>
  );
}
