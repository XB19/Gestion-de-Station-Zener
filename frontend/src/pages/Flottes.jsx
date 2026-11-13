import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  CarFront, Wallet, HandCoins, Undo2, Link as LinkIcon, Pencil, Plus, Users, Fuel, Search, Building2, Gauge, IdCard, ArrowLeftRight,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, fcfa, money, num } from '../lib/format.js';

const TABS = ['Cartes', 'Véhicules', 'Chauffeurs', 'Compte flotte'];
const PAYMENTS = [{ value: 'VIREMENT', label: 'Virement' }, { value: 'ESPECES', label: 'Espèces' },
  { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'CARTE_BANCAIRE', label: 'Carte bancaire' }];
const STATUS_TONES = { ACTIVE: 'green', A_ACTIVER: 'orange', BLOQUEE: 'red', INACTIVE: 'gray' };
const MOVE_LABELS = { RECHARGE: 'Recharge du compte', DOTATION: 'Dotation', REPRISE: 'Reprise', AJUSTEMENT: 'Ajustement', TRANSFERT: 'Transfert' };
const sistersOf = (f) => (typeof f.sisterFleets === 'string' ? JSON.parse(f.sisterFleets) : f.sisterFleets) || [];

const n = (v) => (v === '' || v == null ? null : Number(v));

// ---------------------------------------------------------------- fenêtres

function RechargeModal({ fleet, threshold, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ amount: '', paymentMethod: 'VIREMENT', paymentRef: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const above = Number(f.amount) > threshold;
  const fee = Math.round((Number(f.amount) || 0) * Number(fleet.rechargeFeePct || 0) / 100);
  return (
    <FormModal title={`Recharger le compte · ${fleet.holderName} · ${fleet.name}`} submitLabel="Recharger" onClose={onClose}
      onSubmit={async () => {
        const r = await post(`/fleets/${fleet.id}/recharges`, { amount: Number(f.amount), paymentMethod: f.paymentMethod,
          paymentRef: f.paymentRef || null, note: f.note || null });
        notify(r.status === 'CREDITEE' ? `Compte flotte crédité de ${money(r.amount)}` : `Au-dessus du palier : validation Comptable puis DAF (${r.ref})`,
          r.status === 'CREDITEE' ? 'success' : 'info');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Montant (F)" hint={above ? `Au-dessus du palier de ${fcfa(threshold)} F : validation Comptable puis DAF` : 'Crédité immédiatement'}>
          <TextInput type="number" min="1" step="1000" value={f.amount} onChange={set('amount')} required />
        </Field>
        <Field label="Paiement"><SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={PAYMENTS} /></Field>
        <Field label="Référence du paiement"><TextInput value={f.paymentRef} onChange={set('paymentRef')} placeholder="N° de virement…" /></Field>
        <Field label="Note"><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
      {(fleet.rechargeFeePct > 0 || fleet.loadUpLimit) && (
        <div className="muted" style={{ marginTop: 12, fontSize: 13 }}>
          {fleet.rechargeFeePct > 0 && <>Frais de {Number(fleet.rechargeFeePct)} % : {fcfa(fee)} F · crédité {fcfa((Number(f.amount) || 0) - fee)} F. </>}
          {fleet.loadUpLimit && <>Compte limité à {fcfa(fleet.loadUpLimit)} F (solde actuel {fcfa(fleet.balance)} F).</>}
        </div>
      )}
    </FormModal>
  );
}

/** Transfert du compte flotte vers une autre flotte de la même entreprise. */
function FleetTransferModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const sisters = sistersOf(fleet);
  const [f, setF] = useState({ toGroupId: String(sisters[0]?.id || ''), amount: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Transférer depuis ${fleet.name}`} submitLabel="Transférer" onClose={onClose}
      onSubmit={async () => {
        await post(`/fleets/${fleet.id}/transfer`, { toGroupId: Number(f.toGroupId), amount: Number(f.amount), note: f.note || null });
        notify(`${money(Number(f.amount))} transférés vers ${sisters.find((x) => String(x.id) === f.toGroupId)?.name}`);
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Vers la flotte">
          <SelectInput value={f.toGroupId} onChange={set('toGroupId')} options={sisters.map((x) => ({ value: String(x.id), label: x.name }))} />
        </Field>
        <Field label="Montant (F)" hint={`Compte flotte : ${money(fleet.balance)}`}>
          <TextInput type="number" min="1" max={fleet.balance} value={f.amount} onChange={set('amount')} required />
        </Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

/** Dotation d'une ou plusieurs cartes par le compte flotte. */
function AllocateModal({ fleet, cards, onClose, onSaved }) {
  const notify = useToast();
  const usable = cards.filter((c) => c.status === 'ACTIVE' || c.status === 'A_ACTIVER');
  const [amounts, setAmounts] = useState(() => Object.fromEntries(usable.map((c) => [c.id, ''])));
  const [all, setAll] = useState('');
  const [note, setNote] = useState('');
  const total = Object.values(amounts).reduce((s, v) => s + (Number(v) || 0), 0);
  return (
    <FormModal title={`Doter les cartes · ${fleet.name}`} submitLabel={`Doter · ${money(total)}`} wide onClose={onClose}
      onSubmit={async () => {
        const lines = Object.entries(amounts).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ cardId: Number(id), amount: Number(v) }));
        if (!lines.length) throw new Error('Indiquez un montant pour au moins une carte');
        await post(`/fleets/${fleet.id}/allocate`, { lines, note: note || null });
        notify(`${lines.length} carte(s) dotée(s) · ${money(total)}`);
        onSaved();
      }}>
      <div className="row" style={{ gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
        <span className="muted">Compte flotte : <b className="num" style={{ color: total > fleet.balance ? 'var(--red)' : 'var(--text)' }}>{money(fleet.balance)}</b></span>
        <span style={{ marginLeft: 'auto' }} className="row">
          <label className="input" style={{ height: 34, width: 150 }}>
            <input type="number" min="0" step="1000" value={all} onChange={(e) => setAll(e.target.value)} placeholder="Même montant" />
          </label>
          <Button size="sm" type="button" onClick={() => setAmounts(Object.fromEntries(usable.map((c) => [c.id, all])))}>Appliquer à toutes</Button>
        </span>
      </div>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>Carte</th><th>Véhicule · chauffeur</th><th className="r">Solde</th><th className="r">Dotation (F)</th></tr></thead>
          <tbody>
            {usable.map((c) => (
              <tr key={c.id}>
                <td className="num strong">{c.numberFormatted}</td>
                <td>{[c.plate, c.driverName].filter(Boolean).join(' · ') || '—'}</td>
                <td className="r num">{fcfa(c.balance)}</td>
                <td className="r" style={{ width: 160 }}>
                  <label className="input" style={{ height: 34 }}>
                    <input type="number" min="0" step="1000" value={amounts[c.id]} onChange={(e) => setAmounts((x) => ({ ...x, [c.id]: e.target.value }))} />
                  </label>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Field label="Note" full><TextInput value={note} onChange={setNote} placeholder="Dotation mensuelle…" /></Field>
    </FormModal>
  );
}

function ReclaimModal({ fleet, card, onClose, onSaved }) {
  const notify = useToast();
  const [amount, setAmount] = useState(String(card.balance));
  const [note, setNote] = useState('');
  return (
    <FormModal title={`Reprise · ${card.numberFormatted}`} submitLabel="Reprendre" onClose={onClose}
      onSubmit={async () => {
        await post(`/fleets/${fleet.id}/reclaim`, { cardId: card.id, amount: n(amount), note: note || null });
        notify(`${money(Number(amount))} repris sur la carte`);
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Montant repris (F)" hint={`Solde de la carte : ${money(card.balance)} · revient au compte flotte`}>
          <TextInput type="number" min="1" max={card.balance} value={amount} onChange={setAmount} required />
        </Field>
        <Field label="Note"><TextInput value={note} onChange={setNote} placeholder="Chauffeur parti, carte perdue…" /></Field>
      </div>
    </FormModal>
  );
}

function AssignCardModal({ card, vehicles, drivers, onClose, onSaved }) {
  const notify = useToast();
  const [vehicleId, setVehicleId] = useState(card.vehicleId ? String(card.vehicleId) : '');
  const [driverId, setDriverId] = useState(card.driverId ? String(card.driverId) : '');
  return (
    <FormModal title={`Affecter · ${card.numberFormatted}`} onClose={onClose}
      onSubmit={async () => {
        await put(`/fleets/cards/${card.id}/assignment`, { vehicleId: n(vehicleId), driverId: n(driverId) });
        notify('Affectation enregistrée');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Véhicule">
          <SelectInput value={vehicleId} onChange={setVehicleId} placeholder="Aucun véhicule"
            options={vehicles.filter((v) => v.active).map((v) => ({ value: String(v.id), label: `${v.plate} · ${[v.brand, v.model].filter(Boolean).join(' ') || v.typeLabel}` }))} />
        </Field>
        <Field label="Chauffeur">
          <SelectInput value={driverId} onChange={setDriverId} placeholder="Aucun chauffeur"
            options={drivers.filter((d) => d.active).map((d) => ({ value: String(d.id), label: d.name }))} />
        </Field>
      </div>
    </FormModal>
  );
}

function VehicleModal({ fleet, vehicle, types, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    plate: vehicle?.plate || '', typeCode: vehicle?.typeCode || 'CAMION', brand: vehicle?.brand || '', model: vehicle?.model || '',
    color: vehicle?.color || '', productCode: vehicle?.productName ? { Gasoil: 'GASOIL', Super: 'SUPER', Pétrole: 'PETROLE' }[vehicle.productName] || 'GASOIL' : 'GASOIL',
    tankCapacity: vehicle?.tankCapacity ?? '', odometerKm: vehicle?.odometerKm ?? '', active: vehicle ? vehicle.active : true, note: vehicle?.note || '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={vehicle ? `Véhicule ${vehicle.plate}` : 'Nouveau véhicule'} wide onClose={onClose}
      onSubmit={async () => {
        const body = { holderId: fleet.holderId, groupId: fleet.id, ...f, tankCapacity: n(f.tankCapacity), odometerKm: n(f.odometerKm) };
        if (vehicle) await put(`/vehicles/${vehicle.id}`, body);
        else await post('/vehicles', body);
        notify(vehicle ? 'Véhicule modifié' : 'Véhicule ajouté');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Immatriculation"><TextInput value={f.plate} onChange={set('plate')} required placeholder="TG 1234 AB" /></Field>
        <Field label="Type"><SelectInput value={f.typeCode} onChange={set('typeCode')} options={(types || []).map((t) => ({ value: t.code, label: t.label }))} /></Field>
        <Field label="Marque"><TextInput value={f.brand} onChange={set('brand')} /></Field>
        <Field label="Modèle"><TextInput value={f.model} onChange={set('model')} /></Field>
        <Field label="Couleur"><TextInput value={f.color} onChange={set('color')} /></Field>
        <Field label="Carburant">
          <SelectInput value={f.productCode} onChange={set('productCode')} options={[{ value: 'GASOIL', label: 'Gasoil' }, { value: 'SUPER', label: 'Super' }, { value: 'PETROLE', label: 'Pétrole' }]} />
        </Field>
        <Field label="Réservoir (L)"><TextInput type="number" min="1" value={f.tankCapacity} onChange={set('tankCapacity')} /></Field>
        <Field label="Kilométrage"><TextInput type="number" min="0" value={f.odometerKm} onChange={set('odometerKm')} /></Field>
        {vehicle && (
          <Field label="État"><SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
            options={[{ value: 'oui', label: 'En service' }, { value: 'non', label: 'Retiré du parc' }]} /></Field>
        )}
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function DriverModal({ fleet, driver, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    name: driver?.name || '', phone: driver?.phone || '', licenceNumber: driver?.licenceNumber || '',
    licenceExpires: driver?.licenceExpires || '', idDocNumber: driver?.idDocNumber || '', active: driver ? driver.active : true, note: driver?.note || '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={driver ? `Chauffeur · ${driver.name}` : 'Nouveau chauffeur'} wide onClose={onClose}
      onSubmit={async () => {
        const body = { holderId: fleet.holderId, groupId: fleet.id, ...f, licenceExpires: f.licenceExpires || null };
        if (driver) await put(`/drivers/${driver.id}`, body);
        else await post('/drivers', body);
        notify(driver ? 'Chauffeur modifié' : 'Chauffeur ajouté');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Nom" full><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Pièce d'identité"><TextInput value={f.idDocNumber} onChange={set('idDocNumber')} /></Field>
        <Field label="N° de permis"><TextInput value={f.licenceNumber} onChange={set('licenceNumber')} /></Field>
        <Field label="Permis valable jusqu'au"><TextInput type="date" value={f.licenceExpires} onChange={set('licenceExpires')} /></Field>
        {driver && (
          <Field label="État"><SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
            options={[{ value: 'oui', label: 'Actif' }, { value: 'non', label: 'Inactif' }]} /></Field>
        )}
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

function AssignDriverModal({ vehicle, drivers, onClose, onSaved }) {
  const notify = useToast();
  const [driverId, setDriverId] = useState(vehicle.driverId ? String(vehicle.driverId) : '');
  const [note, setNote] = useState('');
  const history = useApi(`/vehicles/${vehicle.id}/assignments`);
  return (
    <FormModal title={`Chauffeur du véhicule ${vehicle.plate}`} onClose={onClose}
      onSubmit={async () => {
        await post(`/vehicles/${vehicle.id}/assign`, { driverId: n(driverId), note: note || null });
        notify('Affectation enregistrée · les cartes du véhicule suivent le chauffeur');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Chauffeur" full>
          <SelectInput value={driverId} onChange={setDriverId} placeholder="Sans chauffeur"
            options={drivers.filter((d) => d.active).map((d) => ({ value: String(d.id), label: `${d.name}${d.plate && d.vehicleId !== vehicle.id ? ` (conduit ${d.plate})` : ''}` }))} />
        </Field>
        <Field label="Note" full><TextInput value={note} onChange={setNote} /></Field>
      </div>
      {(history.data || []).length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="section-label">Historique</div>
          {history.data.map((a) => (
            <div key={a.id} className="kv"><span className="k">{a.driverName}</span>
              <span className="v num">{dateTime(a.startedAt)} → {a.endedAt ? dateTime(a.endedAt) : 'en cours'}</span></div>
          ))}
        </div>
      )}
    </FormModal>
  );
}

function FleetModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ code: fleet.code || '', contactName: fleet.contactName || '', phone: fleet.phone || '',
    address: fleet.address || '', note: fleet.note || '', active: fleet.active, loadUpLimit: fleet.loadUpLimit ?? '',
    rechargeFeePct: fleet.rechargeFeePct ?? 0 });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Flotte · ${fleet.holderName} · ${fleet.name}`} onClose={onClose}
      onSubmit={async () => {
        await put(`/fleets/${fleet.id}`, { ...f, loadUpLimit: n(f.loadUpLimit), rechargeFeePct: n(f.rechargeFeePct) ?? 0 });
        notify('Flotte modifiée');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Code"><TextInput value={f.code} onChange={set('code')} /></Field>
        <Field label="État"><SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
          options={[{ value: 'oui', label: 'Active' }, { value: 'non', label: 'Désactivée' }]} /></Field>
        <Field label="Contact"><TextInput value={f.contactName} onChange={set('contactName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Plafond du compte (F)" hint="Solde maximal du compte flotte · vide : sans plafond">
          <TextInput type="number" min="1" step="1000" value={f.loadUpLimit} onChange={set('loadUpLimit')} />
        </Field>
        <Field label="Frais de recharge (%)" hint="Retenus sur chaque recharge du compte">
          <TextInput type="number" min="0" max="100" step="0.1" value={f.rechargeFeePct} onChange={set('rechargeFeePct')} />
        </Field>
        <Field label="Adresse" full><TextInput value={f.address} onChange={set('address')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

// ---------------------------------------------------------------- onglets

function CardsTab({ fleet, edit, onModal }) {
  if (!fleet.cards.length) return <Empty>Aucune carte dans cette flotte</Empty>;
  return (
    <div className="table-wrap">
      <table className="table">
        <thead><tr><th>Carte</th><th>Véhicule</th><th>Chauffeur</th><th className="r">Solde (F)</th><th className="r">30 jours (F)</th><th>Dernier plein</th>{edit && <th />}</tr></thead>
        <tbody>
          {fleet.cards.map((c) => (
            <tr key={c.id}>
              <td><Link to={`/cartes/${c.id}`} className="num strong">{c.numberFormatted}</Link> <Badge tone={STATUS_TONES[c.status] || 'gray'}>{c.statusLabel}</Badge></td>
              <td>{c.plate ? <><b>{c.plate}</b> <span className="muted">{c.vehicleLabel || c.vehicleType}</span></> : <span className="muted">—</span>}</td>
              <td>{c.driverName || <span className="muted">—</span>}</td>
              <td className="r num strong">{fcfa(c.balance)}</td>
              <td className="r num">{fcfa(c.spent30d)}</td>
              <td className="muted num">{c.lastSaleAt ? dateTime(c.lastSaleAt) : '—'}</td>
              {edit && (
                <td className="r" style={{ whiteSpace: 'nowrap' }}>
                  <Button size="sm" icon={HandCoins} title="Doter" onClick={() => onModal({ type: 'allocate1', card: c })} disabled={!['ACTIVE', 'A_ACTIVER'].includes(c.status)} />{' '}
                  <Button size="sm" icon={Undo2} title="Reprendre" onClick={() => onModal({ type: 'reclaim', card: c })} disabled={!c.balance} />{' '}
                  <Button size="sm" icon={LinkIcon} title="Véhicule et chauffeur" onClick={() => onModal({ type: 'assign', card: c })} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function VehiclesTab({ fleet, edit, onModal }) {
  return (
    <>
      {edit && <div className="row" style={{ padding: '12px 20px 0' }}><Button size="sm" icon={Plus} onClick={() => onModal({ type: 'vehicle' })}>Véhicule</Button></div>}
      {!fleet.vehicles.length ? <Empty>Aucun véhicule</Empty> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Immatriculation</th><th>Type</th><th>Marque · modèle</th><th>Carburant</th><th className="r">Réservoir</th><th className="r">Kilométrage</th><th>Chauffeur</th><th>Cartes</th>{edit && <th />}</tr></thead>
            <tbody>
              {fleet.vehicles.map((v) => (
                <tr key={v.id} style={{ opacity: v.active ? 1 : 0.55 }}>
                  <td className="strong">{v.plate}</td>
                  <td>{v.typeLabel}</td>
                  <td>{[v.brand, v.model].filter(Boolean).join(' ') || '—'}{v.color ? <span className="muted"> · {v.color}</span> : ''}</td>
                  <td>{v.productName || '—'}</td>
                  <td className="r num">{v.tankCapacity ? `${num(v.tankCapacity)} L` : '—'}</td>
                  <td className="r num">{v.odometerKm != null ? `${num(v.odometerKm)} km` : '—'}</td>
                  <td>{v.driverName || <span className="muted">—</span>}</td>
                  <td className="muted num">{v.cards ? `···${v.cards.replaceAll(', ', ', ···')}` : '—'}</td>
                  {edit && (
                    <td className="r" style={{ whiteSpace: 'nowrap' }}>
                      <Button size="sm" icon={Users} title="Chauffeur" onClick={() => onModal({ type: 'driverOf', vehicle: v })} disabled={!v.active} />{' '}
                      <Button size="sm" icon={Pencil} title="Modifier" onClick={() => onModal({ type: 'vehicle', vehicle: v })} />
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function licence(d) {
  if (!d.licenceExpires) return <span className="muted">—</span>;
  const expired = new Date(d.licenceExpires) < new Date();
  return (
    <span className="row" style={{ gap: 6 }}>
      <span className="num">{new Date(d.licenceExpires).toLocaleDateString('fr-FR')}</span>
      {expired ? <Badge tone="red">Expiré</Badge> : d.licenceExpiring ? <Badge tone="orange">Expire bientôt</Badge> : null}
    </span>
  );
}

function DriversTab({ fleet, edit, onModal }) {
  return (
    <>
      {edit && <div className="row" style={{ padding: '12px 20px 0' }}><Button size="sm" icon={Plus} onClick={() => onModal({ type: 'driver' })}>Chauffeur</Button></div>}
      {!fleet.drivers.length ? <Empty>Aucun chauffeur</Empty> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Chauffeur</th><th>Téléphone</th><th>Permis</th><th>Fin de validité</th><th>Véhicule</th><th>Cartes</th>{edit && <th />}</tr></thead>
            <tbody>
              {fleet.drivers.map((d) => (
                <tr key={d.id} style={{ opacity: d.active ? 1 : 0.55 }}>
                  <td className="strong">{d.name}</td>
                  <td className="num">{d.phone || '—'}</td>
                  <td className="num">{d.licenceNumber || '—'}</td>
                  <td>{licence(d)}</td>
                  <td>{d.plate || <span className="muted">—</span>}</td>
                  <td className="muted num">{d.cards ? `···${d.cards.replaceAll(', ', ', ···')}` : '—'}</td>
                  {edit && <td className="r"><Button size="sm" icon={Pencil} title="Modifier" onClick={() => onModal({ type: 'driver', driver: d })} /></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function AccountTab({ fleetId, version }) {
  const [page, setPage] = useState(0);
  const moves = useApi(`/fleets/${fleetId}/movements${qs({ page, size: 15, v: version })}`);
  return (
    <Async state={moves}>
      {(data) => (!data.items.length ? <Empty>Aucun mouvement</Empty> : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Date</th><th>Opération</th><th>Carte</th><th className="r">Compte (F)</th><th className="r">Solde du compte</th><th className="r">Carte avant → après</th><th>Opérateur</th><th>Note</th></tr></thead>
              <tbody>
                {data.items.map((m) => (
                  <tr key={m.id}>
                    <td className="muted num">{dateTime(m.at)}</td>
                    <td className="strong">{MOVE_LABELS[m.kind] || m.kind}</td>
                    <td className="num">{m.numberFormatted || '—'}</td>
                    <td className="r num strong" style={{ color: m.amount > 0 ? 'var(--green)' : undefined }}>{m.amount > 0 ? '+' : '−'}{fcfa(Math.abs(m.amount))}</td>
                    <td className="r num">{fcfa(m.balanceAfter)}</td>
                    <td className="r num muted">{m.cardBalanceBefore != null ? `${fcfa(m.cardBalanceBefore)} → ${fcfa(m.cardBalanceAfter)}` : '—'}</td>
                    <td>{m.employeeName || '—'}</td>
                    <td className="muted" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={m.note || ''}>{m.note || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager data={data} page={page} onPage={setPage} label="mouvements" />
        </>
      ))}
    </Async>
  );
}

// ---------------------------------------------------------------- écran

export default function Flottes() {
  const { can } = useSession();
  const edit = can('FLOTTES');
  const [q, setQ] = useState('');
  const list = useApi('/fleets');
  const settings = useApi('/settings');
  const types = useApi('/vehicle-types');
  const [selected, setSelected] = useState(null);
  const [tab, setTab] = useState(TABS[0]);
  const [modal, setModal] = useState(null);
  const [version, setVersion] = useState(0);
  const detail = useApi(selected ? `/fleets/${selected}` : null);
  useLiveReload(() => { list.reload(); detail.reload(); }, ['operation'], 6000);

  useEffect(() => {
    if (!selected && list.data?.length) setSelected(list.data.find((f) => f.cards > 0)?.id || list.data[0].id);
  }, [list.data, selected]);

  const shown = useMemo(() => (list.data || []).filter((f) => !q || `${f.holderName} ${f.name}`.toLowerCase().includes(q.toLowerCase())), [list.data, q]);
  const threshold = Number(settings.data?.['validation.threshold'] || 500000);
  const done = () => {
    setModal(null);
    list.reload();
    detail.reload();
    setVersion((v) => v + 1);
  };

  return (
    <>
      <PageHeader title="Flottes" subtitle="Comptes flotte des entreprises, dotation des cartes, véhicules et chauffeurs · consommation par véhicule dans Extractions" />
      <div className="grid split" style={{ gridTemplateColumns: '320px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad" style={{ padding: 14 }}>
          <label className="input" style={{ height: 38, marginBottom: 10 }}>
            <Search size={16} strokeWidth={1.8} />
            <input placeholder="Entreprise ou flotte" value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
          <Async state={list}>
            {() => (!shown.length ? <Empty>Aucune flotte</Empty> : shown.map((f) => (
              <button key={f.id} type="button" onClick={() => { setSelected(f.id); setTab(TABS[0]); }}
                style={{ width: '100%', textAlign: 'left', border: f.id === selected ? '1.5px solid #bcd3ee' : '1px solid transparent', borderRadius: 10,
                  background: f.id === selected ? 'var(--primary-50)' : 'transparent', padding: '10px 12px', marginBottom: 4, opacity: f.active ? 1 : 0.55 }}>
                <div className="row between">
                  <span className="strong" style={{ fontSize: 14 }}>{f.name}</span>
                  <span className="num strong" style={{ fontSize: 13 }}>{fcfa(f.balance)} F</span>
                </div>
                <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                  {f.holderName} · {f.cards} carte(s) · {f.vehicles} véhicule(s)
                </div>
              </button>
            )))}
          </Async>
        </Card>

        <div className="stack">
          {!selected ? <Card><Empty>Choisissez une flotte</Empty></Card> : (
            <Async state={detail}>
              {(f) => (
                <>
                  <Card className="card-pad">
                    <div className="row between" style={{ flexWrap: 'wrap', rowGap: 10 }}>
                      <div>
                        <div className="row" style={{ gap: 8 }}>
                          <Building2 size={18} color="var(--primary)" />
                          <span className="card-title">{f.holderName} · {f.name}</span>
                          {f.code && <Badge tone="gray">{f.code}</Badge>}
                          {!f.active && <Badge tone="red">Désactivée</Badge>}
                        </div>
                        <div className="card-sub">{[f.contactName, f.phone, f.address].filter(Boolean).join(' · ') || 'Aucun contact renseigné'}</div>
                        {(f.loadUpLimit || f.rechargeFeePct > 0) && (
                          <div className="muted" style={{ fontSize: 12.5, marginTop: 3 }}>
                            {[f.loadUpLimit && `Compte plafonné à ${fcfa(f.loadUpLimit)} F`, f.rechargeFeePct > 0 && `frais de recharge ${Number(f.rechargeFeePct)} %`].filter(Boolean).join(' · ')}
                          </div>
                        )}
                      </div>
                      {edit && (
                        <span className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                          <Button size="sm" icon={Pencil} onClick={() => setModal({ type: 'fleet' })}>Modifier</Button>
                          <Button size="sm" icon={HandCoins} onClick={() => setModal({ type: 'allocate' })} disabled={!f.balance || !f.active}>Doter les cartes</Button>
                          {sistersOf(f).length > 0 && (
                            <Button size="sm" icon={ArrowLeftRight} onClick={() => setModal({ type: 'transfer' })} disabled={!f.balance || !f.active}>Transférer</Button>
                          )}
                          <Button size="sm" variant="primary" icon={Wallet} onClick={() => setModal({ type: 'recharge' })} disabled={!f.active}>Recharger le compte</Button>
                        </span>
                      )}
                    </div>
                    {f.pendingRecharges.length > 0 && (
                      <div className="alert-box" style={{ marginTop: 12, background: 'var(--orange-50)', color: 'var(--orange)' }}>
                        <Wallet size={18} style={{ flexShrink: 0 }} />
                        <span>{f.pendingRecharges.map((r) => `${r.ref} · ${money(r.amount)} en validation (${r.validationRef})`).join(' — ')}</span>
                      </div>
                    )}
                  </Card>
                  <div className="grid g4">
                    <Kpi icon={Wallet} label="Compte flotte" value={fcfa(f.balance)} unit="F" foot="à répartir sur les cartes" />
                    <Kpi icon={IdCard} tone="navy" label="Soldes des cartes" value={fcfa(f.cards.reduce((s, c) => s + c.balance, 0))} unit="F"
                      foot={`${f.cards.filter((c) => c.status === 'ACTIVE').length} carte(s) active(s)`} />
                    <Kpi icon={Fuel} tone="green" label="Carburant 30 jours" value={num(f.consumption.liters)} unit="L"
                      foot={`${num(f.consumption.fills)} pleins · ${money(f.consumption.amount)}`} />
                    <Kpi icon={CarFront} tone="orange" label="Parc" value={num(f.vehicles.filter((v) => v.active).length)} unit="véhicules"
                      foot={<><Gauge size={13} /> {num(f.drivers.filter((d) => d.active).length)} chauffeur(s)</>} />
                  </div>
                  <Card>
                    <div style={{ padding: '14px 20px 0' }}><Segmented items={TABS} value={tab} onChange={setTab} /></div>
                    {tab === 'Cartes' && <CardsTab fleet={f} edit={edit} onModal={setModal} />}
                    {tab === 'Véhicules' && <VehiclesTab fleet={f} edit={edit} onModal={setModal} />}
                    {tab === 'Chauffeurs' && <DriversTab fleet={f} edit={edit} onModal={setModal} />}
                    {tab === 'Compte flotte' && <AccountTab fleetId={f.id} version={version} />}
                  </Card>

                  {modal?.type === 'recharge' && <RechargeModal fleet={f} threshold={threshold} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'allocate' && <AllocateModal fleet={f} cards={f.cards} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'allocate1' && <AllocateModal fleet={f} cards={[modal.card]} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'reclaim' && <ReclaimModal fleet={f} card={modal.card} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'assign' && <AssignCardModal card={modal.card} vehicles={f.vehicles} drivers={f.drivers} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'vehicle' && <VehicleModal fleet={f} vehicle={modal.vehicle} types={types.data} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'driver' && <DriverModal fleet={f} driver={modal.driver} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'driverOf' && <AssignDriverModal vehicle={modal.vehicle} drivers={f.drivers} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'fleet' && <FleetModal fleet={f} onClose={() => setModal(null)} onSaved={done} />}
                  {modal?.type === 'transfer' && <FleetTransferModal fleet={f} onClose={() => setModal(null)} onSaved={done} />}
                </>
              )}
            </Async>
          )}
        </div>
      </div>
    </>
  );
}
