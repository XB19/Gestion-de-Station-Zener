import { useState } from 'react';
import { Droplets, Ruler, Truck, BellRing, Check } from 'lucide-react';
import { Button } from './ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from './feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post } from '../api/client.js';
import { num, time, dateTime } from '../lib/format.js';

const tankLabel = (t) => `${t.name || t.productName} · ${num(t.volume)} / ${num(t.capacity)} L`;

/** Mesure manuelle (pige) d'une cuve ; avec ajustement, l'écart devient un inventaire. */
function ReadingModal({ tanks, onClose, onSaved }) {
  const notify = useToast();
  const [tankId, setTankId] = useState(String(tanks[0]?.id || ''));
  const [f, setF] = useState({ volume: '', heightMm: '', temperature: '', waterHeightMm: '', adjust: true, note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const tank = tanks.find((t) => String(t.id) === tankId);
  const gap = f.volume === '' || !tank ? null : Number(f.volume) - Number(tank.volume);
  const n = (v) => (v === '' ? null : Number(v));
  return (
    <FormModal title="Mesure de cuve (pige)" submitLabel={f.adjust ? 'Enregistrer l’inventaire' : 'Enregistrer la mesure'} onClose={onClose}
      onSubmit={async () => {
        await post(`/tanks/${tankId}/readings`, {
          volume: Number(f.volume), heightMm: n(f.heightMm), temperature: n(f.temperature), waterHeightMm: n(f.waterHeightMm),
          adjust: f.adjust, note: f.note || null,
        });
        notify(f.adjust ? 'Inventaire de cuve enregistré · stock ajusté' : 'Mesure enregistrée');
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Cuve" full>
          <SelectInput value={tankId} onChange={setTankId} options={tanks.map((t) => ({ value: String(t.id), label: tankLabel(t) }))} />
        </Field>
        <Field label="Volume mesuré (L)" hint={gap == null ? 'Lecture de la règle convertie avec le barème de la cuve'
          : `Stock théorique ${num(tank.volume)} L · écart ${gap > 0 ? '+' : ''}${num(gap, 2)} L`}>
          <TextInput type="number" min="0" step="0.01" value={f.volume} onChange={set('volume')} required />
        </Field>
        <Field label="Hauteur (mm)"><TextInput type="number" min="0" step="0.1" value={f.heightMm} onChange={set('heightMm')} /></Field>
        <Field label="Température (°C)"><TextInput type="number" step="0.1" value={f.temperature} onChange={set('temperature')} /></Field>
        <Field label="Eau en fond de cuve (mm)"><TextInput type="number" min="0" step="0.1" value={f.waterHeightMm} onChange={set('waterHeightMm')} /></Field>
        <Field label="Ajuster le stock" full hint="L’écart avec le stock théorique est enregistré comme inventaire">
          <SelectInput value={f.adjust ? 'oui' : 'non'} onChange={(v) => set('adjust')(v === 'oui')}
            options={[{ value: 'oui', label: 'Oui : le volume mesuré devient le stock de la cuve' }, { value: 'non', label: 'Non : simple contrôle' }]} />
        </Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} placeholder="Pige hebdomadaire, contrôle après livraison…" /></Field>
      </div>
    </FormModal>
  );
}

/** Réception de carburant (dépotage) : bon du fournisseur et volume reçu en cuve. */
function ReceptionModal({ tanks, onClose, onSaved }) {
  const notify = useToast();
  const [tankId, setTankId] = useState(String(tanks[0]?.id || ''));
  const [f, setF] = useState({ ticketVolume: '', receivedVolume: '', supplier: '', truckPlate: '', driver: '', ticketNumber: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const gap = f.ticketVolume && f.receivedVolume ? Number(f.receivedVolume) - Number(f.ticketVolume) : null;
  return (
    <FormModal title="Réception de carburant" submitLabel="Enregistrer la réception" wide onClose={onClose}
      onSubmit={async () => {
        const d = await post(`/tanks/${tankId}/deliveries`, {
          ticketVolume: Number(f.ticketVolume), receivedVolume: f.receivedVolume ? Number(f.receivedVolume) : null,
          supplier: f.supplier, truckPlate: f.truckPlate, driver: f.driver, ticketNumber: f.ticketNumber, note: f.note,
        });
        notify(`Réception ${d.ref} enregistrée · ${num(d.receivedVolume)} L`);
        onSaved();
      }}>
      <div className="form-grid">
        <Field label="Cuve" full>
          <SelectInput value={tankId} onChange={setTankId} options={tanks.map((t) => ({ value: String(t.id), label: tankLabel(t) }))} />
        </Field>
        <Field label="Volume du bon (L)"><TextInput type="number" min="1" step="0.01" value={f.ticketVolume} onChange={set('ticketVolume')} required /></Field>
        <Field label="Volume reçu en cuve (L)" hint={gap == null ? 'Laisser vide si égal au bon' : `Écart reçu − bon : ${gap > 0 ? '+' : ''}${num(gap, 2)} L`}>
          <TextInput type="number" min="1" step="0.01" value={f.receivedVolume} onChange={set('receivedVolume')} />
        </Field>
        <Field label="N° du bon de livraison"><TextInput value={f.ticketNumber} onChange={set('ticketNumber')} /></Field>
        <Field label="Fournisseur"><TextInput value={f.supplier} onChange={set('supplier')} placeholder="Dépôt pétrolier…" /></Field>
        <Field label="Camion (immatriculation)"><TextInput value={f.truckPlate} onChange={set('truckPlate')} placeholder="TG 1234 AB" /></Field>
        <Field label="Chauffeur"><TextInput value={f.driver} onChange={set('driver')} /></Field>
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

/**
 * Outils des cuves d'une station : alarmes ouvertes (acquittement), pige, réception.
 * Les historiques complets sont dans Extractions (historique des cuves, réceptions, inventaires, alarmes).
 */
export default function TankTools({ stationId, tanks, canEdit, onChanged }) {
  const notify = useToast();
  const alarms = useApi(`/tanks/alarms?stationId=${stationId}`);
  const [modal, setModal] = useState(null);
  useLiveReload(alarms.reload, ['alert'], 3000);
  const done = () => {
    setModal(null);
    alarms.reload();
    onChanged();
  };
  const ack = async (a) => {
    try {
      await post(`/tanks/alarms/${a.id}/acknowledge`, { note: null });
      notify('Alarme acquittée');
      alarms.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <>
      {(alarms.data || []).map((a) => (
        <div key={a.id} className="alert-box" style={{ marginTop: 12, background: 'var(--orange-50)', color: 'var(--orange)', alignItems: 'center' }}>
          {a.kind === 'EAU' ? <Droplets size={18} style={{ flexShrink: 0 }} /> : <BellRing size={18} style={{ flexShrink: 0 }} />}
          <span style={{ flex: 1, minWidth: 0 }}>
            <b>{a.productName} · {a.label}</b> depuis {dateTime(a.startedAt)} · valeur {num(a.value, 1)} (seuil {num(a.threshold, 1)})
            {a.acknowledgedAt && <span className="muted"> · acquittée par {a.acknowledgedByName} à {time(a.acknowledgedAt)}</span>}
          </span>
          {canEdit && !a.acknowledgedAt && <Button size="sm" icon={Check} onClick={() => ack(a)}>Acquitter</Button>}
        </div>
      ))}
      {canEdit && tanks.length > 0 && (
        <div className="row" style={{ gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
          <Button size="sm" icon={Ruler} onClick={() => setModal('reading')}>Pige</Button>
          <Button size="sm" icon={Truck} onClick={() => setModal('reception')}>Réception</Button>
        </div>
      )}
      {modal === 'reading' && <ReadingModal tanks={tanks} onClose={() => setModal(null)} onSaved={done} />}
      {modal === 'reception' && <ReceptionModal tanks={tanks} onClose={() => setModal(null)} onSaved={done} />}
    </>
  );
}
