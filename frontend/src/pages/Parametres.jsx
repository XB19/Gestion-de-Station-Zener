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
      ['validation.threshold', 'Palier de validation par carte', 'number', 'FCFA · au-dessus, la recharge est validée par le DAF (ou son délégué)'],
      ['validation.approver_roles', 'Rôles qui valident les montants', 'text', 'codes de rôle séparés par des virgules (DAF par défaut)'],
    ],
  },
  {
    title: 'Carte Club',
    items: [
      ['club.price', 'Prix de la carte Club', 'number', 'FCFA'],
      ['club.initial_deposit', 'Dépôt initial minimal', 'number', 'FCFA crédités sur la carte à l’adhésion'],
      ['club.validity_months', 'Durée de l’adhésion', 'number', 'mois'],
      ['club.auto_renew_spend', 'Consommation donnant un renouvellement d’office', 'number', 'FCFA sur les 12 derniers mois'],
      ['club.renewal_fee', 'Frais de renouvellement', 'number', 'FCFA'],
      ['club.grace_days', 'Période de grâce', 'number', 'jours après l’échéance'],
      ['club.reminder_days', 'Rappels avant l’échéance', 'text', 'jours, ex. 30,7,1'],
      ['club.daily_limit', 'Plafond journalier par défaut', 'number', 'FCFA'],
      ['club.monthly_limit', 'Plafond mensuel par défaut', 'number', 'FCFA'],
      ['card.replacement_fee', 'Frais de remplacement d’une carte', 'number', 'FCFA'],
    ],
  },
  {
    title: 'Contrôles des soldes et recharges',
    items: [
      ['controls.split_count', 'Recharges fractionnées : nombre en 24 h', 'number', 'juste sous le palier'],
      ['controls.split_ratio', 'Recharges fractionnées : « juste sous le palier » dès', 'number', 'fraction du palier (0,8 = 80 %)', '0.05'],
      ['controls.atypical_factor', 'Recharge atypique : fois la moyenne', 'number', 'des 90 jours précédents'],
      ['controls.atypical_min', 'Recharge atypique : à partir de', 'number', 'FCFA'],
      ['controls.rapid_count', 'Recharges rapprochées : nombre en 24 h', 'number'],
      ['controls.night_from', 'Recharge de nuit : à partir de', 'time'],
      ['controls.night_to', 'Recharge de nuit : jusqu’à', 'time'],
      ['controls.same_operator_count', 'Même opérateur, même carte : recharges en 7 jours', 'number'],
      ['controls.dormant_balance', 'Solde dormant : à partir de', 'number', 'FCFA'],
      ['controls.dormant_days', 'Solde dormant : jours sans achat', 'number'],
      ['controls.accumulation_ratio', 'Accumulation : recharges / dépenses', 'number', 'sur 90 jours'],
      ['controls.accumulation_min', 'Accumulation : recharges d’au moins', 'number', 'FCFA sur 90 jours'],
    ],
  },
  {
    title: 'Sécurité',
    items: [
      ['security.sensitive_settings', 'Paramètres soumis à une seconde personne', 'text', 'clés séparées par des virgules'],
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
      ['alerts.tank_levels', 'Paliers d’alerte des cuves', 'text', '% de la capacité, priorité croissante (ex. 50,40,30,5)'],
      ['alerts.critical_repeat_hours', 'Rappel sous le dernier palier toutes les', 'number', 'heures, jusqu’à la livraison'],
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
      const sensitiveKeys = String(settings.data['security.sensitive_settings'] || '').split(',').map((x) => x.trim());
      const sensitive = changed.filter((k) => sensitiveKeys.includes(k));
      if (sensitive.length) {
        const reason = window.prompt(`Paramètres financiers (${sensitive.join(', ')}) : une seconde personne doit approuver. Motif :`) || '';
        if (!reason.trim()) throw new Error('Motif requis pour un paramètre financier');
        values._reason = reason;
      }
      const r = await put('/settings', values);
      const { _pending: pending, ...rest } = r;
      settings.setData(rest);
      if (pending) notify(pending.message, 'info');
      if (changed.length > sensitive.length) notify(`${changed.length - sensitive.length} paramètre(s) enregistré(s)`);
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
