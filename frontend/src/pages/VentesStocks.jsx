import { useMemo, useState } from 'react';
import {
  Receipt, Plus, LayoutGrid, Fuel, Droplet, Flame, Package, CreditCard, ShoppingCart, ArrowLeftRight, CircleDollarSign,
  TriangleAlert, Pencil, Cylinder, Users, Sparkles, TrendingUp, TrendingDown, Mail, Trash2,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Segmented, Select, IcoBox, Async, Empty, Pager, Person } from '../components/ui.jsx';
import { Donut } from '../components/charts.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { fileUrl, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { compact, dateTime, fcfa, money, num, PAYMENT_LABELS, pct } from '../lib/format.js';

const TABS = [
  { label: 'Vue d’ensemble', icon: LayoutGrid, category: '' },
  { label: 'Carburant', icon: Fuel, category: 'CARBURANT' },
  { label: 'Lubrifiants', icon: Droplet, category: 'LUBRIFIANT' },
  { label: 'Gaz', icon: Flame, category: 'GAZ' },
  { label: 'Accessoires', icon: Package, category: 'ACCESSOIRE' },
  { label: 'Cartes', icon: CreditCard, category: 'CARTE' },
];
const CATEGORY_ICON = { CARBURANT: Fuel, LUBRIFIANT: Droplet, GAZ: Flame, ACCESSOIRE: Package, CARTE: CreditCard };
const LEVEL_COLOR = { Critique: '#e5484d', Bas: '#f29e1f', Normal: '#5aa935' };
const PROFILES = [
  ['GRAND_PUBLIC', 'Grand public'], ['EMPLOYE_STATION', 'Employé station'], ['EMPLOYE_GROUPE', 'Employé groupe'], ['PARTENAIRE', 'Partenaire'],
];
const KIND_LABELS = { CARBURANT: 'Carburant', BOUTIQUE: 'Boutique', ECHANGE_GAZ: 'Échange gaz', CARTE: 'Carte' };

function PricesModal({ products, onClose, onSaved }) {
  const notify = useToast();
  const [prices, setPrices] = useState(() => Object.fromEntries(products.map((p) => [p.code, { ...p.prices }])));
  return (
    <FormModal title="Prix par profil d’acheteur" wide submitLabel="Enregistrer les prix" onClose={onClose} onSubmit={async () => {
      const changes = [];
      products.forEach((p) => PROFILES.forEach(([profile]) => {
        const v = Number(prices[p.code][profile]);
        if (v !== p.prices[profile]) changes.push({ productCode: p.code, profile, price: v });
      }));
      if (changes.length) await put('/prices', { changes });
      notify(changes.length ? `${changes.length} prix modifiés` : 'Aucun changement');
      onSaved();
    }}>
      <div className="table-wrap"><table className="table">
        <thead>
          <tr><th>Produit</th>{PROFILES.map(([, l]) => <th key={l} className="r">{l}</th>)}</tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.code}>
              <td className="strong">{p.name}</td>
              {PROFILES.map(([profile]) => (
                <td key={profile} className="r">
                  <label className="input" style={{ height: 34, width: 110, marginLeft: 'auto' }}>
                    <input className="num" style={{ textAlign: 'right' }} type="number" min="0" value={prices[p.code][profile] ?? ''}
                      onChange={(e) => setPrices({ ...prices, [p.code]: { ...prices[p.code], [profile]: e.target.value } })} />
                  </label>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table></div>
    </FormModal>
  );
}

/** Vente boutique (gaz, lubrifiants, accessoires) ou échange de bouteille. */
function SaleModal({ exchange, stations, products, onClose, onSaved }) {
  const notify = useToast();
  const { user } = useSession();
  const sellable = products.filter((p) => (exchange ? p.category === 'GAZ' : ['GAZ', 'LUBRIFIANT', 'ACCESSOIRE'].includes(p.category)));
  const [stationId, setStationId] = useState(user.stationId || stations[0]?.id || '');
  const [lines, setLines] = useState([{ productCode: sellable[0]?.code, quantity: 1, withDeposit: false }]);
  const [payment, setPayment] = useState('ESPECES');
  const [cardNumber, setCardNumber] = useState('');
  const [pin, setPin] = useState('');
  const total = lines.reduce((s, l) => {
    const p = sellable.find((x) => x.code === l.productCode);
    return s + (p ? (p.prices.GRAND_PUBLIC + (l.withDeposit ? p.depositAmount || 0 : 0)) * Number(l.quantity || 0) : 0);
  }, 0);
  const setLine = (i, patch) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  return (
    <FormModal
      title={exchange ? 'Échange de bouteille vide ⇄ pleine' : 'Nouvelle vente boutique'}
      submitLabel={`Encaisser ${exchange ? '' : `· ${money(total)}`}`}
      wide
      onClose={onClose}
      onSubmit={async () => {
        const base = { stationId: Number(stationId), paymentMethod: payment, cardNumber: payment === 'CARTE' ? cardNumber : null,
          pin: payment === 'CARTE' ? pin || null : null };
        const r = exchange
          ? await post('/sales/gas-exchange', { ...base, productCode: lines[0].productCode, quantity: Number(lines[0].quantity) })
          : await post('/sales/shop', { ...base, lines: lines.map((l) => ({ ...l, quantity: Number(l.quantity) })) });
        notify(`Vente enregistrée · ${money(r.total)}`);
        onSaved();
      }}
    >
      <div className="form-grid">
        <Field label="Station">
          <SelectInput value={stationId} onChange={setStationId} options={stations.map((s) => ({ value: s.id, label: s.shortName }))} />
        </Field>
        <Field label="Paiement">
          <SelectInput value={payment} onChange={setPayment} options={[{ value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'CARTE', label: 'Carte ZENER' }]} />
        </Field>
        {payment === 'CARTE' && (
          <>
            <Field label="N° de carte"><TextInput value={cardNumber} onChange={setCardNumber} required /></Field>
            <Field label="Code PIN"><TextInput type="password" value={pin} onChange={setPin} /></Field>
          </>
        )}
      </div>
      <div className="section-label" style={{ marginTop: 18 }}>Articles</div>
      {lines.map((l, i) => {
        const p = sellable.find((x) => x.code === l.productCode);
        return (
          <div key={i} className="row" style={{ gap: 10, marginBottom: 10 }}>
            <label className="input" style={{ flex: 1 }}>
              <select value={l.productCode} onChange={(e) => setLine(i, { productCode: e.target.value })}>
                {sellable.map((x) => <option key={x.code} value={x.code}>{x.name} · {fcfa(x.prices.GRAND_PUBLIC)} F</option>)}
              </select>
            </label>
            <label className="input" style={{ width: 90 }}>
              <input type="number" min="1" className="num" value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} />
            </label>
            {!exchange && p?.depositAmount && (
              <label className="row" style={{ gap: 6, whiteSpace: 'nowrap', fontSize: 13 }}>
                <input type="checkbox" checked={l.withDeposit} onChange={(e) => setLine(i, { withDeposit: e.target.checked })} />
                Bouteille neuve (+{fcfa(p.depositAmount)} F consigne)
              </label>
            )}
            {!exchange && lines.length > 1 && (
              <button type="button" className="link-more" onClick={() => setLines(lines.filter((_, j) => j !== i))}><Trash2 size={16} /></button>
            )}
          </div>
        );
      })}
      {!exchange && (
        <Button type="button" size="sm" icon={Plus} onClick={() => setLines([...lines, { productCode: sellable[0]?.code, quantity: 1, withDeposit: false }])}>
          Ajouter un article
        </Button>
      )}
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>
        Le prix appliqué dépend du profil du titulaire de la carte (sans carte : grand public).
      </div>
    </FormModal>
  );
}

/** Entrée de stock (livraison reçue) ou achat fournisseur. */
function EntryModal({ purchase, stations, products, onClose, onSaved }) {
  const notify = useToast();
  const { user } = useSession();
  const items = products.filter((p) => p.category !== 'CARTE');
  const [stationId, setStationId] = useState(user.stationId || stations[0]?.id || '');
  const [productCode, setProductCode] = useState(items[0]?.code);
  const [quantity, setQuantity] = useState('');
  const [supplier, setSupplier] = useState('');
  const [unitCost, setUnitCost] = useState('');
  const [note, setNote] = useState('');
  return (
    <FormModal title={purchase ? 'Nouvel achat fournisseur' : 'Entrée de stock'} onClose={onClose} onSubmit={async () => {
      if (purchase) {
        await post('/stock/purchases', { supplier, stationId: Number(stationId), note: note || null,
          lines: [{ productCode, quantity: Number(quantity), unitCost: unitCost ? Number(unitCost) : 0 }] });
        notify('Achat enregistré · stock mis à jour');
      } else {
        await post('/stock/entries', { stationId: Number(stationId), productCode, quantity: Number(quantity), note: note || null });
        notify('Entrée de stock enregistrée');
      }
      onSaved();
    }}>
      <div className="form-grid">
        {purchase && <Field label="Fournisseur" full><TextInput value={supplier} onChange={setSupplier} required placeholder="Dépôt pétrolier, grossiste…" /></Field>}
        <Field label="Station">
          <SelectInput value={stationId} onChange={setStationId} options={stations.map((s) => ({ value: s.id, label: s.shortName }))} />
        </Field>
        <Field label="Produit">
          <SelectInput value={productCode} onChange={setProductCode} options={items.map((p) => ({ value: p.code, label: p.name }))} />
        </Field>
        <Field label={`Quantité (${items.find((p) => p.code === productCode)?.unit || ''})`}>
          <TextInput type="number" min="0.01" step="0.01" value={quantity} onChange={setQuantity} required />
        </Field>
        {purchase && <Field label="Coût unitaire (F)"><TextInput type="number" min="0" value={unitCost} onChange={setUnitCost} /></Field>}
        <Field label="Note" full><TextInput value={note} onChange={setNote} placeholder="N° de bon de livraison…" /></Field>
      </div>
    </FormModal>
  );
}

export default function VentesStocks() {
  const { can } = useSession();
  const [tab, setTab] = useState('Vue d’ensemble');
  const [stationId, setStationId] = useState('');
  const [modal, setModal] = useState(null);
  const [page, setPage] = useState(0);
  const category = TABS.find((t) => t.label === tab).category;

  const summary = useApi('/sales/summary');
  const prices = useApi('/prices');
  const bottles = useApi(`/stock/gas-bottles${qs({ stationId })}`);
  const stock = useApi(`/stock${qs({ stationId, category })}`);
  const stations = useApi('/stations');
  const sales = useApi(can('TRANSACTIONS', 'VENTES_POMPE') ? `/sales${qs({ stationId, page, size: 10 })}` : null);
  const reload = () => {
    summary.reload();
    bottles.reload();
    stock.reload();
    sales.reload();
  };
  useLiveReload(reload, ['sale', 'alert'], 4000);

  const products = prices.data || [];
  const shown = useMemo(
    () => products.filter((p) => (category ? p.category === category : p.category !== 'CARBURANT')),
    [products, category],
  );
  const s = summary.data;
  const b = bottles.data;
  const shopChange = s?.shopYesterdaySameTime ? ((s.shopToday - s.shopYesterdaySameTime) / s.shopYesterdaySameTime) * 100 : null;
  const rows = stock.data || [];

  return (
    <>
      <PageHeader
        title="Ventes & stocks"
        subtitle="Cartes, gaz, lubrifiants et accessoires · prix par profil · stocks et alertes intelligentes"
        actions={
          <>
            {can('STOCKS') && <Button icon={Receipt} disabled={!stations.data || !prices.data} onClick={() => setModal('purchase')}>Nouvel achat</Button>}
            {can('VENTES_BOUTIQUE') && <Button variant="primary" icon={Plus} disabled={!stations.data || !prices.data} onClick={() => setModal('sale')}>Nouvelle vente</Button>}
          </>
        }
      />

      <div className="mb20">
        <Segmented value={tab} onChange={setTab} items={TABS.map(({ label, icon }) => ({ label, icon }))} />
      </div>

      <div className="grid g4 mb20">
        <Kpi icon={ShoppingCart} label="Ventes boutique du jour" value={s ? fcfa(s.shopToday) : '…'} unit="FCFA"
          foot={shopChange === null ? 'gaz, lubrifiants, accessoires' : <><Trend value={pct(shopChange)} down={shopChange < 0} /> vs hier même heure</>} />
        <Kpi icon={ArrowLeftRight} tone="orange" label="Bouteilles échangées" value={s ? num(s.bottlesExchangedToday) : '…'} foot={s ? `${s.exchangesToday} échanges aujourd’hui` : ''} />
        <Kpi icon={CircleDollarSign} tone="navy" label="Achats du mois" value={s ? compact(s.purchasesMonth) : '…'} unit="FCFA" foot={s ? `${s.purchasesMonthCount} bons de livraison` : ''} />
        <Kpi icon={TriangleAlert} tone="red" label="Alertes de stock" value={s ? s.alerts : '…'}
          foot={s ? <><Badge tone="red">{s.alertsCritical} critiques</Badge> {s.alerts - s.alertsCritical} à surveiller</> : ''} />
      </div>

      <div className="grid split mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 420px', alignItems: 'start' }}>
        <Card>
          <div className="card-head" style={{ paddingBottom: 14 }}>
            <div>
              <div className="card-title">Prix par profil d’acheteur</div>
              <div className="card-sub">FCFA TTC · appliqués automatiquement selon le profil</div>
            </div>
            {can('PRIX') && <Button size="sm" icon={Pencil} disabled={!shown.length} onClick={() => setModal('prices')}>Modifier les prix</Button>}
          </div>
          <Async state={prices}>
            {() => (shown.length === 0 ? <Empty>Aucun produit</Empty> : (
              <div className="table-wrap"><table className="table">
                <thead>
                  <tr><th>Produit</th>{PROFILES.map(([, l]) => <th key={l} className="r">{l}</th>)}</tr>
                </thead>
                <tbody>
                  {shown.map((p) => (
                    <tr key={p.code}>
                      <td>
                        <div className="row" style={{ gap: 12 }}>
                          <IcoBox icon={CATEGORY_ICON[p.category] || Package} tone="gray" size={30} />
                          <span className="strong">{p.name}</span>
                        </div>
                      </td>
                      {PROFILES.map(([profile], i) => (
                        <td key={profile} className={`r num ${i === 0 ? 'strong' : ''}`} style={i ? { color: 'var(--text-2)' } : undefined}>
                          {fcfa(p.prices[profile])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table></div>
            ))}
          </Async>
        </Card>

        <Card className="card-pad">
          <div className="card-title">Bouteilles de gaz</div>
          <div className="card-sub">État du parc et consigne</div>
          {b && (
            <div className="row" style={{ gap: 20, marginTop: 18 }}>
              <Donut size={140} thickness={16} segments={[
                { value: b.full, color: '#5aa935' }, { value: b.empty, color: '#cbd5e1' }, { value: b.withCustomers, color: '#1b66b8' }, { value: b.damaged, color: '#e5484d' },
              ]}>
                <div>
                  <div className="num" style={{ fontSize: 22, fontWeight: 700 }}>{num(b.total)}</div>
                  <div className="muted" style={{ fontSize: 12 }}>bouteilles</div>
                </div>
              </Donut>
              <div style={{ flex: 1 }}>
                {[
                  [Cylinder, 'green', 'Pleines', b.full], [Cylinder, 'gray', 'Vides', b.empty],
                  [Users, 'blue', 'Chez les clients', b.withCustomers], [TriangleAlert, 'red', 'Endommagées', b.damaged],
                ].map(([icon, tone, l, v]) => (
                  <div key={l} className="list-item" style={{ padding: '10px 0' }}>
                    <IcoBox icon={icon} tone={tone} size={32} />
                    <span style={{ flex: 1 }}>{l}</span>
                    <span className="strong num" style={{ fontSize: 16 }}>{num(v)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          <div className="row" style={{ gap: 12, background: 'var(--surface-2)', borderRadius: 10, padding: '12px 14px', marginTop: 16 }}>
            <ArrowLeftRight size={18} color="var(--orange)" />
            <div style={{ flex: 1, fontSize: 13 }}>
              <div className="strong">Échange vide ⇄ pleine</div>
              <div>{(b?.byProduct || []).map((g) => `consigne ${g.name.replace('Gaz butane ', '')} : ${fcfa(g.depositAmount)} F`).join(' · ')}</div>
            </div>
            {can('VENTES_BOUTIQUE') && <Button size="sm" disabled={!stations.data} onClick={() => setModal('exchange')}>Enregistrer un échange</Button>}
          </div>
        </Card>
      </div>

      <Card className="mb20">
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Stocks & alertes</div>
          <div className="row" style={{ gap: 8 }}>
            <Select icon={Fuel} value={stationId} onChange={(v) => { setStationId(v); setPage(0); }} placeholder="Toutes les stations"
              options={(stations.data || []).map((x) => ({ value: x.id, label: x.shortName }))} />
            {can('STOCKS') && <Button variant="primary" icon={Plus} disabled={!stations.data || !prices.data} onClick={() => setModal('entry')}>Entrée de stock</Button>}
          </div>
        </div>
        <Async state={stock}>
          {() => (rows.length === 0 ? <Empty>Aucun stock pour ce filtre</Empty> : (
            <div className="table-wrap"><table className="table">
              <thead>
                <tr><th>Produit</th><th>Station</th><th className="r">Stock</th><th style={{ width: 230 }}>Niveau</th><th>Seuil d’alerte</th><th>Tendance 7 j</th><th>État</th></tr>
              </thead>
              <tbody>
                {rows.slice(0, 60).map((r) => (
                  <tr key={`${r.source}-${r.id}`}>
                    <td className="strong">{r.productName}</td>
                    <td>{r.place}</td>
                    <td className="r num strong">{num(r.quantity)}{r.unit === 'L' ? ' L' : ''}</td>
                    <td><div className="progress" style={{ maxWidth: 170 }}><span style={{ width: `${r.levelPct ?? 0}%`, background: LEVEL_COLOR[r.state] }} /></div></td>
                    <td>
                      <span className="row num" style={{ gap: 8 }}>
                        {r.threshold != null ? `${num(r.threshold)}${r.unit === 'L' ? ' L' : ''}` : '—'}
                        {r.autoThreshold && <Badge tone="blue" style={{ height: 22, fontSize: 12 }}><Sparkles size={12} /> auto</Badge>}
                      </span>
                    </td>
                    <td>
                      {r.trendPct != null ? (
                        <span className="row num" style={{ gap: 4, fontWeight: 500 }}>
                          {r.trendPct < 0 ? <TrendingDown size={14} /> : <TrendingUp size={14} />} {r.trendPct > 0 ? '+' : '−'}{pct(r.trendPct)}
                        </span>
                      ) : '—'}
                    </td>
                    <td><StatusBadge status={r.state} /></td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          ))}
        </Async>
        <div className="row" style={{ gap: 10, padding: '14px 20px', borderTop: '1px solid var(--border-2)', color: 'var(--text-2)' }}>
          <Mail size={17} color="var(--primary)" />
          Email automatique au gérant et à la logistique dès qu’un stock passe sous son seuil · seuils recalculés chaque nuit selon la consommation.
        </div>
      </Card>

      {can('TRANSACTIONS', 'VENTES_POMPE') && (
        <Card>
          <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
            <div className="card-title">Dernières ventes</div>
            <span className="muted" style={{ fontSize: 13 }}>Historique des transactions · mis à jour en direct</span>
          </div>
          <Async state={sales}>
            {(data) => (data.items.length === 0 ? <Empty>Aucune vente</Empty> : (
              <>
                <div className="table-wrap"><table className="table">
                  <thead>
                    <tr><th>Quand</th><th>Lieu</th><th>Type</th><th>Détail</th><th>Vendeur</th><th>Paiement</th><th className="r">Montant (F)</th></tr>
                  </thead>
                  <tbody>
                    {data.items.map((x) => (
                      <tr key={x.id}>
                        <td className="muted num">{dateTime(x.at)}</td>
                        <td>{x.stationName || x.pdvName}{x.pumpCode ? ` · ${x.pumpCode}` : ''}</td>
                        <td><Badge tone={x.mode === 'AUTONOME' ? 'navy' : 'gray'}>{KIND_LABELS[x.kind] || x.kind}{x.mode === 'AUTONOME' ? ' · auto' : ''}</Badge></td>
                        <td>{x.description}</td>
                        <td><Person name={x.employeeName} id={x.employeeId} photo={fileUrl(x.employeePhotoUrl)} /></td>
                        <td>{PAYMENT_LABELS[x.paymentMethod]}{x.cardNumber ? ` ···${x.cardNumber.slice(-4)}` : ''}</td>
                        <td className="r num strong">{fcfa(x.total)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table></div>
                <Pager data={data} page={page} onPage={setPage} label="ventes" />
              </>
            ))}
          </Async>
        </Card>
      )}

      {modal === 'prices' && <PricesModal products={shown} onClose={() => setModal(null)} onSaved={prices.reload} />}
      {(modal === 'sale' || modal === 'exchange') && (
        <SaleModal exchange={modal === 'exchange'} stations={stations.data} products={products} onClose={() => setModal(null)} onSaved={reload} />
      )}
      {(modal === 'entry' || modal === 'purchase') && (
        <EntryModal purchase={modal === 'purchase'} stations={stations.data} products={products} onClose={() => setModal(null)} onSaved={reload} />
      )}
    </>
  );
}
