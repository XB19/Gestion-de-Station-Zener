import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Truck, Plus, Droplet, ClipboardList, PackageCheck, Navigation, MapPin, Phone, Check, X, Send, UserCheck, Route,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, Select, KV, Async, Empty, Pager, IcoBox, Avatar, Person } from '../components/ui.jsx';
import GeoMap from '../components/GeoMap.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useInterval, useLiveReload } from '../api/hooks.js';
import { useLiveEvent } from '../api/live.jsx';
import { useSession } from '../api/session.jsx';
import { fileUrl, post, qs } from '../api/client.js';
import { ago, compact, dateTime, isoDay, money, num, PAYMENT_LABELS, time } from '../lib/format.js';

const TABS = [
  { label: 'En cours', status: 'EN_ROUTE' },
  { label: 'Demandées', status: 'DEMANDEE' },
  { label: 'Validées', status: 'VALIDEE' },
  { label: 'Livrées', status: 'LIVREE' },
  { label: 'Toutes', status: '' },
];
const STATION_COLORS = { EN_LIGNE: '#3f9a2c', INSTABLE: '#f29e1f', HORS_LIGNE: '#e5484d' };

/** Date et heure locales dans deux heures, format de champ datetime-local (AAAA-MM-JJTHH:00). */
function inTwoHours() {
  const d = new Date(Date.now() + 2 * 3600 * 1000);
  return `${isoDay(d)}T${String(d.getHours()).padStart(2, '0')}:00`;
}

function OrderModal({ min, onClose, onSaved }) {
  const notify = useToast();
  const holders = useApi('/holders?kind=ENTREPRISE');
  const stations = useApi('/stations');
  const [f, setF] = useState({
    holderId: '', cardNumber: '', productCode: 'GASOIL', quantity: Math.max(min, 1000), address: '', latitude: '', longitude: '',
    sourceStationId: '', paymentMethod: 'VIREMENT', requestedFor: inTwoHours(), note: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Nouvelle commande Zen Mobility" submitLabel="Créer la commande" wide onClose={onClose} onSubmit={async () => {
      const o = await post('/mobility/orders', {
        holderId: f.holderId ? Number(f.holderId) : null, cardNumber: f.paymentMethod === 'CARTE' ? f.cardNumber : null,
        productCode: f.productCode, quantity: Number(f.quantity), address: f.address,
        latitude: f.latitude === '' ? null : Number(f.latitude), longitude: f.longitude === '' ? null : Number(f.longitude),
        sourceStationId: f.sourceStationId ? Number(f.sourceStationId) : null, paymentMethod: f.paymentMethod,
        requestedFor: f.requestedFor ? `${f.requestedFor}:00` : null, note: f.note || null,
      });
      notify(`Commande ${o.ref} créée · ${money(o.amount)}`);
      onSaved(o);
    }}>
      <div className="form-grid">
        <Field label="Paiement">
          <SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={[
            { value: 'VIREMENT', label: 'Virement' }, { value: 'CARTE', label: 'Carte ZENER du client' },
            { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'ESPECES', label: 'Espèces à la livraison' }]} />
        </Field>
        {f.paymentMethod === 'CARTE' ? (
          <Field label="N° de carte (le client est déduit)"><TextInput value={f.cardNumber} onChange={set('cardNumber')} required /></Field>
        ) : (
          <Field label="Client">
            <SelectInput value={f.holderId} onChange={set('holderId')} placeholder="Choisir…" required
              options={(holders.data || []).map((h) => ({ value: h.id, label: h.name }))} />
          </Field>
        )}
        <Field label="Carburant">
          <SelectInput value={f.productCode} onChange={set('productCode')} options={[{ value: 'GASOIL', label: 'Gasoil' }, { value: 'SUPER', label: 'Super' }, { value: 'PETROLE', label: 'Pétrole' }]} />
        </Field>
        <Field label={`Quantité (${num(min)} L minimum)`}><TextInput type="number" min={min} step="100" value={f.quantity} onChange={set('quantity')} required /></Field>
        <Field label="Adresse de livraison" full><TextInput value={f.address} onChange={set('address')} required placeholder="Dépôt, chantier, entrepôt…" /></Field>
        <Field label="Latitude (facultatif)"><TextInput type="number" step="0.0001" value={f.latitude} onChange={set('latitude')} placeholder="6.1372" /></Field>
        <Field label="Longitude (facultatif)"><TextInput type="number" step="0.0001" value={f.longitude} onChange={set('longitude')} placeholder="1.2861" /></Field>
        <Field label="Station source">
          <SelectInput value={f.sourceStationId} onChange={set('sourceStationId')} placeholder="À choisir à l’affectation"
            options={(stations.data || []).map((s) => ({ value: s.id, label: s.shortName }))} />
        </Field>
        <Field label="Livraison souhaitée"><TextInput type="datetime-local" value={f.requestedFor} onChange={set('requestedFor')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function AssignModal({ order, agents, onClose, onSaved }) {
  const notify = useToast();
  const stations = useApi('/stations');
  const free = agents.filter((a) => !a.currentDelivery);
  const [agentId, setAgentId] = useState(String(free[0]?.id || agents[0]?.id || ''));
  const [vehicle, setVehicle] = useState('Citerne TG ');
  const [source, setSource] = useState(String(order.sourceStationId || ''));
  return (
    <FormModal title={`Affecter ${order.ref}`} submitLabel="Affecter le livreur" onClose={onClose} onSubmit={async () => {
      await post(`/mobility/orders/${order.id}/assign`, { agentId: Number(agentId), vehicle, sourceStationId: source ? Number(source) : null });
      notify('Livreur affecté');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Livreur" full>
          <SelectInput value={agentId} onChange={setAgentId}
            options={agents.map((a) => ({ value: a.id, label: `${a.name}${a.currentDelivery ? ` · en livraison (${a.currentDelivery})` : ' · disponible'}` }))} />
        </Field>
        <Field label="Véhicule"><TextInput value={vehicle} onChange={setVehicle} /></Field>
        <Field label="Station source">
          <SelectInput value={source} onChange={setSource} placeholder="Choisir…" required options={(stations.data || []).map((s) => ({ value: s.id, label: s.shortName }))} />
        </Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>Le stock de la cuve de la station source est vérifié.</div>
    </FormModal>
  );
}

function DeliverModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [q, setQ] = useState(String(order.quantity));
  return (
    <FormModal title={`Livraison ${order.ref}`} submitLabel="Confirmer la livraison" onClose={onClose} onSubmit={async () => {
      const o = await post(`/mobility/orders/${order.id}/deliver`, { deliveredQuantity: Number(q) });
      notify(`Livré · ${num(o.deliveredQuantity)} L${o.paymentMethod === 'CARTE' ? ' · carte débitée' : ''}`);
      onSaved();
    }}>
      <Field label="Quantité livrée (L)" hint="Sortie de la cuve de la station source ; la carte du client est débitée si elle paie">
        <TextInput type="number" min="1" step="0.1" value={q} onChange={setQ} required />
      </Field>
    </FormModal>
  );
}

function CancelModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [reason, setReason] = useState('');
  return (
    <FormModal title={`Annuler ${order.ref}`} danger submitLabel="Annuler la commande" onClose={onClose} onSubmit={async () => {
      await post(`/mobility/orders/${order.id}/cancel`, { reason });
      notify('Commande annulée');
      onSaved();
    }}>
      <Field label="Motif"><TextInput value={reason} onChange={setReason} required placeholder="Client injoignable, doublon…" /></Field>
    </FormModal>
  );
}

/** Détail d'une commande et actions selon son état. */
function OrderDetail({ id, agents, onChanged, onTrack }) {
  const notify = useToast();
  const { can, user } = useSession();
  const o = useApi(`/mobility/orders/${id}`);
  const [modal, setModal] = useState(null);
  useLiveReload(o.reload, ['delivery'], 2000);
  useEffect(() => {
    if (o.data) onTrack(o.data);
  }, [o.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const manage = can('LIVRAISONS');
  const mine = o.data && o.data.agentId === user.id;
  const act = async (path, message) => {
    try {
      await post(`/mobility/orders/${id}/${path}`);
      notify(message);
      o.reload();
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const done = () => {
    o.reload();
    onChanged();
  };
  return (
    <Async state={o} minHeight={200}>
      {(d) => (
        <Card className="card-pad">
          <div className="row between">
            <div>
              <div className="card-title">{d.ref} · {d.holderName}</div>
              <div className="card-sub">{num(d.quantity)} L {d.productName} · {money(d.amount)} · {PAYMENT_LABELS[d.paymentMethod]}{d.cardMasked ? ` ${d.cardMasked}` : ''}</div>
            </div>
            <StatusBadge status={d.statusLabel} />
          </div>
          <div className="grid g2" style={{ gap: 20, marginTop: 10 }}>
            <div>
              <KV k="Adresse" v={d.address} />
              <KV k="Souhaitée" v={d.requestedFor ? dateTime(d.requestedFor) : '—'} />
              <KV k="Station source" v={d.sourceStationName || '—'} />
              <KV k="Commande" v={`${dateTime(d.createdAt)}${d.createdByName ? ` · ${d.createdByName}` : ''}`} />
            </div>
            <div>
              <KV k="Livreur" v={d.agentName ? <span className="row" style={{ gap: 6 }}><Person name={d.agentName} id={d.agentId} photo={fileUrl(d.agentPhotoUrl)} size={24} />{d.agentPhone && <><Phone size={13} /> {d.agentPhone}</>}</span> : '—'} />
              <KV k="Véhicule" v={d.vehicle || '—'} />
              <KV k="Dernière position" v={d.lastPingAt ? `${ago(d.lastPingAt)}` : '—'} />
              <KV k={d.status === 'LIVREE' ? 'Livrée' : 'Départ'} v={d.status === 'LIVREE' ? `${dateTime(d.deliveredAt)} · ${num(d.deliveredQuantity)} L` : d.departedAt ? dateTime(d.departedAt) : '—'} />
            </div>
          </div>
          {d.cancelReason && <div className="form-error">Annulée : {d.cancelReason}</div>}
          {d.note && <div className="muted" style={{ fontSize: 13, marginTop: 8 }}>{d.note}</div>}
          <div className="row" style={{ gap: 8, marginTop: 16, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
            {manage && d.status === 'DEMANDEE' && <Button icon={Check} onClick={() => act('validate', 'Commande validée')}>Valider</Button>}
            {manage && ['DEMANDEE', 'VALIDEE'].includes(d.status) && <Button icon={UserCheck} onClick={() => setModal('assign')}>Affecter un livreur</Button>}
            {(manage || mine) && d.status === 'VALIDEE' && d.agentId && <Button variant="primary" icon={Send} onClick={() => act('depart', 'Livraison en route')}>Départ</Button>}
            {(manage || mine) && d.status === 'EN_ROUTE' && <Button variant="success" icon={PackageCheck} onClick={() => setModal('deliver')}>Livrée</Button>}
            {manage && !['LIVREE', 'ANNULEE'].includes(d.status) && <Button variant="danger" icon={X} onClick={() => setModal('cancel')}>Annuler</Button>}
          </div>
          {modal === 'assign' && <AssignModal order={d} agents={agents} onClose={() => setModal(null)} onSaved={done} />}
          {modal === 'deliver' && <DeliverModal order={d} onClose={() => setModal(null)} onSaved={done} />}
          {modal === 'cancel' && <CancelModal order={d} onClose={() => setModal(null)} onSaved={done} />}
        </Card>
      )}
    </Async>
  );
}

export default function Mobilite() {
  const { can } = useSession();
  const [params] = useSearchParams();
  const manage = can('LIVRAISONS');
  const [tab, setTab] = useState(manage ? 'En cours' : 'Mes livraisons');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [track, setTrack] = useState(null);
  const [creating, setCreating] = useState(false);
  const [, tick] = useState(0);
  const status = TABS.find((t) => t.label === tab)?.status;

  const summary = useApi(manage ? '/mobility/summary' : null);
  const orders = useApi(manage ? `/mobility/orders${qs({ status, page, size: 12 })}` : null);
  const mine = useApi(!manage ? '/mobility/my-deliveries' : null);
  const live = useApi(manage || can('STATIONS_GERER', 'STATION_EXPLOITER') ? '/tracking/live' : null);
  const stations = useApi('/stations/map');
  const reload = () => {
    summary.reload();
    orders.reload();
    mine.reload();
  };
  useLiveReload(reload, ['delivery'], 2500);
  useInterval(() => tick((n) => n + 1), 15_000);

  // positions des livreurs mises à jour en direct
  useLiveEvent(['location'], (_, e) => {
    if (!live.data || e?.subject !== 'LIVREUR') return;
    const agents = live.data.agents.some((a) => a.id === e.id)
      ? live.data.agents.map((a) => (a.id === e.id ? { ...a, latitude: e.latitude, longitude: e.longitude, heading: e.heading, speedKmh: e.speedKmh, at: e.at, ageSeconds: 0, stale: false } : a))
      : live.data.agents;
    if (!live.data.agents.some((a) => a.id === e.id)) live.reload();
    live.setData({ ...live.data, agents });
    if (track && e.deliveryId === track.id) {
      setTrack((t) => ({ ...t, track: [...(t.track || []), { latitude: e.latitude, longitude: e.longitude, at: e.at }] }));
    }
  });

  const list = manage ? orders.data?.items : mine.data;
  useEffect(() => {
    if (!selected && list?.length) setSelected(list[0].id);
  }, [list, selected]);

  const markers = useMemo(() => {
    const m = [];
    const lome = (s) => s.latitude < 6.35 && s.longitude > 1.05 && s.longitude < 1.35;
    (stations.data || []).filter((s) => s.latitude != null && (lome(s) || s.id === track?.sourceStationId)).forEach((s) =>
      m.push({ id: `s${s.id}`, lat: Number(s.latitude), lon: Number(s.longitude), color: STATION_COLORS[s.status] || '#94a3b8', label: s.shortName }));
    (live.data?.agents || []).forEach((a) =>
      m.push({ id: `a${a.id}`, lat: Number(a.latitude), lon: Number(a.longitude), color: a.stale ? '#94a3b8' : '#1b66b8', shape: 'truck', heading: Number(a.heading),
        label: `${a.name.split(' ')[0]}${a.deliveryRef ? ` · ${a.deliveryRef}` : ''}`, faded: a.stale }));
    (live.data?.mobileStations || []).forEach((s) =>
      m.push({ id: `m${s.id}`, lat: Number(s.latitude), lon: Number(s.longitude), color: '#8b5cf6', shape: 'truck', label: s.shortName }));
    if (track?.latitude != null) m.push({ id: 'dest', lat: Number(track.latitude), lon: Number(track.longitude), color: '#e5484d', shape: 'flag', label: track.holderName });
    return m;
  }, [stations.data, live.data, track]);
  const lines = track?.track?.length ? [{ points: track.track.map((p) => [Number(p.latitude), Number(p.longitude)]), color: '#1b66b8' }] : [];
  if (track?.status === 'EN_ROUTE' && track.track?.length && track.latitude != null) {
    const last = track.track[track.track.length - 1];
    lines.push({ points: [[Number(last.latitude), Number(last.longitude)], [Number(track.latitude), Number(track.longitude)]], color: '#e5484d', dashed: true, width: 1.6 });
  }

  const s = summary.data;
  return (
    <>
      <PageHeader
        title="Zen Mobility"
        subtitle={`Livraison de carburant sur site (${num(s?.minQuantity || 1000)} L minimum) · suivi GPS du livreur en temps réel`}
        actions={manage && <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>Nouvelle commande</Button>}
      />

      {manage && (
        <div className="grid g4 mb20">
          <Kpi icon={ClipboardList} tone="orange" label="À traiter" value={s ? s.requested + s.validated : '…'} foot={s ? `${s.requested} demandées · ${s.validated} validées` : ''} />
          <Kpi icon={Truck} label="En route" value={s ? s.onTheWay : '…'} foot={s ? `${(s.agents || []).filter((a) => !a.currentDelivery).length} livreur(s) disponible(s)` : ''} />
          <Kpi icon={Droplet} tone="green" label="Livré ce mois" value={s ? num(s.litersMonth) : '…'} unit="L" foot={s ? `${s.deliveredMonth} livraisons` : ''} />
          <Kpi icon={PackageCheck} tone="navy" label="Chiffre du mois" value={s ? compact(s.amountMonth) : '…'} unit="FCFA" foot="livraisons effectuées" />
        </div>
      )}

      <div className="grid split" style={{ gridTemplateColumns: '420px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card>
          <div className="card-head" style={{ paddingBottom: 12 }}>
            <div className="card-title">{manage ? 'Commandes' : 'Mes livraisons'}</div>
          </div>
          {manage && (
            <div style={{ padding: '0 16px 12px' }}>
              <Select value={tab} onChange={(v) => { setTab(v); setPage(0); setSelected(null); }} options={TABS.map((t) => t.label)} />
            </div>
          )}
          {(manage ? orders : mine).data && !list?.length && <Empty>Aucune commande</Empty>}
          <div style={{ padding: '0 12px 8px' }}>
            {(list || []).map((o) => {
              const active = o.id === selected;
              return (
                <button key={o.id} onClick={() => setSelected(o.id)} className="row"
                  style={{ width: '100%', gap: 12, textAlign: 'left', padding: '12px 12px', borderRadius: 10, marginBottom: 6,
                    background: active ? '#f5f9fe' : 'transparent', border: active ? '1px solid #bcd3ee' : '1px solid transparent' }}>
                  <IcoBox icon={o.status === 'EN_ROUTE' ? Navigation : o.status === 'LIVREE' ? PackageCheck : Truck} tone={o.status === 'EN_ROUTE' ? 'blue' : o.status === 'LIVREE' ? 'green' : 'orange'} size={34} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="strong" style={{ fontSize: 14 }}>{o.holderName}</div>
                    <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>{o.ref} · {num(o.quantity)} L {o.productName} · {o.agentName || 'non affectée'}</div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <StatusBadge status={o.statusLabel} />
                    <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>{ago(o.createdAt)}</div>
                  </div>
                </button>
              );
            })}
          </div>
          {manage && orders.data && <Pager data={orders.data} page={page} onPage={setPage} label="commandes" />}
        </Card>

        <div className="stack">
          <Card>
            <div className="card-head" style={{ paddingBottom: 12 }}>
              <div>
                <div className="card-title">Suivi en direct</div>
                <div className="card-sub">
                  {live.data ? `${live.data.agents.length} livreur(s) suivi(s) · position envoyée par le téléphone` : 'Livreurs et stations mobiles'}
                </div>
              </div>
              <span className="row" style={{ gap: 14 }}>
                <span className="legend"><i style={{ background: '#1b66b8', borderRadius: '50%' }} /> Livreur</span>
                <span className="legend"><i style={{ background: '#e5484d' }} /> Destination</span>
                <span className="live-pill" style={{ height: 26 }}><span className="pulse" /> Live</span>
              </span>
            </div>
            <div style={{ padding: '0 16px 16px' }}>
              <GeoMap markers={markers} lines={lines} height={400} selected={track ? `a${track.agentId}` : null}
                onSelect={(id) => {
                  const a = live.data?.agents.find((x) => `a${x.id}` === id);
                  if (a?.deliveryId) setSelected(a.deliveryId);
                }} />
              <div className="row" style={{ gap: 18, marginTop: 10, flexWrap: 'wrap' }}>
                {(live.data?.agents || []).map((a) => (
                  <span key={a.id} className="row muted" style={{ gap: 6, fontSize: 13 }}>
                    <Avatar initials={a.name.split(' ').map((w) => w[0]).join('').slice(0, 2)} size={24} photo={fileUrl(a.photoUrl)} />
                    <Truck size={14} color={a.stale ? 'var(--muted-2)' : 'var(--primary)'} />
                    <b style={{ color: 'var(--text)' }}>{a.name}</b>
                    {a.deliveryRef ? ` · ${a.deliveryRef}` : ' · disponible'} · {num(a.speedKmh)} km/h · {a.at ? time(a.at, true) : '—'}
                    {a.stale && <Badge tone="orange">Signal ancien</Badge>}
                  </span>
                ))}
                {live.data && !live.data.agents.length && <span className="muted">Aucun livreur n’a envoyé sa position depuis 24 h</span>}
              </div>
            </div>
          </Card>
          {selected ? (
            <OrderDetail key={selected} id={selected} agents={s?.agents || []} onChanged={reload} onTrack={setTrack} />
          ) : (
            <Card className="card-pad"><Empty><MapPin size={16} /> Sélectionnez une commande <Route size={16} /></Empty></Card>
          )}
        </div>
      </div>

      {creating && <OrderModal min={s?.minQuantity || 1000} onClose={() => setCreating(false)} onSaved={(o) => { reload(); setTab('Demandées'); setSelected(o.id); }} />}
    </>
  );
}

