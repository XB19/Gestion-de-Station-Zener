import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Truck, PackageCheck, Plus, Pencil, Ban, Send, Tags, CalendarClock, Factory, Fuel } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, fcfa, money, num, signed } from '../lib/format.js';

const ORDER = {
  BROUILLON: ['Brouillon', 'gray'], COMMANDEE: ['Commandée', 'blue'], EXPEDIEE: ['Expédiée', 'orange'],
  LIVREE: ['Livrée', 'green'], ANNULEE: ['Annulée', 'red'],
};
const PRICE = { PLANIFIE: ['Planifié', 'orange'], APPLIQUE: ['Appliqué', 'green'], ANNULE: ['Annulé', 'gray'] };
const SUPPLIER_KINDS = [
  { value: 'CARBURANT', label: 'Carburant' }, { value: 'BOUTIQUE', label: 'Boutique' }, { value: 'GAZ', label: 'Gaz' }, { value: 'AUTRE', label: 'Autre' },
];
const PROFILES = [
  { value: 'GRAND_PUBLIC', label: 'Grand public' }, { value: 'EMPLOYE_STATION', label: 'Employé station' },
  { value: 'EMPLOYE_GROUPE', label: 'Employé groupe' }, { value: 'PARTENAIRE', label: 'Partenaire' },
];
const PROFILE_LABEL = Object.fromEntries(PROFILES.map((p) => [p.value, p.label]));
const ORDER_FILTERS = ['En cours', 'Livrées', 'Annulées', 'Toutes'];
const iso = (local) => (local ? new Date(local).toISOString() : null);
const qty = (v) => `${num(v)} L`;

function OrderModal({ suppliers, stations, products, prefill, onClose, onSaved }) {
  const notify = useToast();
  const fuels = products.filter((p) => p.category === 'CARBURANT');
  const [f, setF] = useState({
    supplierId: String(suppliers.find((s) => s.kind === 'CARBURANT')?.id || ''), stationId: String(prefill?.station || stations[0]?.id || ''),
    productCode: prefill?.product || fuels[0]?.code || '', quantity: prefill?.quantity || '', unitPrice: '', expectedAt: '', sellerName: '', sellerPhone: '',
    note: prefill ? 'Commande suite à l’alerte de niveau de cuve' : '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const amount = Math.round((Number(f.quantity) || 0) * (Number(f.unitPrice) || 0));
  return (
    <FormModal title="Commande de carburant" submitLabel="Commander" wide onClose={onClose} onSubmit={async () => {
      const o = await post('/supplier-orders', {
        supplierId: Number(f.supplierId), stationId: Number(f.stationId), productCode: f.productCode, quantity: Number(f.quantity),
        unitPrice: Number(f.unitPrice), expectedAt: iso(f.expectedAt), sellerName: f.sellerName || null, sellerPhone: f.sellerPhone || null, note: f.note || null,
      });
      notify(`Commande ${o.ref} passée · ${money(o.amount)}`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Fournisseur">
          <SelectInput value={f.supplierId} onChange={set('supplierId')} required options={suppliers.filter((s) => s.active).map((s) => ({ value: String(s.id), label: s.name }))} />
        </Field>
        <Field label="Station">
          <SelectInput value={f.stationId} onChange={set('stationId')} required options={stations.map((s) => ({ value: String(s.id), label: s.name }))} />
        </Field>
        <Field label="Carburant" hint="Livré dans la cuve de ce carburant">
          <SelectInput value={f.productCode} onChange={set('productCode')} options={fuels.map((p) => ({ value: p.code, label: p.name }))} />
        </Field>
        <Field label="Quantité (L)"><TextInput type="number" min="1" step="500" value={f.quantity} onChange={set('quantity')} required /></Field>
        <Field label="Prix d'achat (F / L)" hint={amount ? `Montant : ${money(amount)}` : undefined}>
          <TextInput type="number" min="0" value={f.unitPrice} onChange={set('unitPrice')} required />
        </Field>
        <Field label="Livraison prévue"><TextInput type="datetime-local" value={f.expectedAt} onChange={set('expectedAt')} /></Field>
        <Field label="Commercial du fournisseur"><TextInput value={f.sellerName} onChange={set('sellerName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.sellerPhone} onChange={set('sellerPhone')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function ShipModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ truckPlate: order.truckPlate || '', driverName: order.driverName || '', driverPhone: order.driverPhone || '', expectedAt: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Expédition · ${order.ref}`} submitLabel="Marquer expédiée" onClose={onClose} onSubmit={async () => {
      await post(`/supplier-orders/${order.id}/ship`, { ...f, expectedAt: iso(f.expectedAt) });
      notify('Commande expédiée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Camion (immatriculation)"><TextInput value={f.truckPlate} onChange={set('truckPlate')} /></Field>
        <Field label="Arrivée prévue"><TextInput type="datetime-local" value={f.expectedAt} onChange={set('expectedAt')} /></Field>
        <Field label="Chauffeur"><TextInput value={f.driverName} onChange={set('driverName')} /></Field>
        <Field label="Téléphone du chauffeur"><TextInput value={f.driverPhone} onChange={set('driverPhone')} /></Field>
      </div>
    </FormModal>
  );
}

function ReceiveModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ receivedVolume: String(order.quantity), ticketNumber: '', ticketDensity: '', temperature: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const gap = (Number(f.receivedVolume) || 0) - Number(order.quantity);
  return (
    <FormModal title={`Réception · ${order.ref}`} submitLabel="Réceptionner en cuve" onClose={onClose} onSubmit={async () => {
      await post(`/supplier-orders/${order.id}/receive`, {
        receivedVolume: Number(f.receivedVolume), ticketNumber: f.ticketNumber || null, ticketDensity: f.ticketDensity ? Number(f.ticketDensity) : null,
        temperature: f.temperature ? Number(f.temperature) : null, note: f.note || null,
      });
      notify(`Commande livrée · ${qty(f.receivedVolume)} en cuve ${order.tankName || ''}`);
      onSaved();
    }}>
      <div className="muted" style={{ fontSize: 13, marginBottom: 12 }}>
        {order.stationName} · {order.productName} · cuve {order.tankName} · commandé {qty(order.quantity)}
      </div>
      <div className="form-grid">
        <Field label="Volume reçu (L)" hint={gap ? `Écart avec la commande : ${signed(gap)} L` : 'Conforme à la commande'}>
          <TextInput type="number" min="1" value={f.receivedVolume} onChange={set('receivedVolume')} required autoFocus />
        </Field>
        <Field label="N° du bon de livraison"><TextInput value={f.ticketNumber} onChange={set('ticketNumber')} placeholder={order.ref} /></Field>
        <Field label="Densité du bon"><TextInput type="number" step="0.001" value={f.ticketDensity} onChange={set('ticketDensity')} /></Field>
        <Field label="Température (°C)"><TextInput type="number" step="0.1" value={f.temperature} onChange={set('temperature')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function CancelModal({ order, onClose, onSaved }) {
  const notify = useToast();
  const [reason, setReason] = useState('');
  return (
    <FormModal title={`Annuler la commande ${order.ref}`} danger submitLabel="Annuler la commande" onClose={onClose} onSubmit={async () => {
      await post(`/supplier-orders/${order.id}/cancel`, { reason });
      notify('Commande annulée');
      onSaved();
    }}>
      <Field label="Motif"><TextInput value={reason} onChange={setReason} required autoFocus /></Field>
    </FormModal>
  );
}

function SupplierModal({ supplier, onClose, onSaved }) {
  const notify = useToast();
  const s = supplier || {};
  const [f, setF] = useState({
    name: s.name || '', kind: s.kind || 'CARBURANT', contactName: s.contactName || '', phone: s.phone || '', email: s.email || '',
    address: s.address || '', note: s.note || '', active: s.active ?? true,
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={supplier ? `Fournisseur · ${supplier.name}` : 'Nouveau fournisseur'} wide onClose={onClose} onSubmit={async () => {
      if (supplier) await put(`/suppliers/${supplier.id}`, f);
      else await post('/suppliers', f);
      notify(supplier ? 'Fournisseur modifié' : 'Fournisseur ajouté');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Nom"><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Type"><SelectInput value={f.kind} onChange={set('kind')} options={SUPPLIER_KINDS} /></Field>
        <Field label="Contact"><TextInput value={f.contactName} onChange={set('contactName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Email"><TextInput type="email" value={f.email} onChange={set('email')} /></Field>
        <Field label="État">
          <SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
            options={[{ value: 'oui', label: 'Actif' }, { value: 'non', label: 'Inactif' }]} />
        </Field>
        <Field label="Adresse" full><TextInput value={f.address} onChange={set('address')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function PriceModal({ products, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ productCode: products[0]?.code || '', profile: 'GRAND_PUBLIC', price: '', effectiveAt: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Changer un prix" onClose={onClose} submitLabel={f.effectiveAt ? 'Planifier' : 'Appliquer maintenant'} onSubmit={async () => {
      await put('/prices', { changes: [{ productCode: f.productCode, profile: f.profile, price: Number(f.price) }], effectiveAt: iso(f.effectiveAt), note: f.note || null });
      notify(f.effectiveAt ? `Changement planifié pour le ${dateTime(iso(f.effectiveAt))}` : 'Prix appliqué dans toutes les stations');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Produit">
          <SelectInput value={f.productCode} onChange={set('productCode')} options={products.filter((p) => p.active !== false).map((p) => ({ value: p.code, label: p.name }))} />
        </Field>
        <Field label="Profil"><SelectInput value={f.profile} onChange={set('profile')} options={PROFILES} /></Field>
        <Field label="Nouveau prix (F)"><TextInput type="number" min="0" value={f.price} onChange={set('price')} required /></Field>
        <Field label="Date d'effet" hint="Vide : immédiat (ex. hausse officielle à minuit)">
          <TextInput type="datetime-local" value={f.effectiveAt} onChange={set('effectiveAt')} />
        </Field>
        <Field label="Motif" full><TextInput value={f.note} onChange={set('note')} placeholder="Arrêté ministériel, promotion…" /></Field>
      </div>
    </FormModal>
  );
}

function OrdersTab({ canOrder, canReceive, onModal, version }) {
  const [filter, setFilter] = useState(ORDER_FILTERS[0]);
  const [page, setPage] = useState(0);
  const status = { 'En cours': 'EN_COURS', Livrées: 'LIVREE', Annulées: 'ANNULEE', Toutes: undefined }[filter];
  const orders = useApi(`/supplier-orders${qs({ status, page, size: 20, v: version })}`);
  return (
    <>
      <div className="row" style={{ padding: '12px 20px 0' }}>
        <Segmented items={ORDER_FILTERS} value={filter} onChange={(v) => { setFilter(v); setPage(0); }} />
      </div>
      <Async state={orders}>
        {(data) => (!data.items.length ? <Empty>Aucune commande</Empty> : (
          <>
            <div className="table-wrap"><table className="table">
              <thead><tr><th>Commande</th><th>Fournisseur</th><th>Station · cuve</th><th>Carburant</th><th className="r">Commandé</th><th className="r">Reçu</th>
                <th className="r">Montant</th><th>Prévue</th><th>État</th><th /></tr></thead>
              <tbody>
                {data.items.map((o) => (
                  <tr key={o.id}>
                    <td><div className="num strong">{o.ref}</div><div className="muted" style={{ fontSize: 12 }}>{dateTime(o.orderedAt)}</div></td>
                    <td>{o.supplierName}</td>
                    <td>{o.stationName}<span className="muted"> · {o.tankName}</span></td>
                    <td>{o.productName}</td>
                    <td className="r num">{qty(o.quantity)}</td>
                    <td className="r num">
                      {o.receivedVolume != null ? <>{qty(o.receivedVolume)}{Number(o.gap) !== 0 && <div style={{ fontSize: 12, color: o.gap < 0 ? 'var(--red)' : 'var(--green)' }}>{signed(o.gap)} L</div>}</> : '—'}
                    </td>
                    <td className="r num strong">{fcfa(o.amount)}</td>
                    <td className="muted num">{o.status === 'LIVREE' ? dateTime(o.deliveredAt) : o.expectedAt ? dateTime(o.expectedAt) : '—'}
                      {o.truckPlate && <div style={{ fontSize: 12 }}><Truck size={12} /> {o.truckPlate}{o.driverName ? ` · ${o.driverName}` : ''}</div>}
                    </td>
                    <td><Badge tone={ORDER[o.status]?.[1]}>{ORDER[o.status]?.[0]}</Badge>{o.cancelReason && <div className="muted" style={{ fontSize: 12 }}>{o.cancelReason}</div>}</td>
                    <td className="r" style={{ whiteSpace: 'nowrap' }}>
                      {canOrder && o.status === 'COMMANDEE' && <Button size="sm" icon={Send} title="Expédiée" onClick={() => onModal({ type: 'ship', order: o })} />}{' '}
                      {canReceive && ['COMMANDEE', 'EXPEDIEE'].includes(o.status) && (
                        <Button size="sm" variant="success" icon={PackageCheck} onClick={() => onModal({ type: 'receive', order: o })}>Réceptionner</Button>
                      )}{' '}
                      {canOrder && ['BROUILLON', 'COMMANDEE', 'EXPEDIEE'].includes(o.status) && (
                        <Button size="sm" icon={Ban} title="Annuler" onClick={() => onModal({ type: 'cancel', order: o })} />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table></div>
            <Pager data={data} page={page} onPage={setPage} label="commandes" />
          </>
        ))}
      </Async>
    </>
  );
}

function PricesTab({ canEdit, version, onChanged }) {
  const notify = useToast();
  const history = useApi(`/prices/history${qs({ v: version })}`);
  const cancel = async (c) => {
    try {
      await post(`/prices/changes/${c.id}/cancel`);
      notify('Changement planifié annulé');
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Async state={history}>
      {(rows) => (!rows.length ? <Empty>Aucun changement de prix</Empty> : (
        <div className="table-wrap"><table className="table">
          <thead><tr><th>Date d'effet</th><th>Produit</th><th>Profil</th><th className="r">Ancien</th><th className="r">Nouveau</th><th className="r">Variation</th>
            <th>État</th><th>Par</th><th>Motif</th><th /></tr></thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} style={{ background: c.status === 'PLANIFIE' ? 'var(--orange-50)' : undefined, opacity: c.status === 'ANNULE' ? 0.55 : 1 }}>
                <td className="num">{dateTime(c.effectiveAt)}</td>
                <td className="strong">{c.productName}</td>
                <td>{PROFILE_LABEL[c.profile] || c.profile}</td>
                <td className="r num muted">{c.oldPrice != null ? fcfa(c.oldPrice) : '—'}</td>
                <td className="r num strong">{fcfa(c.newPrice)}</td>
                <td className="r num" style={{ color: c.change > 0 ? 'var(--red)' : c.change < 0 ? 'var(--green)' : undefined }}>{c.change != null && c.change !== 0 ? signed(c.change) : '—'}</td>
                <td><Badge tone={PRICE[c.status]?.[1]}>{PRICE[c.status]?.[0]}</Badge></td>
                <td>{c.createdByName || '—'}</td>
                <td className="muted">{c.note || '—'}</td>
                <td className="r">{canEdit && c.status === 'PLANIFIE' && <Button size="sm" icon={Ban} title="Annuler" onClick={() => cancel(c)} />}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      ))}
    </Async>
  );
}

export default function Approvisionnement() {
  const { can } = useSession();
  const canOrder = can('APPROVISIONNEMENT');
  const canReceive = can('APPROVISIONNEMENT', 'STOCKS');
  const canPrice = can('PRIX');
  const tabs = [canReceive && 'Commandes', canReceive && 'Fournisseurs', 'Prix'].filter(Boolean);
  const [tab, setTab] = useState(tabs[0]);
  const [params, setParams] = useSearchParams();
  const [modal, setModal] = useState(params.get('station') ? { type: 'order', prefill: { station: params.get('station'),
    product: params.get('product'), quantity: params.get('quantity') } } : null);
  const [version, setVersion] = useState(0);
  const suppliers = useApi(canReceive ? '/suppliers?all=true' : null);
  const open = useApi(canReceive ? `/supplier-orders?status=EN_COURS&size=200&v=${version}` : null);
  const stations = useApi('/stations');
  const products = useApi('/products');
  const planned = useApi(`/prices/history?status=PLANIFIE&v=${version}`);
  useLiveReload(() => setVersion((v) => v + 1), ['operation'], 30000);
  const done = () => {
    setModal(null);
    suppliers.reload();
    setVersion((v) => v + 1);
  };
  const inProgress = open.data?.items || [];
  const shipped = inProgress.filter((o) => o.status === 'EXPEDIEE');
  const volume = inProgress.reduce((s, o) => s + Number(o.quantity), 0);
  const fuelSuppliers = (suppliers.data || []).filter((s) => s.active);

  return (
    <>
      <PageHeader title="Approvisionnement et prix" subtitle="Commandes de carburant aux fournisseurs (commandée → expédiée → réceptionnée en cuve), fournisseurs, historique et planification des prix"
        actions={
          <>
            {canPrice && <Button icon={Tags} onClick={() => setModal({ type: 'price' })}>Changer un prix</Button>}
            {canOrder && tab === 'Fournisseurs' && <Button icon={Plus} onClick={() => setModal({ type: 'supplier' })}>Fournisseur</Button>}
            {canOrder && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'order' })} disabled={!fuelSuppliers.length}>Nouvelle commande</Button>}
          </>
        } />
      <div className="grid g4">
        {canReceive && <Kpi icon={Fuel} label="Commandes en cours" value={num(inProgress.length)} foot={`${qty(volume)} attendus`} />}
        {canReceive && <Kpi icon={Truck} tone="orange" label="En route" value={num(shipped.length)} foot={shipped.map((o) => o.stationName).join(', ') || 'aucun camion annoncé'} />}
        {canReceive && <Kpi icon={Factory} tone="navy" label="Fournisseurs actifs" value={num(fuelSuppliers.length)} foot={`${money(fuelSuppliers.reduce((s, x) => s + Number(x.purchased12m || 0), 0))} achetés sur 12 mois`} />}
        <Kpi icon={CalendarClock} tone="purple" label="Prix planifiés" value={num(planned.data?.length)}
          foot={planned.data?.[0] ? `prochain : ${planned.data[planned.data.length - 1].productName} le ${dateTime(planned.data[planned.data.length - 1].effectiveAt)}` : 'aucun changement prévu'} />
      </div>
      <Card style={{ marginTop: 16 }}>
        <div className="card-head" style={{ alignItems: 'center' }}>
          <Segmented items={tabs} value={tab} onChange={setTab} />
        </div>
        {tab === 'Commandes' && <OrdersTab canOrder={canOrder} canReceive={canReceive} onModal={setModal} version={version} />}
        {tab === 'Fournisseurs' && (
          <Async state={suppliers}>
            {(rows) => (!rows.length ? <Empty>Aucun fournisseur</Empty> : (
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Fournisseur</th><th>Type</th><th>Contact</th><th className="r">Commandes</th><th className="r">Achats 12 mois</th><th>Dernière commande</th><th /></tr></thead>
                <tbody>
                  {rows.map((s) => (
                    <tr key={s.id} style={{ opacity: s.active ? 1 : 0.55 }}>
                      <td className="strong">{s.name}{!s.active && <> <Badge tone="gray">Inactif</Badge></>}</td>
                      <td>{SUPPLIER_KINDS.find((k) => k.value === s.kind)?.label || s.kind}</td>
                      <td>{[s.contactName, s.phone, s.email].filter(Boolean).join(' · ') || '—'}</td>
                      <td className="r num">{num(s.orders)}</td>
                      <td className="r num">{money(s.purchased12m)}</td>
                      <td className="muted num">{s.lastOrderAt ? dateTime(s.lastOrderAt) : '—'}</td>
                      <td className="r">{canOrder && <Button size="sm" icon={Pencil} title="Modifier" onClick={() => setModal({ type: 'supplier', supplier: s })} />}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            ))}
          </Async>
        )}
        {tab === 'Prix' && <PricesTab canEdit={canPrice} version={version} onChanged={done} />}
      </Card>

      {modal?.type === 'order' && (
        <OrderModal suppliers={suppliers.data || []} stations={stations.data || []} products={products.data || []} prefill={modal.prefill} onClose={() => { setModal(null); if (params.get('station')) setParams({}); }} onSaved={done} />
      )}
      {modal?.type === 'ship' && <ShipModal order={modal.order} onClose={() => setModal(null)} onSaved={done} />}
      {modal?.type === 'receive' && <ReceiveModal order={modal.order} onClose={() => setModal(null)} onSaved={done} />}
      {modal?.type === 'cancel' && <CancelModal order={modal.order} onClose={() => setModal(null)} onSaved={done} />}
      {modal?.type === 'supplier' && <SupplierModal supplier={modal.supplier} onClose={() => setModal(null)} onSaved={done} />}
      {modal?.type === 'price' && <PriceModal products={products.data || []} onClose={() => setModal(null)} onSaved={done} />}
    </>
  );
}
