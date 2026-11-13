import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldX, ShieldAlert, ShieldCheck, Plus, X } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import CardPicker from '../components/CardPicker.jsx';
import { dateTime, longDate } from '../lib/format.js';

const KINDS = {
  NOIRE: { label: 'Liste noire', tone: 'red', icon: ShieldX, help: 'Refusée partout, y compris par les PC des stations hors connexion (perte, vol, fraude).' },
  GRISE: { label: 'Liste grise', tone: 'orange', icon: ShieldAlert, help: 'Sous surveillance : PIN exigé à chaque paiement. Ajout automatique après plusieurs PIN faux ou une transaction hors ligne refusée.' },
  BLANCHE: { label: 'Liste blanche', tone: 'green', icon: ShieldCheck, help: 'Acceptée par le PC de la station même hors connexion (clients de confiance), pour une station ou toutes.' },
};
const TABS = ['Liste noire', 'Liste grise', 'Liste blanche', 'Historique'];
const TAB_KIND = { 'Liste noire': 'NOIRE', 'Liste grise': 'GRISE', 'Liste blanche': 'BLANCHE' };

function AddModal({ kind, onClose, onSaved }) {
  const notify = useToast();
  const stations = useApi('/stations');
  const [card, setCard] = useState(null);
  const [f, setF] = useState({ kind: kind || 'NOIRE', stationId: '', reason: '', validUntil: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Ajouter une carte à une liste" onClose={onClose} onSubmit={async () => {
      if (!card) throw new Error('Choisissez une carte');
      await post('/card-lists', {
        cardId: card.id, kind: f.kind, stationId: f.kind === 'BLANCHE' && f.stationId ? Number(f.stationId) : null, reason: f.reason,
        validUntil: f.validUntil ? new Date(`${f.validUntil}T23:59:59`).toISOString() : null,
      });
      notify(`Carte ajoutée à la ${KINDS[f.kind].label.toLowerCase()}`);
      onSaved();
    }}>
      <Field label="Carte"><CardPicker value={card} onChange={setCard} autoFocus /></Field>
      <div className="form-grid" style={{ marginTop: 12 }}>
        <Field label="Liste">
          <SelectInput value={f.kind} onChange={set('kind')} options={Object.entries(KINDS).map(([value, k]) => ({ value, label: k.label }))} />
        </Field>
        <Field label="Jusqu'au" hint="Vide : sans limite"><TextInput type="date" value={f.validUntil} onChange={set('validUntil')} /></Field>
        {f.kind === 'BLANCHE' && (
          <Field label="Station" full>
            <SelectInput value={f.stationId} onChange={set('stationId')} placeholder="Toutes les stations"
              options={(stations.data || []).map((s) => ({ value: s.id, label: s.name }))} />
          </Field>
        )}
        <Field label="Motif" full><TextInput value={f.reason} onChange={set('reason')} required /></Field>
      </div>
      <div className="muted" style={{ marginTop: 10, fontSize: 13 }}>{KINDS[f.kind].help}</div>
    </FormModal>
  );
}

function RemoveModal({ entry, onClose, onSaved }) {
  const notify = useToast();
  const [note, setNote] = useState('');
  return (
    <FormModal title={`Retirer ${entry.numberFormatted} de la ${KINDS[entry.kind].label.toLowerCase()}`} submitLabel="Retirer" onClose={onClose}
      onSubmit={async () => {
        await post(`/card-lists/${entry.id}/remove`, { note: note || null });
        notify('Carte retirée de la liste');
        onSaved();
      }}>
      <Field label="Motif du retrait"><TextInput value={note} onChange={setNote} placeholder="Carte retrouvée, contrôle terminé…" autoFocus /></Field>
      {entry.kind === 'NOIRE' && entry.status === 'BLOQUEE' && (
        <div className="muted" style={{ marginTop: 10, fontSize: 13 }}>La carte reste bloquée : débloquez-la depuis sa fiche si elle doit resservir.</div>
      )}
    </FormModal>
  );
}

export default function ListesCartes() {
  const { can } = useSession();
  const edit = can('CARTES_GERER');
  const [tab, setTab] = useState(TABS[0]);
  const [modal, setModal] = useState(null);
  const kind = TAB_KIND[tab];
  const all = useApi('/card-lists');
  const rows = useApi(`/card-lists${qs({ kind, all: kind ? undefined : true })}`);
  const reload = () => {
    all.reload();
    rows.reload();
  };
  useLiveReload(reload, ['operation'], 15000);
  const count = (k) => (all.data || []).filter((r) => r.kind === k).length;

  return (
    <>
      <PageHeader title="Listes de cartes" subtitle="Envoyées aux PC des stations pour le fonctionnement hors connexion · une carte remplacée pour perte ou vol passe en liste noire"
        actions={edit && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'add' })}>Ajouter une carte</Button>} />
      <div className="grid g3">
        {Object.entries(KINDS).map(([k, v]) => (
          <Kpi key={k} icon={v.icon} tone={v.tone === 'orange' ? 'orange' : v.tone === 'green' ? 'green' : 'red'} label={v.label} value={count(k)} unit="carte(s)"
            foot={<span style={{ whiteSpace: 'normal' }}>{v.help}</span>} />
        ))}
      </div>
      <Card style={{ marginTop: 16 }}>
        <div className="card-head" style={{ alignItems: 'center' }}>
          <Segmented items={TABS.map((t) => ({ label: t, count: TAB_KIND[t] ? count(TAB_KIND[t]) : undefined }))} value={tab} onChange={setTab} />
        </div>
        <Async state={rows}>
          {(list) => (!list.length ? <Empty>Aucune carte</Empty> : (
            <div className="table-wrap"><table className="table">
              <thead>
                <tr>
                  <th>Carte</th><th>Titulaire</th>{!kind && <th>Liste</th>}<th>Station</th><th>Motif</th><th>Ajoutée</th><th>Par</th><th>Jusqu'au</th>
                  {!kind && <th>Retirée</th>}{kind && edit && <th />}
                </tr>
              </thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id} style={{ opacity: r.removedAt ? 0.6 : 1 }}>
                    <td><Link to={`/cartes/${r.cardId}`} className="num strong">{r.numberFormatted}</Link></td>
                    <td>{r.holderName || '—'}</td>
                    {!kind && <td><Badge tone={KINDS[r.kind].tone}>{KINDS[r.kind].label}</Badge></td>}
                    <td>{r.stationName || (r.kind === 'BLANCHE' ? 'Toutes' : '—')}</td>
                    <td>{r.reason}</td>
                    <td className="muted num">{dateTime(r.addedAt)}</td>
                    <td>{r.addedByName}</td>
                    <td className="muted num">{r.validUntil ? longDate(r.validUntil) : '—'}</td>
                    {!kind && <td className="muted">{r.removedAt ? `${dateTime(r.removedAt)} · ${r.removedByName || ''}${r.removalNote ? ` · ${r.removalNote}` : ''}` : '—'}</td>}
                    {kind && edit && <td className="r"><Button size="sm" icon={X} title="Retirer" onClick={() => setModal({ type: 'remove', entry: r })} /></td>}
                  </tr>
                ))}
              </tbody>
            </table></div>
          ))}
        </Async>
      </Card>
      {modal?.type === 'add' && <AddModal kind={kind} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'remove' && <RemoveModal entry={modal.entry} onClose={() => setModal(null)} onSaved={reload} />}
    </>
  );
}
