import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { SearchCheck, ShieldAlert, Eye, CheckCircle2, RefreshCw } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { Modal, FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, post, qs } from '../api/client.js';
import { dateTime, fcfa, money, num } from '../lib/format.js';

const FILTERS = ['À traiter', 'En revue', 'Closes', 'Toutes'];
const FILTER_STATUS = { 'À traiter': 'A_TRAITER', 'En revue': 'EN_REVUE', Closes: 'CLOSE', Toutes: 'TOUTES' };
const STATUS_TONE = { OUVERTE: 'orange', EN_REVUE: 'blue', CLOSE: 'gray' };
const CHANNEL = { UNITAIRE: 'Guichet', DISTANCE: 'À distance', MASSE: 'En masse' };

function CloseModal({ alert, onClose, onSaved }) {
  const notify = useToast();
  const [justified, setJustified] = useState('oui');
  const [resolution, setResolution] = useState('');
  return (
    <FormModal title={`Clore l'alerte · ${alert.title}`} submitLabel="Clore" onClose={onClose} onSubmit={async () => {
      await post(`/controls/${alert.id}/close`, { justified: justified === 'oui', resolution });
      notify('Alerte close');
      onSaved();
    }}>
      <Field label="Conclusion">
        <SelectInput value={justified} onChange={setJustified} options={[
          { value: 'oui', label: 'Activité justifiée (justificatifs vus, client connu…)' },
          { value: 'non', label: 'Anomalie confirmée (à traiter : blocage, signalement…)' },
        ]} />
      </Field>
      <div style={{ marginTop: 12 }}>
        <Field label="Ce qui a été vérifié"><TextInput value={resolution} onChange={setResolution} required placeholder="Ex. virement du client reçu, bon de commande vu" /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>Aucun blocage automatique : si la carte doit être bloquée, faites-le depuis sa fiche.</div>
    </FormModal>
  );
}

function Detail({ id, onClose, onChanged }) {
  const notify = useToast();
  const d = useApi(`/controls/${id}`);
  const [closing, setClosing] = useState(false);
  const review = async () => {
    try {
      await post(`/controls/${id}/review`);
      d.reload();
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Modal title="Alerte de contrôle" wide onClose={onClose} footer={d.data && d.data.status !== 'CLOSE' && (
      <>
        {d.data.status === 'OUVERTE' && <Button icon={Eye} onClick={review}>Prendre en revue</Button>}
        <Button variant="primary" icon={CheckCircle2} onClick={() => setClosing(true)}>Clore</Button>
      </>
    )}>
      <Async state={d}>
        {(a) => (
          <>
            <div className="row between" style={{ flexWrap: 'wrap', rowGap: 8 }}>
              <div>
                <div className="strong" style={{ fontSize: 16 }}>{a.title}</div>
                <div className="muted" style={{ marginTop: 4 }}>{a.detail}</div>
              </div>
              <span className="row" style={{ gap: 6 }}>
                <Badge tone={a.severity === 'ELEVEE' ? 'red' : 'orange'}>{a.severity === 'ELEVEE' ? 'Élevée' : 'Moyenne'}</Badge>
                <Badge tone={STATUS_TONE[a.status]}>{a.statusLabel}</Badge>
              </span>
            </div>
            <div className="grid g3" style={{ gap: 10, marginTop: 14 }}>
              {[['Carte', <Link key="c" to={`/cartes/${a.cardId}`} className="num strong">{a.numberFormatted}</Link>], ['Titulaire', a.holderName || '—'],
                ['Solde actuel', money(a.balance)]].map(([k, v]) => (
                <div key={k} style={{ background: 'var(--surface-2)', borderRadius: 9, padding: '10px 12px' }}>
                  <div className="muted" style={{ fontSize: 12.5 }}>{k}</div>
                  <div className="strong" style={{ marginTop: 4 }}>{v}</div>
                </div>
              ))}
            </div>
            {a.status === 'CLOSE' && (
              <div className="alert-box" style={{ marginTop: 12, background: a.justified ? 'var(--green-50, #ecf7e8)' : 'var(--red-50, #fdecec)' }}>
                {a.justified ? 'Justifiée' : 'Anomalie confirmée'} · {a.resolution} · {a.closedByName}, {dateTime(a.closedAt)}
              </div>
            )}
            <div className="section-label" style={{ marginTop: 16 }}>Dernières recharges</div>
            <div className="table-wrap"><table className="table">
              <thead><tr><th>Date</th><th>Réf.</th><th>Canal</th><th>Station</th><th>Opérateur</th><th>État</th><th className="r">Montant</th></tr></thead>
              <tbody>{a.recharges.map((r) => (
                <tr key={r.ref}><td className="muted num">{dateTime(r.createdAt)}</td><td className="num">{r.ref}</td><td>{CHANNEL[r.channel] || r.channel}</td>
                  <td>{r.stationName || '—'}</td><td>{r.operator || '—'}</td><td>{r.status === 'CREDITEE' ? 'Créditée' : r.status === 'REFUSEE' ? 'Refusée' : 'En validation'}</td>
                  <td className="r num strong">{fcfa(r.amount)}</td></tr>
              ))}</tbody>
            </table></div>
            <div className="section-label" style={{ marginTop: 16 }}>Derniers achats</div>
            {a.sales.length === 0 ? <div className="muted">Aucun achat</div> : (
              <div className="table-wrap"><table className="table">
                <tbody>{a.sales.map((x, i) => (<tr key={i}><td className="muted num">{dateTime(x.at)}</td><td>{x.stationName || 'Point de vente'}</td><td className="r num">{fcfa(x.total)}</td></tr>))}</tbody>
              </table></div>
            )}
            {a.history.length > 0 && (
              <>
                <div className="section-label" style={{ marginTop: 16 }}>Alertes précédentes sur cette carte</div>
                {a.history.map((h, i) => (
                  <div key={i} className="muted" style={{ fontSize: 13 }}>{dateTime(h.createdAt)} · {h.rule} · {h.status === 'CLOSE' ? (h.justified ? 'justifiée' : 'anomalie') : h.status}</div>
                ))}
              </>
            )}
            {closing && <CloseModal alert={a} onClose={() => setClosing(false)} onSaved={() => { d.reload(); onChanged(); }} />}
          </>
        )}
      </Async>
    </Modal>
  );
}

export default function Controles() {
  const notify = useToast();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState(FILTERS[0]);
  const [rule, setRule] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const summary = useApi('/controls/summary');
  const list = useApi(`/controls${qs({ status: FILTER_STATUS[filter], rule, page })}`);
  useLiveReload(() => { summary.reload(); list.reload(); }, ['notification'], 3000);
  useEffect(() => {
    if (params.get('id')) setSelected(Number(params.get('id')));
  }, [params]);
  const s = summary.data || {};
  const scan = async () => {
    try {
      const r = await post('/controls/scan');
      notify(r.created ? `${r.created} nouvelle(s) alerte(s)` : 'Aucune nouvelle alerte', r.created ? 'info' : 'success');
      summary.reload();
      list.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <>
      <PageHeader title="Contrôles des soldes et recharges"
        subtitle="Recharges fractionnées sous le palier, recharges atypiques ou de nuit, soldes qui dorment ou s'accumulent… Contrôlé toutes les 15 minutes, sans blocage automatique."
        actions={<Button icon={RefreshCw} onClick={scan}>Contrôler maintenant</Button>} />
      <div className="grid g4">
        <Kpi icon={ShieldAlert} tone="orange" label="Ouvertes" value={num(s.open)} foot="à examiner" />
        <Kpi icon={Eye} label="En revue" value={num(s.inReview)} foot="prises en charge" />
        <Kpi icon={ShieldAlert} tone="red" label="Gravité élevée" value={num(s.high)} foot="fractionnement, aller-retour" />
        <Kpi icon={SearchCheck} tone="green" label="Closes ce mois" value={num(s.closedMonth)} foot={`${num(s.confirmedMonth)} anomalie(s) confirmée(s)`} />
      </div>
      <Card style={{ marginTop: 16 }}>
        <div className="card-head" style={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 8 }}>
          <Segmented items={FILTERS} value={filter} onChange={(v) => { setFilter(v); setPage(0); }} />
          <label className="select" style={{ height: 36 }}>
            <select value={rule} onChange={(e) => { setRule(e.target.value); setPage(0); }}>
              <option value="">Toutes les règles</option>
              {(s.rules || []).map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
            </select>
          </label>
        </div>
        <Async state={list}>
          {(data) => (!data.items.length ? <Empty>Aucune alerte</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Date</th><th>Règle</th><th>Carte</th><th>Titulaire</th><th>Détail</th><th>Gravité</th><th>État</th></tr></thead>
                <tbody>
                  {data.items.map((a) => (
                    <tr key={a.id} onClick={() => setSelected(a.id)} style={{ cursor: 'pointer' }}>
                      <td className="muted num">{dateTime(a.createdAt)}</td>
                      <td className="strong">{a.title}</td>
                      <td className="num">{a.numberFormatted}</td>
                      <td>{a.holderName || '—'}</td>
                      <td className="muted" style={{ maxWidth: 380 }}>{a.detail}</td>
                      <td><Badge tone={a.severity === 'ELEVEE' ? 'red' : 'orange'}>{a.severity === 'ELEVEE' ? 'Élevée' : 'Moyenne'}</Badge></td>
                      <td><Badge tone={STATUS_TONE[a.status]}>{a.statusLabel}</Badge>{a.assignedName && a.status === 'EN_REVUE' && <div className="muted" style={{ fontSize: 12 }}>{a.assignedName}</div>}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="alertes" />
            </>
          ))}
        </Async>
      </Card>
      {selected && <Detail id={selected} onClose={() => { setSelected(null); setParams({}); }} onChanged={() => { summary.reload(); list.reload(); }} />}
    </>
  );
}
