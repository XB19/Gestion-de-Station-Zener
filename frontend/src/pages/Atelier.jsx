import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Wrench, Plus, Play, CheckCircle2, Banknote, Ban, Car, Trash2 } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { Modal, FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import CardPicker from '../components/CardPicker.jsx';
import { dateTime, fcfa, money, num } from '../lib/format.js';

const SERVICES = { VIDANGE: 'Vidange', GRAISSAGE: 'Graissage', FILTRES: 'Filtres', LAVAGE: 'Lavage', PNEUS: 'Pneus', BATTERIE: 'Batterie',
  DIAGNOSTIC: 'Diagnostic', AUTRE: 'Autre' };
const STATUS_TONE = { OUVERT: 'blue', EN_COURS: 'orange', TERMINE: 'green', PAYE: 'gray', ANNULE: 'red' };
const FILTERS = ['En cours', 'À encaisser', 'Payés', 'Tous'];
const FILTER_STATUS = { 'En cours': 'EN_COURS_TOUS', 'À encaisser': 'TERMINE', Payés: 'PAYE', Tous: undefined };

function ServicesPicker({ value, onChange }) {
  return (
    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
      {Object.entries(SERVICES).map(([k, l]) => {
        const on = value.includes(k);
        return (
          <button key={k} type="button" className={`badge ${on ? 'blue' : 'gray'}`} style={{ border: 0, height: 28, cursor: 'pointer' }}
            onClick={() => onChange(on ? value.filter((x) => x !== k) : [...value, k])}>{l}</button>
        );
      })}
    </div>
  );
}

function NewOrder({ stationId, stations, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ stationId: stationId ? String(stationId) : '', plate: '', brand: '', model: '', customerName: '', customerPhone: '',
    odometerKm: '', description: '', labourAmount: '', services: ['VIDANGE'] });
  const [card, setCard] = useState(null);
  const [known, setKnown] = useState(null);
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const lookup = async () => {
    if (f.plate.trim().length < 4) return;
    try {
      const v = await api(`/vehicles/lookup${qs({ plate: f.plate })}`);
      setKnown(v.known ? v : null);
      if (v.known) setF((x) => ({ ...x, brand: x.brand || v.brand || '', model: x.model || v.model || '', customerName: x.customerName || v.ownerName || '',
        customerPhone: x.customerPhone || v.ownerPhone || '' }));
    } catch {
      setKnown(null);
    }
  };
  return (
    <FormModal title="Nouvel ordre de travail" wide submitLabel="Ouvrir l'ordre" onClose={onClose} onSubmit={async () => {
      const o = await post('/work-orders', { ...f, stationId: Number(f.stationId), odometerKm: f.odometerKm ? Number(f.odometerKm) : null,
        labourAmount: f.labourAmount ? Number(f.labourAmount) : 0, cardNumber: card?.number || null });
      notify(`Ordre ${o.ref} ouvert`);
      onSaved(o);
    }}>
      <div className="form-grid">
        {!stationId && (
          <Field label="Station"><SelectInput value={f.stationId} onChange={set('stationId')} placeholder="Choisir…" required
            options={stations.map((s) => ({ value: String(s.id), label: s.name }))} /></Field>
        )}
        <Field label="Immatriculation" hint={known ? `Véhicule connu · dernier passage ${dateTime(known.orders?.[0]?.openedAt)} à ${num(known.odometerKm)} km` : 'Ex. TG 1234 AB'}>
          <TextInput value={f.plate} onChange={set('plate')} onBlur={lookup} required />
        </Field>
        <Field label="Marque"><TextInput value={f.brand} onChange={set('brand')} /></Field>
        <Field label="Modèle"><TextInput value={f.model} onChange={set('model')} /></Field>
        <Field label="Client"><TextInput value={f.customerName} onChange={set('customerName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.customerPhone} onChange={set('customerPhone')} /></Field>
        <Field label="Kilométrage"><TextInput type="number" min="0" value={f.odometerKm} onChange={set('odometerKm')} /></Field>
        <Field label="Main-d'œuvre (F)"><TextInput type="number" min="0" step="500" value={f.labourAmount} onChange={set('labourAmount')} /></Field>
        <Field label="Prestations" full><ServicesPicker value={f.services} onChange={set('services')} /></Field>
        <Field label="Carte Zener du client (facultatif)" full><CardPicker value={card} onChange={setCard} /></Field>
        <Field label="Travaux / remarques" full><TextInput value={f.description} onChange={set('description')} /></Field>
      </div>
    </FormModal>
  );
}

function OrderDetail({ id, onClose, onChanged }) {
  const notify = useToast();
  const o = useApi(`/work-orders/${id}`);
  const products = useApi('/products');
  const [lines, setLines] = useState(null);
  const [pay, setPay] = useState(false);
  const [cancel, setCancel] = useState(false);
  useEffect(() => {
    if (o.data && lines === null) setLines(o.data.lines.map((l) => ({ productCode: l.code, quantity: String(l.quantity) })));
  }, [o.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const act = async (path, msg) => {
    try {
      await post(`/work-orders/${id}/${path}`);
      notify(msg);
      o.reload();
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const saveLines = async () => {
    try {
      await put(`/work-orders/${id}/lines`, { items: lines.filter((l) => l.productCode && Number(l.quantity) > 0).map((l) => ({ ...l, quantity: Number(l.quantity) })) });
      notify('Produits enregistrés');
      setLines(null);
      o.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const usable = (products.data || []).filter((p) => ['LUBRIFIANT', 'ACCESSOIRE', 'GAZ'].includes(p.category) && p.active);
  return (
    <Modal title="Ordre de travail" wide onClose={onClose} footer={o.data && !['PAYE', 'ANNULE'].includes(o.data.status) && (
      <>
        <Button icon={Ban} onClick={() => setCancel(true)}>Annuler l'ordre</Button>
        {o.data.status === 'OUVERT' && <Button icon={Play} onClick={() => act('start', 'Travaux commencés')}>Commencer</Button>}
        {['OUVERT', 'EN_COURS'].includes(o.data.status) && <Button icon={CheckCircle2} onClick={() => act('finish', 'Travaux terminés')}>Terminer</Button>}
        {['EN_COURS', 'TERMINE'].includes(o.data.status) && <Button variant="primary" icon={Banknote} onClick={() => setPay(true)}>Encaisser</Button>}
      </>
    )}>
      <Async state={o}>
        {(w) => {
          const productsAmount = w.lines.reduce((s, l) => s + Number(l.quantity) * (l.unitPrice || 0), 0);
          const editable = !['PAYE', 'ANNULE'].includes(w.status);
          return (
            <>
              <div className="row between" style={{ flexWrap: 'wrap', rowGap: 8 }}>
                <div>
                  <div className="row" style={{ gap: 8 }}><Car size={18} color="var(--primary)" /><span className="strong" style={{ fontSize: 17 }}>{w.plate}</span>
                    <span className="muted">{[w.brand, w.model].filter(Boolean).join(' ')}</span></div>
                  <div className="muted" style={{ fontSize: 13, marginTop: 4 }}>{w.ref} · {w.stationName} · {[w.customerName, w.customerPhone].filter(Boolean).join(' · ')}</div>
                </div>
                <Badge tone={STATUS_TONE[w.status]}>{w.statusLabel}</Badge>
              </div>
              <div className="grid g4" style={{ gap: 10, marginTop: 14 }}>
                {[['Kilométrage', w.odometerKm ? `${num(w.odometerKm)} km` : '—'], ['Prestations', (w.services || []).map((s) => SERVICES[s]).join(', ') || '—'],
                  ['Technicien', w.technicianName || '—'], ['Prochaine vidange', w.nextServiceKm ? `${num(w.nextServiceKm)} km` : '—']].map(([k, v]) => (
                  <div key={k} style={{ background: 'var(--surface-2)', borderRadius: 9, padding: '10px 12px' }}>
                    <div className="muted" style={{ fontSize: 12.5 }}>{k}</div><div className="strong" style={{ marginTop: 4 }}>{v}</div>
                  </div>
                ))}
              </div>
              {w.description && <div className="muted" style={{ marginTop: 10 }}>{w.description}</div>}
              <div className="row between" style={{ marginTop: 16 }}>
                <div className="section-label" style={{ margin: 0 }}>Produits utilisés</div>
                {editable && lines && <Button size="sm" icon={Plus} onClick={() => setLines([...lines, { productCode: usable[0]?.code || '', quantity: '1' }])}>Produit</Button>}
              </div>
              {editable && lines ? (
                <div style={{ marginTop: 8 }}>
                  {lines.map((l, i) => (
                    <div key={i} className="row" style={{ gap: 8, marginBottom: 6 }}>
                      <label className="input" style={{ flex: 1 }}>
                        <select value={l.productCode} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, productCode: e.target.value } : x)))}>
                          {usable.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
                        </select>
                      </label>
                      <label className="input" style={{ width: 100 }}>
                        <input type="number" min="0.5" step="0.5" value={l.quantity} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} />
                      </label>
                      <Button size="sm" icon={Trash2} title="Retirer" onClick={() => setLines(lines.filter((_, j) => j !== i))} />
                    </div>
                  ))}
                  <Button size="sm" variant="primary" onClick={saveLines}>Enregistrer les produits</Button>
                </div>
              ) : (
                <div className="table-wrap"><table className="table">
                  <tbody>{w.lines.map((l) => (
                    <tr key={l.id}><td>{l.name}</td><td className="r num">{Number(l.quantity)}</td><td className="r num">{fcfa(l.unitPrice)} F</td>
                      <td className="r num strong">{fcfa(Number(l.quantity) * l.unitPrice)} F</td></tr>
                  ))}</tbody>
                </table></div>
              )}
              <div className="row between" style={{ marginTop: 14, background: 'var(--surface-2)', borderRadius: 9, padding: '12px 14px' }}>
                <span className="muted">Produits {money(productsAmount)} + main-d'œuvre {money(w.labourAmount)}</span>
                <span className="strong num" style={{ fontSize: 17 }}>{w.status === 'PAYE' ? `Payé ${money(w.paidTotal)}` : money(productsAmount + w.labourAmount)}</span>
              </div>
              {w.history.length > 0 && (
                <>
                  <div className="section-label" style={{ marginTop: 16 }}>Passages précédents</div>
                  {w.history.map((h) => (
                    <div key={h.id} className="muted" style={{ fontSize: 13 }}>{dateTime(h.openedAt)} · {h.stationName} · {h.odometerKm ? `${num(h.odometerKm)} km · ` : ''}
                      {(h.services || []).map((s) => SERVICES[s]).join(', ')}</div>
                  ))}
                </>
              )}
              {pay && <PayModal order={w} onClose={() => setPay(false)} onSaved={() => { o.reload(); onChanged(); }} />}
              {cancel && (
                <FormModal title={`Annuler ${w.ref}`} danger submitLabel="Annuler l'ordre" onClose={() => setCancel(false)} onSubmit={async () => {
                  await post(`/work-orders/${id}/cancel`, { reason: document.getElementById('wo-cancel').value });
                  o.reload();
                  onChanged();
                }}>
                  <Field label="Motif"><label className="input"><input id="wo-cancel" required /></label></Field>
                </FormModal>
              )}
            </>
          );
        }}
      </Async>
    </Modal>
  );
}

function PayModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [method, setMethod] = useState(order.cardId ? 'CARTE' : 'ESPECES');
  const [ref, setRef] = useState('');
  const [card, setCard] = useState(null);
  const [pin, setPin] = useState('');
  return (
    <FormModal title={`Encaisser ${order.ref}`} submitLabel="Encaisser" onClose={onClose} onSubmit={async () => {
      const r = await post(`/work-orders/${order.id}/pay`, { paymentMethod: method, paymentRef: ref || null, cardNumber: card?.number || null, pin: pin || null });
      notify(`Encaissé · ${money(r.paidTotal)}`);
      onSaved();
    }}>
      <Field label="Paiement"><SelectInput value={method} onChange={setMethod} options={[{ value: 'ESPECES', label: 'Espèces' },
        { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'CARTE', label: 'Carte Zener' }]} /></Field>
      {method === 'MOBILE_MONEY' && <div style={{ marginTop: 12 }}><Field label="Référence"><TextInput value={ref} onChange={setRef} required /></Field></div>}
      {method === 'CARTE' && (
        <div className="form-grid" style={{ marginTop: 12 }}>
          <Field label="Carte" full hint={order.cardNumberFormatted ? `Par défaut : ${order.cardNumberFormatted}` : undefined}><CardPicker value={card} onChange={setCard} /></Field>
          <Field label="Code PIN (si demandé)"><TextInput type="password" value={pin} onChange={setPin} /></Field>
        </div>
      )}
    </FormModal>
  );
}

export default function Atelier() {
  const { user, can } = useSession();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState(FILTERS[0]);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const stations = useApi(user?.stationBound ? null : '/stations');
  const summary = useApi('/work-orders/summary');
  const list = useApi(`/work-orders${qs({ status: FILTER_STATUS[filter], q, page })}`);
  useLiveReload(() => { summary.reload(); list.reload(); }, ['operation', 'notification'], 3000);
  const s = summary.data || {};
  const reload = () => { summary.reload(); list.reload(); };
  return (
    <>
      <PageHeader title="Atelier" subtitle="Ordres de travail sur les véhicules : vidange, graissage, filtres, lavage… Les produits utilisés sortent du stock à l'encaissement."
        actions={can('ATELIER') && <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>Nouvel ordre</Button>} />
      <div className="grid g4">
        <Kpi icon={Wrench} label="Ouverts" value={num(s.open)} foot="en attente de travaux" />
        <Kpi icon={Play} tone="orange" label="En cours" value={num(s.inProgress)} foot="véhicules à l'atelier" />
        <Kpi icon={Banknote} tone="green" label="À encaisser" value={num(s.toPay)} foot={`${num(s.paidToday)} payé(s) aujourd'hui`} />
        <Kpi icon={Banknote} tone="navy" label="Atelier ce mois" value={fcfa(s.revenueMonth)} unit="F" foot={`${num(s.paidMonth)} ordre(s) payé(s)`} />
      </div>
      <Card style={{ marginTop: 16 }}>
        <div className="card-head" style={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 8 }}>
          <Segmented items={FILTERS} value={filter} onChange={(v) => { setFilter(v); setPage(0); }} />
          <label className="input" style={{ height: 36, width: 260 }}>
            <input placeholder="Immatriculation, client, n° d'ordre" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          </label>
        </div>
        <Async state={list}>
          {(data) => (!data.items.length ? <Empty>Aucun ordre de travail</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Ordre</th><th>Véhicule</th><th>Client</th><th>Station</th><th>Prestations</th><th>Technicien</th><th className="r">Montant</th><th>État</th></tr></thead>
                <tbody>
                  {data.items.map((w) => (
                    <tr key={w.id} onClick={() => setSelected(w.id)} style={{ cursor: 'pointer' }}>
                      <td><div className="num strong">{w.ref}</div><div className="muted" style={{ fontSize: 12 }}>{dateTime(w.openedAt)}</div></td>
                      <td><div className="strong">{w.plate}</div><div className="muted" style={{ fontSize: 12 }}>{[w.brand, w.model].filter(Boolean).join(' ')}{w.odometerKm ? ` · ${num(w.odometerKm)} km` : ''}</div></td>
                      <td>{w.customerName || '—'}</td>
                      <td>{w.stationName}</td>
                      <td>{(w.services || []).map((x) => SERVICES[x]).join(', ') || '—'}</td>
                      <td>{w.technicianName || '—'}</td>
                      <td className="r num strong">{w.status === 'PAYE' ? fcfa(w.paidTotal) : fcfa(Number(w.productsAmount) + w.labourAmount)}</td>
                      <td><Badge tone={STATUS_TONE[w.status]}>{w.statusLabel}</Badge></td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="ordres" />
            </>
          ))}
        </Async>
      </Card>
      {creating && <NewOrder stationId={user?.stationBound ? user.stationId : null} stations={stations.data || []} onClose={() => setCreating(false)}
        onSaved={(o) => { setCreating(false); reload(); setSelected(o.id); }} />}
      {selected && <OrderDetail id={selected} onClose={() => { setSelected(null); setParams({}); }} onChanged={reload} />}
    </>
  );
}
