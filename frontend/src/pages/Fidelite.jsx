import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Gift, Coins, Percent, Users, Pencil, Plus, Search, SlidersHorizontal } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, fcfa, money, num, masked } from '../lib/format.js';

const TABS = ['Clients', 'Règles de points', 'Remises', 'Mouvements'];
const CATEGORIES = [
  { value: 'CARBURANT', label: 'Carburant' }, { value: 'LUBRIFIANT', label: 'Lubrifiants' },
  { value: 'GAZ', label: 'Gaz' }, { value: 'ACCESSOIRE', label: 'Accessoires' },
];
const CATEGORY_LABEL = Object.fromEntries(CATEGORIES.map((c) => [c.value, c.label]));
const PROFILES = [
  { value: 'GRAND_PUBLIC', label: 'Grand public' }, { value: 'EMPLOYE_STATION', label: 'Employé station' },
  { value: 'EMPLOYE_GROUPE', label: 'Employé groupe' }, { value: 'PARTENAIRE', label: 'Partenaire' },
];
const PROFILE_LABEL = Object.fromEntries(PROFILES.map((p) => [p.value, p.label]));
const SCOPES = [
  { value: 'PALIER', label: 'Palier de consommation' }, { value: 'GROUPE', label: 'Flotte (contrat)' }, { value: 'PROFIL', label: 'Profil' },
];
const SCOPE_LABEL = { PALIER: 'Palier', GROUPE: 'Flotte', PROFIL: 'Profil' };
const MOVE = {
  GAIN: ['Gain', 'green'], UTILISATION: ['Utilisation', 'blue'], EXPIRATION: ['Expiration', 'gray'], AJUSTEMENT: ['Ajustement', 'orange'],
};
const nn = (v) => (v === '' || v == null ? null : Number(v));

function RuleModal({ rule, products, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    target: rule?.productId ? 'PRODUIT' : 'CATEGORIE', category: rule?.category || 'CARBURANT',
    productCode: products.find((p) => p.id === rule?.productId)?.code || '', points: rule?.pointsPer1000 ?? '', active: rule?.active ?? true,
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={rule ? 'Modifier la règle de points' : 'Nouvelle règle de points'} onClose={onClose} onSubmit={async () => {
      await post('/loyalty/rules', {
        category: f.target === 'CATEGORIE' ? f.category : null, productCode: f.target === 'PRODUIT' ? f.productCode : null,
        pointsPer1000: Number(f.points), active: f.active,
      });
      notify('Règle de points enregistrée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="S'applique à">
          <SelectInput value={f.target} onChange={set('target')} options={[{ value: 'CATEGORIE', label: 'Une catégorie' }, { value: 'PRODUIT', label: 'Un produit' }]} />
        </Field>
        {f.target === 'CATEGORIE' ? (
          <Field label="Catégorie"><SelectInput value={f.category} onChange={set('category')} options={CATEGORIES} /></Field>
        ) : (
          <Field label="Produit">
            <SelectInput value={f.productCode} onChange={set('productCode')} placeholder="Choisir…" required
              options={products.filter((p) => p.category !== 'CARTE').map((p) => ({ value: p.code, label: p.name }))} />
          </Field>
        )}
        <Field label="Points par 1 000 F" hint="Une règle de produit l'emporte sur celle de sa catégorie">
          <TextInput type="number" min="0" step="0.5" value={f.points} onChange={set('points')} required />
        </Field>
        <Field label="État">
          <SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
            options={[{ value: 'oui', label: 'Active' }, { value: 'non', label: 'Suspendue' }]} />
        </Field>
      </div>
    </FormModal>
  );
}

function DiscountModal({ discount, products, fleets, onClose, onSaved }) {
  const notify = useToast();
  const d = discount || {};
  const [f, setF] = useState({
    name: d.name || '', scope: d.scope || 'PALIER', category: d.category || '',
    productCode: products.find((p) => p.id === d.productId)?.code || '', minConsumption: d.minConsumption ?? '',
    cardGroupId: d.cardGroupId ? String(d.cardGroupId) : '', profile: d.profile || 'PARTENAIRE', discountPct: d.discountPct ?? '',
    active: d.active ?? true,
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={discount ? `Remise · ${discount.name}` : 'Nouvelle remise'} onClose={onClose} onSubmit={async () => {
      const body = {
        name: f.name, scope: f.scope, category: f.productCode ? null : f.category || null, productCode: f.productCode || null,
        minConsumption: nn(f.minConsumption), cardGroupId: nn(f.cardGroupId), profile: f.profile, discountPct: Number(f.discountPct), active: f.active,
      };
      if (discount) await put(`/discounts/${discount.id}`, body);
      else await post('/discounts', body);
      notify(discount ? 'Remise modifiée' : 'Remise créée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Nom" full><TextInput value={f.name} onChange={set('name')} required placeholder="Ex. Gros rouleurs, contrat Translom…" /></Field>
        <Field label="Accordée selon"><SelectInput value={f.scope} onChange={set('scope')} options={SCOPES} /></Field>
        {f.scope === 'PALIER' && (
          <Field label="Consommation sur 12 mois (F)" hint="À partir de ce montant d'achats par carte du titulaire">
            <TextInput type="number" min="0" step="10000" value={f.minConsumption} onChange={set('minConsumption')} required />
          </Field>
        )}
        {f.scope === 'GROUPE' && (
          <Field label="Flotte">
            <SelectInput value={f.cardGroupId} onChange={set('cardGroupId')} placeholder="Choisir…" required
              options={fleets.map((g) => ({ value: String(g.id), label: `${g.holderName} · ${g.name}` }))} />
          </Field>
        )}
        {f.scope === 'PROFIL' && (
          <Field label="Profil du titulaire"><SelectInput value={f.profile} onChange={set('profile')} options={PROFILES} /></Field>
        )}
        <Field label="Catégorie" hint="Vide : tous les produits">
          <SelectInput value={f.category} onChange={set('category')} placeholder="Toutes" options={CATEGORIES} />
        </Field>
        <Field label="Produit précis" hint="Prioritaire sur la catégorie">
          <SelectInput value={f.productCode} onChange={set('productCode')} placeholder="Aucun"
            options={products.filter((p) => p.category !== 'CARTE').map((p) => ({ value: p.code, label: p.name }))} />
        </Field>
        <Field label="Remise (%)"><TextInput type="number" min="0.1" max="99" step="0.1" value={f.discountPct} onChange={set('discountPct')} required /></Field>
        <Field label="État">
          <SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
            options={[{ value: 'oui', label: 'Active' }, { value: 'non', label: 'Suspendue' }]} />
        </Field>
      </div>
      <div className="muted" style={{ marginTop: 12, fontSize: 13 }}>
        À chaque vente par carte, la meilleure remise applicable est appliquée ligne par ligne (jamais cumulée) ; les ventes de cartes n'en bénéficient pas.
      </div>
    </FormModal>
  );
}

function AdjustModal({ holder, onClose, onSaved }) {
  const notify = useToast();
  const [points, setPoints] = useState('');
  const [note, setNote] = useState('');
  return (
    <FormModal title={`Ajuster les points · ${holder.name}`} onClose={onClose} onSubmit={async () => {
      await post('/loyalty/adjust', { holderId: holder.id, points: Number(points), note });
      notify('Points ajustés');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Points (+ ou −)" hint={`Solde actuel : ${fcfa(holder.loyaltyPoints)} points`}>
          <TextInput type="number" value={points} onChange={setPoints} required autoFocus />
        </Field>
        <Field label="Motif"><TextInput value={note} onChange={setNote} required placeholder="Geste commercial, correction…" /></Field>
      </div>
    </FormModal>
  );
}

function ClientsTab({ edit, pointValue, onModal, version }) {
  const [q, setQ] = useState('');
  const holders = useApi(`/loyalty/holders${qs({ q, v: version })}`);
  return (
    <>
      <div className="row" style={{ padding: '12px 20px 0' }}>
        <label className="input" style={{ height: 36, width: 280 }}>
          <Search size={16} strokeWidth={1.8} />
          <input placeholder="Titulaire" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
      </div>
      <Async state={holders}>
        {(rows) => (!rows.length ? <Empty>Aucun client</Empty> : (
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Titulaire</th><th>Type</th><th className="r">Achats 12 mois</th><th>Palier</th><th className="r">Points</th>
              <th className="r">Valeur</th><th>Dernier mouvement</th><th /></tr></thead>
            <tbody>
              {rows.map((h) => (
                <tr key={h.id}>
                  <td className="strong">{h.name}</td>
                  <td>{h.kind === 'ENTREPRISE' ? 'Entreprise' : 'Particulier'}</td>
                  <td className="r num">{fcfa(h.consumption12m)}</td>
                  <td>{h.tier ? <Badge tone="purple">{h.tier}</Badge> : <span className="muted">—</span>}</td>
                  <td className="r num strong">{fcfa(h.loyaltyPoints)}</td>
                  <td className="r num muted">{money(h.loyaltyPoints * pointValue)}</td>
                  <td className="muted num">{h.lastMovementAt ? dateTime(h.lastMovementAt) : '—'}</td>
                  <td className="r" style={{ whiteSpace: 'nowrap' }}>
                    {h.cardId && <Link className="btn btn-sm" to={`/cartes/${h.cardId}`}>Carte</Link>}{' '}
                    {edit && <Button size="sm" icon={SlidersHorizontal} title="Ajuster" onClick={() => onModal({ type: 'adjust', holder: h })} />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        ))}
      </Async>
    </>
  );
}

function MovementsTab({ version }) {
  const [page, setPage] = useState(0);
  const moves = useApi(`/loyalty/movements?page=${page}&size=25&v=${version}`);
  return (
    <Async state={moves}>
      {(data) => (!data.items.length ? <Empty>Aucun mouvement</Empty> : (
        <>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Date</th><th>Titulaire</th><th>Carte</th><th>Mouvement</th><th className="r">Points</th><th className="r">Solde</th>
              <th className="r">Montant</th><th>Opérateur</th><th>Note</th></tr></thead>
            <tbody>
              {data.items.map((m) => (
                <tr key={m.id}>
                  <td className="muted num">{dateTime(m.at)}</td>
                  <td className="strong">{m.holderName}</td>
                  <td className="num">{m.number ? masked(m.number) : '—'}</td>
                  <td><Badge tone={MOVE[m.kind]?.[1]}>{MOVE[m.kind]?.[0] || m.kind}</Badge></td>
                  <td className="r num strong" style={{ color: m.points > 0 ? 'var(--green)' : 'var(--red)' }}>{m.points > 0 ? '+' : ''}{fcfa(m.points)}</td>
                  <td className="r num muted">{fcfa(m.balanceAfter)}</td>
                  <td className="r num">{m.kind === 'GAIN' ? (m.saleTotal ? `achat ${fcfa(m.saleTotal)}` : '—') : m.amount ? `${fcfa(m.amount)} F crédités` : '—'}</td>
                  <td>{m.employeeName || (m.kind === 'GAIN' ? 'Automatique' : '—')}</td>
                  <td className="muted">{m.note || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <Pager data={data} page={page} onPage={setPage} label="mouvements" />
        </>
      ))}
    </Async>
  );
}

export default function Fidelite() {
  const { can } = useSession();
  const edit = can('FIDELITE');
  const [tab, setTab] = useState(TABS[0]);
  const [modal, setModal] = useState(null);
  const [version, setVersion] = useState(0);
  const summary = useApi('/loyalty/summary');
  const rules = useApi('/loyalty/rules');
  const discounts = useApi('/discounts');
  const products = useApi('/products');
  const fleets = useApi(can('FLOTTES', 'VALIDER_MONTANTS') ? '/fleets' : null);
  const reloadAll = () => {
    summary.reload();
    rules.reload();
    discounts.reload();
    setVersion((v) => v + 1);
  };
  useLiveReload(() => summary.reload(), ['operation'], 10000);
  const done = () => {
    setModal(null);
    reloadAll();
  };
  const s = summary.data || {};
  const pointValue = s.pointValue || 0;

  return (
    <>
      <PageHeader title="Fidélité et remises" subtitle={`Points gagnés à chaque achat par carte · 1 point = ${pointValue} F, utilisable dès ${s.minRedeem ?? '…'} points · cartes VIP : points × ${s.vipMultiplier ?? '…'}`}
        actions={edit && (
          <>
            {tab === 'Règles de points' && <Button icon={Plus} onClick={() => setModal({ type: 'rule' })}>Règle</Button>}
            {tab === 'Remises' && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'discount' })}>Nouvelle remise</Button>}
          </>
        )} />
      <div className="grid g4">
        <Kpi icon={Gift} label="Points gagnés ce mois" value={num(s.earnedMonth)} unit="pts" foot={`${num(s.members)} client(s) avec des points`} />
        <Kpi icon={Coins} tone="green" label="Points utilisés ce mois" value={num(s.usedMonth)} unit="pts" foot={`${money(s.creditedMonth)} crédités sur les cartes`} />
        <Kpi icon={Users} tone="navy" label="Points en circulation" value={num(s.outstanding)} unit="pts" foot={`valeur ${money((s.outstanding || 0) * pointValue)}`} />
        <Kpi icon={Percent} tone="orange" label="Remises ce mois" value={fcfa(s.discountsMonth)} unit="F" foot="accordées sur les ventes par carte" />
      </div>
      <Card style={{ marginTop: 16 }}>
        <div className="card-head" style={{ alignItems: 'center' }}>
          <Segmented items={TABS} value={tab} onChange={setTab} />
          {s.enabled === false && <Badge tone="red">Programme suspendu (Paramètres)</Badge>}
        </div>
        {tab === 'Clients' && <ClientsTab edit={edit} pointValue={pointValue} onModal={setModal} version={version} />}
        {tab === 'Mouvements' && <MovementsTab version={version} />}
        {tab === 'Règles de points' && (
          <Async state={rules}>
            {(rows) => (
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Catégorie ou produit</th><th className="r">Points par 1 000 F</th><th>Exemple</th><th>État</th><th>Modifiée</th><th /></tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td className="strong">{r.productName || CATEGORY_LABEL[r.category] || r.category}{r.productName && <span className="muted"> · produit</span>}</td>
                      <td className="r num strong">{Number(r.pointsPer1000)}</td>
                      <td className="muted">achat de 20 000 F → {num(20 * r.pointsPer1000)} pts ({money(20 * r.pointsPer1000 * pointValue)})</td>
                      <td>{r.active ? <Badge tone="green">Active</Badge> : <Badge tone="gray">Suspendue</Badge>}</td>
                      <td className="muted num">{dateTime(r.updatedAt)}</td>
                      <td className="r">{edit && <Button size="sm" icon={Pencil} title="Modifier" onClick={() => setModal({ type: 'rule', rule: r })} />}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </Async>
        )}
        {tab === 'Remises' && (
          <Async state={discounts}>
            {(rows) => (!rows.length ? <Empty>Aucune remise</Empty> : (
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Remise</th><th>Selon</th><th>Condition</th><th>Produits</th><th className="r">Taux</th><th className="r">Utilisations</th>
                  <th className="r">Accordé</th><th>État</th><th /></tr></thead>
                <tbody>
                  {rows.map((d) => (
                    <tr key={d.id} style={{ opacity: d.active ? 1 : 0.6 }}>
                      <td className="strong">{d.name}</td>
                      <td><Badge tone="blue">{SCOPE_LABEL[d.scope]}</Badge></td>
                      <td>{d.scope === 'PALIER' ? `dès ${money(d.minConsumption)} sur 12 mois` : d.scope === 'GROUPE' ? `${d.holderName} · ${d.groupName}` : PROFILE_LABEL[d.profile]}</td>
                      <td>{d.productName || CATEGORY_LABEL[d.category] || 'Tous'}</td>
                      <td className="r num strong">{Number(d.discountPct)} %</td>
                      <td className="r num">{num(d.uses)}</td>
                      <td className="r num">{money(d.granted)}</td>
                      <td>{d.active ? <Badge tone="green">Active</Badge> : <Badge tone="gray">Suspendue</Badge>}</td>
                      <td className="r">{edit && <Button size="sm" icon={Pencil} title="Modifier" onClick={() => setModal({ type: 'discount', discount: d })} />}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            ))}
          </Async>
        )}
      </Card>

      {modal?.type === 'rule' && <RuleModal rule={modal.rule} products={products.data || []} onClose={() => setModal(null)} onSaved={done} />}
      {modal?.type === 'discount' && (
        <DiscountModal discount={modal.discount} products={products.data || []} fleets={fleets.data || []} onClose={() => setModal(null)} onSaved={done} />
      )}
      {modal?.type === 'adjust' && <AdjustModal holder={modal.holder} onClose={() => setModal(null)} onSaved={done} />}
    </>
  );
}
