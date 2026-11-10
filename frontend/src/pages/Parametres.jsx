import { useEffect, useState } from 'react';
import { Save, Plus, Trash2, Mail, CalendarDays, Settings as SettingsIcon, Check, CircleAlert } from 'lucide-react';
import { PageHeader, Card, Badge, Button, Select, Async, Empty } from '../components/ui.jsx';
import { Modal, useToast } from '../components/feedback.jsx';
import { useApi } from '../api/hooks.js';
import { del, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, longDate } from '../lib/format.js';

// Paramètres modifiables, regroupés par thème (les clés « demo.* » du simulateur ne sont pas affichées)
const GROUPS = [
  {
    title: 'Validations',
    items: [
      ['validation.threshold', 'Palier de validation par carte', 'number', 'FCFA · au-dessus, la recharge part en validation'],
      ['validation.company_needs_daf', 'Cartes entreprises : validation DAF après la Comptable', 'bool'],
    ],
  },
  {
    title: 'Stations et points de vente',
    items: [
      ['station.online_seconds', 'Station « en ligne » si dernier signal de moins de', 'number', 'secondes'],
      ['station.unstable_seconds', 'Station « instable » jusqu’à', 'number', 'secondes · au-delà : hors ligne'],
      ['pdv.offline_seconds', 'Point de vente « hors ligne » après', 'number', 'secondes sans synchronisation'],
      ['card.number_prefix', 'Préfixe des numéros de carte', 'text', '4 chiffres'],
    ],
  },
  {
    title: 'Stocks et alertes',
    items: [
      ['alerts.critical_ratio', 'Alerte critique sous', 'number', 'fraction du seuil (0,6 = 60 %)', '0.05'],
      ['alerts.safety_days', 'Jours de marge du seuil automatique', 'number', 'ajoutés au délai de livraison'],
      ['alerts.logistics_emails', 'Emails de la logistique', 'text', 'séparés par des virgules'],
    ],
  },
  {
    title: 'Rapports et emails',
    items: [
      ['report.daily_time', 'Génération des rapports journaliers', 'time'],
      ['email.summary_time', 'Envoi du résumé quotidien', 'time'],
      ['email.summary_recipients', 'Destinataires du résumé', 'text', 'séparés par des virgules'],
      ['email.summary_to_managers', 'Envoyer aussi le résumé aux gérants', 'bool'],
      ['licence.reminder_days', 'Rappels avant fin de licence', 'text', 'jours, ex. 30,7,1'],
    ],
  },
  {
    title: 'Zen Mobility',
    items: [
      ['mobility.min_quantity', 'Commande minimale', 'number', 'litres'],
      ['mobility.tracking_stale_seconds', 'Position GPS ancienne après', 'number', 'secondes'],
    ],
  },
];

function Holidays({ editable }) {
  const notify = useToast();
  const list = useApi('/settings/holidays');
  const [day, setDay] = useState('');
  const [label, setLabel] = useState('');
  const year = new Date().getFullYear();
  const add = async (e) => {
    e.preventDefault();
    try {
      list.setData(await post('/settings/holidays', { day, label }));
      notify('Jour férié enregistré');
      setDay('');
      setLabel('');
    } catch (err) {
      notify(err.message, 'error');
    }
  };
  const remove = async (d) => {
    try {
      list.setData(await del(`/settings/holidays/${d}`));
      notify('Jour férié supprimé');
    } catch (err) {
      notify(err.message, 'error');
    }
  };
  return (
    <Card className="card-pad">
      <div className="row" style={{ gap: 8 }}><CalendarDays size={17} /><div className="card-title">Jours fériés</div></div>
      <div className="card-sub">Les stations appliquent les plages « fériés » ces jours-là. Les fêtes musulmanes sont à saisir chaque année.</div>
      {editable && (
        <form className="row" style={{ gap: 8, marginTop: 14 }} onSubmit={add}>
          <label className="input" style={{ width: 160 }}><input type="date" value={day} onChange={(e) => setDay(e.target.value)} required /></label>
          <label className="input" style={{ flex: 1 }}><input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Tabaski, Ramadan…" required /></label>
          <Button type="submit" icon={Plus}>Ajouter</Button>
        </form>
      )}
      <Async state={list}>
        {(rows) => (rows.length === 0 ? <Empty>Aucun jour férié</Empty> : (
          <div style={{ marginTop: 10 }}>
            {rows.map((h) => (
              <div key={h.day} className="kv" style={{ opacity: String(h.day).startsWith(String(year)) || h.day > `${year}` ? 1 : 0.55 }}>
                <span className="k">{longDate(h.day)}</span>
                <span className="v row" style={{ gap: 10 }}>
                  {h.label}
                  {editable && <button className="link-more" title="Supprimer" onClick={() => remove(h.day)}><Trash2 size={15} /></button>}
                </span>
              </div>
            ))}
          </div>
        ))}
      </Async>
    </Card>
  );
}

const KINDS = [
  { value: 'ALERTE_STOCK', label: 'Alertes de stock' }, { value: 'VALIDATION', label: 'Validations' },
  { value: 'RESUME_QUOTIDIEN', label: 'Résumé quotidien' }, { value: 'LICENCE', label: 'Licences' }, { value: 'LIVRAISON', label: 'Livraisons' },
];

function Outbox() {
  const [kind, setKind] = useState('');
  const [open, setOpen] = useState(null);
  const emails = useApi(`/settings/emails${qs({ kind, limit: 50 })}`);
  return (
    <Card>
      <div className="card-head" style={{ paddingBottom: 12 }}>
        <div>
          <div className="row" style={{ gap: 8 }}><Mail size={17} /><div className="card-title">File des emails</div></div>
          <div className="card-sub">Sans serveur SMTP configuré (MAIL_HOST), les emails restent ici, consultables</div>
        </div>
        <Select value={kind} onChange={setKind} placeholder="Tous" options={KINDS} />
      </div>
      <Async state={emails}>
        {(rows) => (rows.length === 0 ? <Empty>Aucun email</Empty> : (
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Quand</th><th>Objet</th><th>Destinataires</th><th>État</th></tr></thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id} className="tr-click" onClick={() => setOpen(m)}>
                  <td className="num muted" style={{ whiteSpace: 'nowrap' }}>{dateTime(m.createdAt)}</td>
                  <td className="strong">{m.subject}</td>
                  <td className="muted">{(m.recipients || []).slice(0, 2).join(', ')}{m.recipients?.length > 2 ? ` +${m.recipients.length - 2}` : ''}</td>
                  <td>{m.sentAt ? <Badge tone="green">Envoyé</Badge> : m.error ? <Badge tone="red">Erreur</Badge> : <Badge tone="gray">En file</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        ))}
      </Async>
      {open && (
        <Modal title={open.subject} onClose={() => setOpen(null)} wide>
          <div className="muted" style={{ fontSize: 13 }}>À : {open.recipients.join(', ')} · {dateTime(open.createdAt)}</div>
          {open.error && <div className="form-error">{open.error}</div>}
          <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', fontSize: 14, lineHeight: 1.6, marginTop: 12 }}>{open.body}</pre>
        </Modal>
      )}
    </Card>
  );
}

export default function Parametres() {
  const notify = useToast();
  const { can } = useSession();
  const editable = can('PARAMETRES');
  const settings = useApi('/settings');
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (settings.data) setDraft({ ...settings.data });
  }, [settings.data]);
  const changed = draft && settings.data ? Object.keys(draft).filter((k) => draft[k] !== settings.data[k]) : [];

  const save = async () => {
    setBusy(true);
    try {
      const values = Object.fromEntries(changed.map((k) => [k, String(draft[k])]));
      settings.setData(await put('/settings', values));
      notify(`${changed.length} paramètre(s) enregistré(s)`);
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Paramètres"
        subtitle="Paliers, délais, alertes, rapports et emails · chaque modification est tracée dans le journal"
        actions={editable && (
          <>
            {changed.length > 0 && <Button onClick={() => setDraft({ ...settings.data })}>Annuler</Button>}
            <Button variant="primary" icon={Save} disabled={busy || changed.length === 0} onClick={save}>
              Enregistrer{changed.length ? ` (${changed.length})` : ''}
            </Button>
          </>
        )}
      />
      {!editable && (
        <div className="alert-box mb20" style={{ background: 'var(--surface-2)' }}>
          <CircleAlert size={18} /> <span>Consultation seule : la modification demande le droit « Paramètres ».</span>
        </div>
      )}

      <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', alignItems: 'start' }}>
        <div className="stack">
          {draft && GROUPS.map((g) => (
            <Card key={g.title} className="card-pad">
              <div className="row" style={{ gap: 8 }}><SettingsIcon size={17} /><div className="card-title">{g.title}</div></div>
              <div style={{ marginTop: 8 }}>
                {g.items.filter(([key]) => key in draft).map(([key, label, type, hint, step]) => {
                  const dirty = draft[key] !== settings.data[key];
                  return (
                    <div key={key} className="row" style={{ gap: 16, padding: '12px 0', borderBottom: '1px solid var(--border-2)' }}>
                      <div style={{ flex: 1 }}>
                        <div className="strong">{label}{dirty && <Badge tone="blue" style={{ marginLeft: 8, height: 20 }}>modifié</Badge>}</div>
                        {hint && <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>{hint}</div>}
                      </div>
                      {type === 'bool' ? (
                        <button type="button" disabled={!editable} className={`toggle ${draft[key] === 'true' ? 'on' : ''}`}
                          onClick={() => setDraft({ ...draft, [key]: draft[key] === 'true' ? 'false' : 'true' })} />
                      ) : (
                        <label className="input" style={{ width: type === 'text' ? 260 : 150 }}>
                          <input
                            className="num"
                            type={type === 'time' ? 'time' : type === 'number' ? 'number' : 'text'}
                            step={step || (type === 'number' ? '1' : undefined)}
                            value={draft[key] ?? ''}
                            disabled={!editable}
                            onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
                          />
                        </label>
                      )}
                    </div>
                  );
                })}
              </div>
            </Card>
          ))}
          {!draft && <Card className="card-pad"><Async state={settings}>{() => null}</Async></Card>}
        </div>
        <div className="stack">
          <Holidays editable={editable} />
          {can('PARAMETRES', 'JOURNAL') && <Outbox />}
          {changed.length > 0 && (
            <div className="row muted" style={{ gap: 6, fontSize: 13 }}><Check size={14} /> Pensez à enregistrer vos modifications.</div>
          )}
        </div>
      </div>
    </>
  );
}
