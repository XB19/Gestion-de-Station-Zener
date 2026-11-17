import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Plus, Store, CreditCard, HandCoins, Clock, CalendarDays, Users, CloudOff, Banknote, Smartphone, Printer, CircleCheck,
  PackagePlus, BadgePlus, Megaphone, RotateCcw, ShoppingBag, Truck, Trash2,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar, IcoBox, Async, Empty } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useInterval, useLiveReload } from '../api/hooks.js';
import { fileUrl, post, put } from '../api/client.js';
import CardPicker from '../components/CardPicker.jsx';
import { useSession } from '../api/session.jsx';
import { ago, avatarColor, dayMonth, fcfa, isoDay, money, num, pct } from '../lib/format.js';

const KIND_TONE = { PERMANENT: 'blue', TEMPORAIRE: 'purple', PROMOTIONNEL: 'orange' };
const KIND_ICON = { PERMANENT: Store, TEMPORAIRE: CalendarDays, PROMOTIONNEL: Megaphone };

function subtitle(p) {
  if (p.kind === 'PERMANENT') return `Permanent · ${p.address || ''}`;
  const period = `${dayMonth(p.startDate)} → ${dayMonth(p.endDate)}`;
  const kind = p.kind === 'PROMOTIONNEL' ? `Promotionnel${p.campaign ? ` · ${p.campaign}` : ''}` : 'Temporaire';
  const session = p.sessionNo > 1 ? ` · session ${p.sessionNo}` : '';
  if (p.status === 'DEMANDE') return `${kind} · Demande${session} · ${period}`;
  return `${kind}${session} · ${period}${p.daysLeft != null && p.daysLeft >= 0 ? ` · J-${p.daysLeft}` : ''}`;
}

/** Réouverture d'un point de vente clos : nouvelle session, à valider par l'Admin ou le DAF. */
function ReopenModal({ pdv, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ startDate: isoDay(), endDate: isoDay(new Date(Date.now() + 5 * 86400000)), reason: '', campaign: pdv.campaign || '', offer: pdv.offer || '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Rouvrir · ${pdv.name}`} submitLabel="Demander la réouverture" onClose={onClose} onSubmit={async () => {
      await post(`/pdv/${pdv.id}/reopen`, f);
      notify('Réouverture demandée · à valider par l’Admin ou le DAF', 'info');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Début"><TextInput type="date" value={f.startDate} onChange={set('startDate')} required /></Field>
        <Field label="Fin"><TextInput type="date" value={f.endDate} onChange={set('endDate')} required /></Field>
        {pdv.kind === 'PROMOTIONNEL' && <Field label="Campagne"><TextInput value={f.campaign} onChange={set('campaign')} /></Field>}
        {pdv.kind === 'PROMOTIONNEL' && <Field label="Offre"><TextInput value={f.offer} onChange={set('offer')} /></Field>}
        <Field label="Motif" full><TextInput value={f.reason} onChange={set('reason')} placeholder="Nouvelle édition, demande du partenaire…" /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>La nouvelle session a sa propre validation, son rapprochement et sa clôture ; l'historique des sessions précédentes est conservé.</div>
    </FormModal>
  );
}

/** Produits proposés (lubrifiants, gaz, accessoires, cartes à prix de campagne) et stock sur place. */
function ProductsCard({ pdv, canManage, canSell, onChanged }) {
  const notify = useToast();
  const all = useApi(canManage ? '/products' : null);
  const stations = useApi(canManage ? '/stations' : null);
  const [editing, setEditing] = useState(false);
  const [items, setItems] = useState([]);
  const [transfer, setTransfer] = useState(null);
  const [selling, setSelling] = useState(false);
  const list = pdv.productsList || [];
  const startEdit = () => {
    setItems(list.map((p) => ({ productId: p.id, promoPrice: p.promoPrice ?? '' })));
    setEditing(true);
  };
  const save = async () => {
    try {
      await put(`/pdv/${pdv.id}/products`, { items: items.filter((i) => i.productId).map((i) => ({ productId: Number(i.productId), promoPrice: i.promoPrice === '' ? null : Number(i.promoPrice) })) });
      notify('Produits du point de vente enregistrés');
      setEditing(false);
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const choices = (all.data || []).filter((p) => p.active && !['CARBURANT', 'SERVICE'].includes(p.category));
  return (
    <Card className="card-pad">
      <div className="row between">
        <div className="row" style={{ gap: 8 }}><ShoppingBag size={18} color="var(--primary)" /><span className="card-title">Produits</span></div>
        <span className="row" style={{ gap: 8 }}>
          {canSell && pdv.status === 'OUVERT' && list.some((p) => p.category !== 'CARTE') && <Button size="sm" variant="primary" icon={BadgePlus} onClick={() => setSelling(true)}>Vendre des produits</Button>}
          {canManage && !editing && <Button size="sm" onClick={startEdit}>Modifier la liste</Button>}
        </span>
      </div>
      {editing ? (
        <div style={{ marginTop: 10 }}>
          {items.map((it, i) => (
            <div key={i} className="row" style={{ gap: 8, marginBottom: 6 }}>
              <label className="input" style={{ flex: 1 }}><select value={it.productId} onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, productId: e.target.value } : x)))}>
                <option value="">Choisir…</option>
                {choices.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select></label>
              <label className="input" style={{ width: 150 }}><input type="number" min="0" placeholder="Prix promo (F)" value={it.promoPrice}
                onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, promoPrice: e.target.value } : x)))} /></label>
              <Button size="sm" icon={Trash2} title="Retirer" onClick={() => setItems(items.filter((_, j) => j !== i))} />
            </div>
          ))}
          <div className="row" style={{ gap: 8, marginTop: 6 }}>
            <Button size="sm" icon={Plus} onClick={() => setItems([...items, { productId: '', promoPrice: '' }])}>Produit</Button>
            <Button size="sm" onClick={() => setEditing(false)}>Annuler</Button>
            <Button size="sm" variant="primary" onClick={save}>Enregistrer</Button>
          </div>
        </div>
      ) : list.length === 0 ? <div className="muted" style={{ marginTop: 10 }}>Cartes neuves uniquement.</div> : (
        <div className="table-wrap" style={{ marginTop: 8 }}><table className="table">
          <thead><tr><th>Produit</th><th className="r">Prix</th><th className="r">Stock</th>{canManage && <th />}</tr></thead>
          <tbody>{list.map((p) => (
            <tr key={p.id}>
              <td>{p.name}{p.grade ? <span className="muted"> · {p.grade}</span> : ''}</td>
              <td className="r num">{p.promoPrice != null ? <><s className="muted">{fcfa(p.price)}</s> <b style={{ color: 'var(--orange)' }}>{fcfa(p.promoPrice)}</b></> : fcfa(p.price)} F</td>
              <td className="r num">{p.category === 'CARTE' ? '—' : num(p.stock)}</td>
              {canManage && <td className="r">{p.category !== 'CARTE' && <Button size="sm" icon={Truck} title="Envoyer / retourner du stock" onClick={() => setTransfer(p)} />}</td>}
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {transfer && (
        <FormModal title={`Stock · ${transfer.name}`} submitLabel="Valider le mouvement" onClose={() => setTransfer(null)} onSubmit={async () => {
          const back = document.getElementById('pdv-tr-dir').value === 'back';
          await post(`/pdv/${pdv.id}/stock`, { stationId: Number(document.getElementById('pdv-tr-st').value), productId: transfer.id,
            quantity: Number(document.getElementById('pdv-tr-q').value), back });
          notify(back ? 'Stock retourné à la station' : 'Stock envoyé au point de vente');
          onChanged();
        }}>
          <div className="form-grid">
            <Field label="Mouvement"><label className="input"><select id="pdv-tr-dir"><option value="send">Station → point de vente</option><option value="back">Point de vente → station</option></select></label></Field>
            <Field label="Station"><label className="input"><select id="pdv-tr-st">{(stations.data || []).map((st) => <option key={st.id} value={st.id}>{st.name}</option>)}</select></label></Field>
            <Field label="Quantité"><label className="input"><input id="pdv-tr-q" type="number" min="1" defaultValue="10" required /></label></Field>
          </div>
        </FormModal>
      )}
      {selling && <ProductSaleModal pdv={pdv} products={list.filter((p) => p.category !== 'CARTE')} onClose={() => setSelling(false)} onSaved={onChanged} />}
    </Card>
  );
}

/** Vente de produits au point de vente : espèces, mobile money ou carte Zener (avec PIN). */
function ProductSaleModal({ pdv, products, onClose, onSaved }) {
  const notify = useToast();
  const [lines, setLines] = useState([{ productCode: products[0]?.code || '', quantity: '1' }]);
  const [method, setMethod] = useState('ESPECES');
  const [ref, setRef] = useState('');
  const [card, setCard] = useState(null);
  const [pin, setPin] = useState('');
  const price = (code) => {
    const p = products.find((x) => x.code === code);
    return p ? (p.promoPrice ?? p.price) : 0;
  };
  const total = lines.reduce((t, l) => t + price(l.productCode) * (Number(l.quantity) || 0), 0);
  return (
    <FormModal title={`Vente de produits · ${pdv.name}`} wide submitLabel={`Encaisser ${money(total)}`} onClose={onClose} onSubmit={async () => {
      const r = await post(`/pdv/${pdv.id}/product-sales`, {
        uuid: crypto.randomUUID(), lines: lines.filter((l) => l.productCode && Number(l.quantity) > 0).map((l) => ({ productCode: l.productCode, quantity: Number(l.quantity) })),
        paymentMethod: method, paymentRef: ref || null, cardNumber: card?.number || null, pin: pin || null,
      });
      notify(`Vente enregistrée · ${money(r.total)}`);
      onSaved();
    }}>
      {lines.map((l, i) => (
        <div key={i} className="row" style={{ gap: 8, marginBottom: 6 }}>
          <label className="input" style={{ flex: 1 }}><select value={l.productCode} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, productCode: e.target.value } : x)))}>
            {products.map((p) => <option key={p.code} value={p.code}>{p.name} · {fcfa(p.promoPrice ?? p.price)} F · stock {num(p.stock)}</option>)}
          </select></label>
          <label className="input" style={{ width: 90 }}><input type="number" min="1" value={l.quantity} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} /></label>
          {lines.length > 1 && <Button size="sm" icon={Trash2} onClick={() => setLines(lines.filter((_, j) => j !== i))} />}
        </div>
      ))}
      <Button size="sm" icon={Plus} onClick={() => setLines([...lines, { productCode: products[0]?.code || '', quantity: '1' }])}>Ligne</Button>
      <div className="form-grid" style={{ marginTop: 12 }}>
        <Field label="Paiement"><SelectInput value={method} onChange={setMethod} options={[{ value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' },
          { value: 'CARTE', label: 'Carte Zener' }]} /></Field>
        {method === 'MOBILE_MONEY' && <Field label="Référence"><TextInput value={ref} onChange={setRef} required /></Field>}
        {method === 'CARTE' && <Field label="Carte" full><CardPicker value={card} onChange={setCard} /></Field>}
        {method === 'CARTE' && <Field label="Code PIN"><TextInput type="password" value={pin} onChange={setPin} required /></Field>}
      </div>
    </FormModal>
  );
}

function PdvModal({ temporary, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ name: '', address: '', startDate: isoDay(), endDate: isoDay(new Date(Date.now() + 5 * 86400000)), cardsPlanned: 200, reason: '',
    kind: 'TEMPORAIRE', campaign: '', offer: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal
      title={temporary ? 'Demander l’ouverture d’un point de vente temporaire' : 'Nouveau point de vente permanent'}
      submitLabel={temporary ? 'Envoyer la demande' : 'Créer'}
      onClose={onClose}
      onSubmit={async () => {
        const body = { ...f, cardsPlanned: Number(f.cardsPlanned) || null };
        if (temporary) {
          await post('/pdv/requests', body);
          notify('Demande envoyée · à valider par l’Admin ou le DAF', 'info');
        } else {
          await post('/pdv', { name: f.name, address: f.address, cardsPlanned: body.cardsPlanned });
          notify('Point de vente créé');
        }
        onSaved();
      }}
    >
      <div className="form-grid">
        <Field label="Nom" full><TextInput value={f.name} onChange={set('name')} required placeholder={temporary ? 'Campagne Université de Lomé' : 'Agence de Kara'} /></Field>
        <Field label="Adresse" full><TextInput value={f.address} onChange={set('address')} /></Field>
        {temporary && (
          <>
            <Field label="Type">
              <SelectInput value={f.kind} onChange={set('kind')} options={[{ value: 'TEMPORAIRE', label: 'Temporaire (événement)' },
                { value: 'PROMOTIONNEL', label: 'Promotionnel (campagne, partenaire)' }]} />
            </Field>
            {f.kind === 'PROMOTIONNEL' && <Field label="Campagne"><TextInput value={f.campaign} onChange={set('campaign')} required placeholder="Campagne partenaire…" /></Field>}
            {f.kind === 'PROMOTIONNEL' && <Field label="Offre" full><TextInput value={f.offer} onChange={set('offer')} placeholder="Carte classique à 500 F…" /></Field>}
            <Field label="Début"><TextInput type="date" value={f.startDate} onChange={set('startDate')} required /></Field>
            <Field label="Fin"><TextInput type="date" value={f.endDate} onChange={set('endDate')} required /></Field>
          </>
        )}
        <Field label="Cartes prévues"><TextInput type="number" min="0" value={f.cardsPlanned} onChange={set('cardsPlanned')} /></Field>
        {temporary && <Field label="Motif" full><TextInput value={f.reason} onChange={set('reason')} placeholder="Événement, campagne…" /></Field>}
      </div>
    </FormModal>
  );
}

function AllocateModal({ pdv, onClose, onSaved }) {
  const notify = useToast();
  const [typeCode, setTypeCode] = useState('CLASSIQUE');
  const [quantity, setQuantity] = useState(50);
  return (
    <FormModal title={`Attribuer des cartes · ${pdv.name}`} submitLabel="Attribuer" onClose={onClose} onSubmit={async () => {
      await post(`/pdv/${pdv.id}/cards/allocate`, { typeCode, quantity: Number(quantity) });
      notify(`${quantity} cartes attribuées`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Type"><SelectInput value={typeCode} onChange={setTypeCode} options={[{ value: 'CLASSIQUE', label: 'Classique' }, { value: 'CLUB', label: 'Club' }]} /></Field>
        <Field label="Quantité"><TextInput type="number" min="1" max="2000" value={quantity} onChange={setQuantity} required /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>Seules les cartes activées du stock sont attribuées, dans l’ordre des numéros.</div>
    </FormModal>
  );
}

/** Vente d'une carte neuve sur pièce d'identité (espèces ou mobile money). */
function SellModal({ pdv, onClose, onSaved }) {
  const notify = useToast();
  const cards = useApi(`/pdv/${pdv.id}/cards`);
  const [f, setF] = useState({ cardNumber: '', name: '', idDocType: 'CNI', idDocNumber: '', phone: '', paymentMethod: 'ESPECES', paymentRef: '', deposit: '200000' });
  const isClub = (cards.data || []).find((c) => c.number === f.cardNumber)?.typeCode === 'CLUB';
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  useEffect(() => {
    if (!f.cardNumber && cards.data?.length) setF((x) => ({ ...x, cardNumber: cards.data[0].number }));
  }, [cards.data]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <FormModal title={`Vendre une carte · ${pdv.name}`} submitLabel="Encaisser et remettre la carte" wide onClose={onClose} onSubmit={async () => {
      const r = await post(`/pdv/${pdv.id}/sales`, {
        uuid: crypto.randomUUID(), cardNumber: f.cardNumber, paymentMethod: f.paymentMethod, paymentRef: f.paymentRef || null,
        holder: { name: f.name, idDocType: f.idDocType, idDocNumber: f.idDocNumber, phone: f.phone || null }, deposit: isClub ? Number(f.deposit) : null,
      });
      notify(`Carte vendue et active · ${money(r.total)}${isClub ? ` + dépôt Club ${money(Number(f.deposit))}` : ''}`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Carte" full>
          <SelectInput value={f.cardNumber} onChange={set('cardNumber')}
            options={(cards.data || []).map((c) => ({ value: c.number, label: `${c.numberFormatted || c.number} · ${c.typeLabel || c.typeCode}` }))} />
        </Field>
        <Field label="Nom de l’acheteur" full><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Pièce d’identité">
          <SelectInput value={f.idDocType} onChange={set('idDocType')} options={['CNI', 'Passeport', 'Permis', 'Carte d’électeur']} />
        </Field>
        <Field label="Numéro de la pièce"><TextInput value={f.idDocNumber} onChange={set('idDocNumber')} required /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Paiement">
          <SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={[{ value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' }]} />
        </Field>
        {f.paymentMethod === 'MOBILE_MONEY' && <Field label="Référence de la transaction" full><TextInput value={f.paymentRef} onChange={set('paymentRef')} required /></Field>}
        {isClub && <Field label="Dépôt Club crédité (F)" hint="Carte Club : 200 000 F minimum en plus du prix de la carte"><TextInput type="number" min="200000" value={f.deposit} onChange={set('deposit')} required /></Field>}
      </div>
    </FormModal>
  );
}

function Closure({ pdv, onClosed }) {
  const notify = useToast();
  const { can } = useSession();
  const r = pdv.reconciliation;
  const [cash, setCash] = useState(String(r.expectedCash));
  const [mm, setMm] = useState(String(r.expectedMobileMoney));
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setCash(String(r.expectedCash));
    setMm(String(r.expectedMobileMoney));
  }, [pdv.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const closed = pdv.closure;
  const counted = closed ? closed.countedCash + closed.countedMm : Number(cash || 0) + Number(mm || 0);
  const gap = closed ? closed.gap : counted - r.expectedTotal;
  const canClose = !closed && ['OUVERT', 'A_CLOTURER'].includes(pdv.status) && can('PDV_GERER', 'PDV_VENDRE');
  const close = async () => {
    setBusy(true);
    try {
      await post(`/pdv/${pdv.id}/close`, { countedCash: Number(cash), countedMobileMoney: Number(mm) });
      notify('Point de vente clôturé · rapport généré');
      onClosed();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };
  const rows = [
    { icon: Banknote, l: 'Espèces', expected: r.expectedCash, counted: closed ? closed.countedCash : cash, set: setCash },
    { icon: Smartphone, l: 'Mobile money (Flooz / T-Money)', expected: r.expectedMobileMoney, counted: closed ? closed.countedMm : mm, set: setMm },
  ];
  return (
    <>
      <div className="section-label" style={{ marginTop: 18 }}>Rapprochement des cartes</div>
      <div className="grid g4" style={{ gap: 12 }}>
        {[['Cartes attribuées', r.cardsAllocated, 'var(--text)'], ['Cartes vendues', r.cardsSold, 'var(--primary)'],
          [closed ? 'Cartes rendues' : 'Cartes restantes', closed ? closed.cardsReturned : r.cardsRemaining, 'var(--green)'],
          ['Écart', r.cardsGap, r.cardsGap ? 'var(--red)' : 'var(--green)']].map(([l, v, c]) => (
          <div key={l} className="mini-stat">
            <div className="l">{l}</div>
            <div className="v num" style={{ color: c, fontSize: 24 }}>{num(v)}</div>
          </div>
        ))}
      </div>

      <div className="section-label" style={{ marginTop: 22 }}>Comptage des encaissements</div>
      {rows.map((x) => {
        const diff = Number(x.counted || 0) - x.expected;
        return (
          <div key={x.l} className="row" style={{ gap: 16, padding: '12px 0', borderBottom: '1px solid var(--border-2)' }}>
            <IcoBox icon={x.icon} tone="gray" />
            <span className="strong" style={{ flex: 1 }}>{x.l}</span>
            <div style={{ textAlign: 'right' }}>
              <div className="muted" style={{ fontSize: 12 }}>Attendu</div>
              <div className="num">{fcfa(x.expected)}</div>
            </div>
            <div>
              <div className="muted" style={{ fontSize: 12, textAlign: 'right' }}>Compté</div>
              <label className="input" style={{ width: 150 }}>
                <input className="num strong" style={{ textAlign: 'right' }} type="number" min="0" value={x.counted} disabled={!canClose}
                  onChange={(e) => x.set(e.target.value)} />
              </label>
            </div>
            <div style={{ width: 100, textAlign: 'right' }}>
              {diff === 0 ? <Badge tone="green">Conforme</Badge> : <Badge tone="red">{diff > 0 ? '+' : '−'}{fcfa(Math.abs(diff))} F</Badge>}
            </div>
          </div>
        );
      })}

      <div className="row between" style={{ background: 'var(--navy)', color: '#fff', borderRadius: 12, padding: '16px 22px', marginTop: 18 }}>
        <div>
          <div style={{ fontSize: 13, opacity: 0.75 }}>Total encaissé · {num(r.cardsSold)} cartes vendues</div>
          <div className="num" style={{ fontSize: 24, fontWeight: 700, marginTop: 4 }}>{fcfa(counted)} FCFA</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontSize: 13, opacity: 0.75 }}>Écart global</div>
          <div className="num" style={{ fontSize: 21, fontWeight: 700, color: gap ? '#ff7b7b' : '#a3e08b', marginTop: 4 }}>
            {gap === 0 ? '0 F' : `${gap > 0 ? '+' : '−'}${fcfa(Math.abs(gap))} F`}
          </div>
        </div>
      </div>
      <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
        <Button icon={Printer} onClick={() => window.print()}>Imprimer</Button>
        {canClose && <Button variant="primary" icon={CircleCheck} disabled={busy} onClick={close}>Clôturer et générer le rapport</Button>}
        {closed && <Badge tone="gray">Clôturé le {dayMonth(closed.closedAt)}</Badge>}
      </div>
    </>
  );
}

export default function PointsDeVente() {
  const { can } = useSession();
  const [params] = useSearchParams();
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [modal, setModal] = useState(null);
  const [, tick] = useState(0);
  const summary = useApi('/pdv/summary');
  const list = useApi('/pdv');
  const detail = useApi(selected ? `/pdv/${selected}` : null);
  const reload = () => {
    summary.reload();
    list.reload();
    detail.reload();
  };
  useLiveReload(reload, ['operation'], 5000);
  useInterval(() => tick((n) => n + 1), 30_000);

  useEffect(() => {
    if (!selected && list.data?.length) {
      const temp = list.data.find((p) => p.kind === 'TEMPORAIRE' && p.status === 'OUVERT');
      setSelected((temp || list.data[0]).id);
    }
  }, [list.data, selected]);

  const s = summary.data;
  const lastWeek = s?.cardsSoldSameDayLastWeek ? ((s.cardsSoldToday - s.cardsSoldSameDayLastWeek) / s.cardsSoldSameDayLastWeek) * 100 : null;
  const cashShare = s?.cashedToday ? Math.round((s.cashToday / s.cashedToday) * 100) : null;

  return (
    <>
      <PageHeader
        title="Points de vente"
        subtitle="Point de vente permanent au siège et points de vente temporaires · fonctionne hors connexion"
        actions={
          <>
            {can('PDV_GERER') && <Button icon={Store} onClick={() => setModal({ type: 'pdv' })}>Point permanent</Button>}
            {can('PDV_GERER', 'PDV_VENDRE') && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'pdv', temporary: true })}>Nouveau point de vente</Button>}
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Store} label="Points de vente ouverts" value={s ? s.open : '…'} foot={s ? `${s.openPermanent} permanent · ${s.openTemporary} temporaires` : ''} />
        <Kpi icon={CreditCard} tone="green" label="Cartes vendues aujourd’hui" value={s ? num(s.cardsSoldToday) : '…'}
          foot={lastWeek === null ? 'aucune la semaine dernière' : <><Trend value={pct(lastWeek)} down={lastWeek < 0} /> vs même jour la semaine dernière</>} />
        <Kpi icon={HandCoins} tone="navy" label="Encaissé aujourd’hui" value={s ? fcfa(s.cashedToday) : '…'} unit="FCFA"
          foot={cashShare === null ? '—' : `Espèces ${cashShare} % · Mobile money ${100 - cashShare} %`} />
        <Kpi icon={Clock} tone="orange" label="Demandes d’ouverture" value={s ? s.pendingRequests : '…'} foot={s?.toClose ? `${s.toClose} à clôturer` : 'en attente Admin / DAF'} />
      </div>

      <div className="grid split" style={{ gridTemplateColumns: '440px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Points de vente</div>
            {can('PDV_GERER', 'PDV_VENDRE') && <Button size="sm" icon={Plus} onClick={() => setModal({ type: 'pdv', temporary: true })}>Demander une ouverture</Button>}
          </div>
          <div className="stack" style={{ gap: 12, marginTop: 18 }}>
            {list.data?.length === 0 && <Empty>Aucun point de vente</Empty>}
            {(list.data || []).map((p) => {
              const active = p.id === selected;
              return (
                <button
                  key={p.id}
                  onClick={() => setSelected(p.id)}
                  style={{
                    textAlign: 'left', borderRadius: 10, padding: '14px 16px', background: '#fff',
                    border: active ? '2px solid var(--primary)' : '1px solid var(--border)',
                  }}
                >
                  <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}>
                    <IcoBox icon={KIND_ICON[p.kind] || CalendarDays} tone={KIND_TONE[p.kind] || 'purple'} size={34} />
                    <div style={{ flex: 1 }}>
                      <div className="strong" style={{ fontSize: 14 }}>{p.name}</div>
                      <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{subtitle(p)}</div>
                    </div>
                    {p.statusLabel === 'Hors ligne' ? <Badge tone="orange" dot>Hors ligne</Badge> : <StatusBadge status={p.statusLabel} />}
                  </div>
                  <div className="row" style={{ marginTop: 12, gap: 18, color: 'var(--muted)', fontSize: 13 }}>
                    <span className="row" style={{ gap: 6 }}><Users size={15} /> {p.agents} agents</span>
                    <span className="row num" style={{ gap: 6 }}><CreditCard size={15} /> {num(p.cardsSold)} / {num(p.cardsPlanned || p.cardsAllocated)} cartes</span>
                    <span className="strong num" style={{ marginLeft: 'auto', color: 'var(--text)' }}>{p.amountTotal ? money(p.amountTotal) : '— F'}</span>
                  </div>
                  {p.statusLabel === 'Hors ligne' && (
                    <div className="row" style={{ gap: 6, marginTop: 10, color: 'var(--orange)', fontSize: 13 }}>
                      <CloudOff size={15} /> Dernière synchronisation {ago(p.lastSyncAt)} · ventes enregistrées sur place
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </Card>

        <div className="stack">
          {selected && (
            <Async state={detail} minHeight={300}>
              {(p) => (
                <>
                  <Card className="card-pad">
                    <div className="row between">
                      <div className="card-title">{p.name}{['OUVERT', 'A_CLOTURER', 'CLOS'].includes(p.status) ? ' · clôture' : ''}</div>
                      <span className="row" style={{ gap: 8 }}>
                        {p.status === 'OUVERT' && can('PDV_VENDRE') && <Button size="sm" icon={BadgePlus} onClick={() => setModal({ type: 'sell', pdv: p })}>Vendre une carte</Button>}
                        {['OUVERT', 'DEMANDE'].includes(p.status) && can('PDV_GERER') && <Button size="sm" icon={PackagePlus} onClick={() => setModal({ type: 'allocate', pdv: p })}>Attribuer des cartes</Button>}
                        {p.kind !== 'PERMANENT' && ['CLOS', 'REFUSE'].includes(p.status) && can('PDV_GERER') && <Button size="sm" icon={RotateCcw} onClick={() => setModal({ type: 'reopen', pdv: p })}>Rouvrir</Button>}
                      </span>
                    </div>
                    <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
                      <Badge tone={KIND_TONE[p.kind]}>{p.kindLabel}</Badge>
                      {p.kind !== 'PERMANENT' && <span className="muted" style={{ fontSize: 13 }}>{subtitle(p)}</span>}
                      {p.offer && <Badge tone="orange">{p.offer}</Badge>}
                      {p.validatedBy && <Badge tone="gray">Validé par {p.validatedBy} · {dayMonth(p.validatedAt)}</Badge>}
                      {p.validationStatus === 'EN_ATTENTE' && <Badge tone="orange">Validation {p.validationRef} en attente</Badge>}
                      {p.status === 'OUVERT' && (p.statusLabel === 'Hors ligne'
                        ? <Badge tone="orange" dot>Hors ligne · {ago(p.lastSyncAt)}</Badge>
                        : <Badge tone="green" dot>Synchronisé · {ago(p.lastSyncAt)}</Badge>)}
                    </div>
                    {p.reason && <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>{p.reason}</div>}
                    {p.status === 'DEMANDE' ? (
                      <Empty>En attente de validation par l’Admin ou le DAF (écran Validations).</Empty>
                    ) : (
                      <Closure pdv={p} onClosed={reload} />
                    )}
                  </Card>

                  <ProductsCard pdv={p} canManage={can('PDV_GERER')} canSell={can('PDV_VENDRE')} onChanged={reload} />

                  {(p.sessions || []).length > 1 && (
                    <Card className="card-pad">
                      <div className="card-title">Sessions</div>
                      <div className="table-wrap" style={{ marginTop: 8 }}><table className="table">
                        <thead><tr><th>Session</th><th>Période</th><th>État</th><th className="r">Cartes</th><th className="r">Produits</th><th className="r">Écart</th></tr></thead>
                        <tbody>{p.sessions.map((x) => (
                          <tr key={x.sessionNo}><td className="strong">{x.sessionNo}</td><td className="muted">{dayMonth(x.startDate)} → {dayMonth(x.endDate)}</td>
                            <td>{{ DEMANDE: 'Demandée', OUVERT: 'Ouverte', A_CLOTURER: 'À clôturer', CLOS: 'Close', REFUSE: 'Refusée' }[x.status]}</td>
                            <td className="r num">{x.cardsSold ?? '—'}</td><td className="r num">{x.productsSoldAmount != null ? fcfa(x.productsSoldAmount) : '—'}</td>
                            <td className="r num">{x.gap != null ? fcfa(x.gap) : '—'}</td></tr>
                        ))}</tbody>
                      </table></div>
                    </Card>
                  )}

                  <Card className="card-pad">
                    <div className="row between">
                      <div className="card-title">Agents affectés</div>
                      <span className="muted" style={{ fontSize: 13 }}>Ventes de cartes</span>
                    </div>
                    <div style={{ marginTop: 8 }}>
                      {(p.agentsList || []).length === 0 && <Empty>Aucun agent</Empty>}
                      {(p.agentsList || []).map((a) => (
                        <div key={a.id} className="list-item" style={{ borderBottom: 0, padding: '9px 0' }}>
                          <Avatar initials={a.initials} color={avatarColor(a.id)} size={38} photo={fileUrl(a.photoUrl)} />
                          <div style={{ flex: 1 }}>
                            <div className="strong">{a.name}</div>
                            <div className="muted" style={{ fontSize: 13 }}>{a.role === 'SUPERVISEUR' ? 'Superviseur' : 'Agent'} · pièce d’identité vérifiée à chaque vente</div>
                          </div>
                          <span className="strong num" style={{ width: 50, textAlign: 'right' }}>{num(a.cardsSold)}</span>
                          <span className="muted" style={{ width: 60, textAlign: 'right' }}>cartes</span>
                        </div>
                      ))}
                    </div>
                  </Card>
                </>
              )}
            </Async>
          )}
        </div>
      </div>

      {modal?.type === 'pdv' && <PdvModal temporary={modal.temporary} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'allocate' && <AllocateModal pdv={modal.pdv} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'sell' && <SellModal pdv={modal.pdv} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'reopen' && <ReopenModal pdv={modal.pdv} onClose={() => setModal(null)} onSaved={reload} />}
    </>
  );
}
