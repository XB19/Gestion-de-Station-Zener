import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Settings, Clock, CircleCheck, CircleX, Timer, X, Check, Download, UserCheck, ShieldAlert, Plus } from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar, Async, Empty, Pager, Select } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload, useInterval } from '../api/hooks.js';
import { fileUrl, post, put } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, avatarColor, dateTime, dayMonth, exportCsv, fcfa, money, num } from '../lib/format.js';

const ROLE_LABELS = { COMPTABLE: 'Comptable', DAF: 'DAF', ADMIN: 'Admin' };
const KIND_LABELS = { RECHARGE: 'Recharge', RECHARGE_MASSE: 'Recharge en masse', OUVERTURE_PDV: 'Ouverture de point de vente',
  DROITS_ROLE: 'Droits d’un rôle', PARAMETRES: 'Paramètres financiers', AJUSTEMENT_SOLDE: 'Ajustement de solde', COMPTE_SENSIBLE: 'Compte Admin / DAF' };
const DELEGABLE = [['RECHARGE', 'Recharges'], ['RECHARGE_MASSE', 'Recharges en masse'], ['OUVERTURE_PDV', 'Ouverture de points de vente']];
const DELEGATION_STATE = { ACTIVE: ['Active', 'green'], A_VENIR: ['À venir', 'blue'], EXPIREE: ['Expirée', 'gray'], REVOQUEE: ['Révoquée', 'red'] };

/** Contenu d'une action sensible : avant / après, lisible. */
function Payload({ json }) {
  let p = null;
  try {
    p = JSON.parse(json);
  } catch {
    return null;
  }
  const rows = [];
  if (p.values) Object.entries(p.values).forEach(([k, v]) => rows.push([k, p.before?.[k], v]));
  if (p.rights) rows.push(['Droits du rôle ' + p.role, (p.before || []).join(', '), p.rights.join(', ')]);
  if (p.amount !== undefined && p.cardId) rows.push(['Ajustement', '', `${p.amount > 0 ? '+' : ''}${fcfa(p.amount)} F`]);
  if (p.employeeId && p.role) rows.push(['Rôle', p.previousRole || (p.activate ? 'nouveau compte' : ''), p.role]);
  return (
    <div style={{ marginTop: 16, border: '1px solid var(--border)', borderRadius: 10, padding: '12px 14px' }}>
      <div className="row" style={{ gap: 8 }}><ShieldAlert size={16} color="var(--orange)" /><span className="strong">Action sensible : seconde personne requise</span></div>
      <table className="table" style={{ marginTop: 8 }}>
        <thead><tr><th>Élément</th><th>Avant</th><th>Après</th></tr></thead>
        <tbody>{rows.map(([k, a, b]) => (
          <tr key={k}><td className="strong">{k}</td><td className="muted">{a ?? '—'}</td><td className="strong">{String(b)}</td></tr>
        ))}</tbody>
      </table>
      {p.reason && <div className="muted" style={{ fontSize: 13, marginTop: 6 }}>Motif : {p.reason}</div>}
    </div>
  );
}

function DelegationModal({ onClose, onSaved }) {
  const notify = useToast();
  const people = useApi('/employees/operators');
  const [f, setF] = useState({ target: 'USER', toEmployeeId: '', toRole: 'COMPTABLE', kinds: ['RECHARGE', 'RECHARGE_MASSE'], maxAmount: '', endsAt: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Déléguer mon pouvoir de validation" submitLabel="Déléguer" onClose={onClose} onSubmit={async () => {
      await post('/validations/delegations', {
        toEmployeeId: f.target === 'USER' ? Number(f.toEmployeeId) : null, toRole: f.target === 'ROLE' ? f.toRole : null,
        kinds: f.kinds, maxAmount: f.maxAmount ? Number(f.maxAmount) : null,
        endsAt: f.endsAt ? new Date(`${f.endsAt}T23:59:59`).toISOString() : null, note: f.note || null,
      });
      notify('Délégation enregistrée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Délégué à">
          <SelectInput value={f.target} onChange={set('target')} options={[{ value: 'USER', label: 'Une personne' }, { value: 'ROLE', label: 'Tous les membres d’un rôle' }]} />
        </Field>
        {f.target === 'USER' ? (
          <Field label="Personne">
            <SelectInput value={f.toEmployeeId} onChange={set('toEmployeeId')} placeholder="Choisir…" required
              options={(people.data || []).filter((e) => e.active && !['POMPISTE', 'LIVREUR', 'AGENT_PDV'].includes(e.roleCode))
                .map((e) => ({ value: String(e.id), label: `${e.name} · ${e.roleLabel}` }))} />
          </Field>
        ) : (
          <Field label="Rôle">
            <SelectInput value={f.toRole} onChange={set('toRole')} options={[{ value: 'COMPTABLE', label: 'Comptable' }, { value: 'ADMIN', label: 'Admin' },
              { value: 'GERANT', label: 'Gérant' }]} />
          </Field>
        )}
        <Field label="Montant maximal (F)" hint="Vide : sans limite"><TextInput type="number" min="1" value={f.maxAmount} onChange={set('maxAmount')} /></Field>
        <Field label="Jusqu'au" hint="Vide : jusqu'à révocation"><TextInput type="date" value={f.endsAt} onChange={set('endsAt')} /></Field>
        <Field label="Demandes concernées" full>
          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            {DELEGABLE.map(([k, l]) => {
              const on = f.kinds.includes(k);
              return (
                <button key={k} type="button" className={`badge ${on ? 'blue' : 'gray'}`} style={{ border: 0, height: 28, cursor: 'pointer' }}
                  onClick={() => set('kinds')(on ? f.kinds.filter((x) => x !== k) : [...f.kinds, k])}>{l}</button>
              );
            })}
          </div>
        </Field>
        <Field label="Motif" full><TextInput value={f.note} onChange={set('note')} placeholder="Congés, mission…" /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>Les décisions prises par délégation portent le nom du délégué et le vôtre. Vous pouvez révoquer à tout moment.</div>
    </FormModal>
  );
}

function Delegations({ canCreate }) {
  const notify = useToast();
  const list = useApi('/validations/delegations');
  const [creating, setCreating] = useState(false);
  const revoke = async (d) => {
    try {
      await post(`/validations/delegations/${d.id}/revoke`);
      notify('Délégation révoquée');
      list.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Card className="mb20">
      <div className="card-head" style={{ alignItems: 'center' }}>
        <div className="row" style={{ gap: 8 }}><UserCheck size={18} color="var(--primary)" /><span className="card-title">Délégations du pouvoir de valider</span></div>
        {canCreate && <Button size="sm" icon={Plus} onClick={() => setCreating(true)}>Déléguer</Button>}
      </div>
      <Async state={list}>
        {(rows) => (!rows.length ? <Empty>Aucune délégation</Empty> : (
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Titulaire</th><th>Délégué</th><th>Demandes</th><th className="r">Maximum</th><th>Période</th><th className="r">Utilisée</th><th>État</th><th /></tr></thead>
            <tbody>{rows.map((d) => (
              <tr key={d.id}>
                <td className="strong">{d.fromName} <span className="muted">· {d.fromRoleLabel}</span></td>
                <td>{d.toName || `Rôle ${d.toRoleLabel}`}</td>
                <td>{(d.kinds || []).map((k) => KIND_LABELS[k]).join(', ') || 'Toutes'}</td>
                <td className="r num">{d.maxAmount ? fcfa(d.maxAmount) : '—'}</td>
                <td className="muted num">{dateTime(d.startsAt)} → {d.endsAt ? dateTime(d.endsAt) : '…'}</td>
                <td className="r num">{d.uses}</td>
                <td><Badge tone={DELEGATION_STATE[d.state]?.[1]}>{DELEGATION_STATE[d.state]?.[0]}</Badge></td>
                <td className="r">{['ACTIVE', 'A_VENIR'].includes(d.state) && canCreate && <Button size="sm" icon={X} title="Révoquer" onClick={() => revoke(d)} />}</td>
              </tr>
            ))}</tbody>
          </table></div>
        ))}
      </Async>
      {creating && <DelegationModal onClose={() => setCreating(false)} onSaved={list.reload} />}
    </Card>
  );
}

function Detail({ label, value }) {
  return (
    <div className="kv" style={{ padding: '12px 0' }}>
      <span className="k">{label}</span>
      <span className="v">{value}</span>
    </div>
  );
}

function ThresholdModal({ settings, onClose, onSaved }) {
  const notify = useToast();
  const [threshold, setThreshold] = useState(settings['validation.threshold']);
  const [reason, setReason] = useState('');
  return (
    <FormModal title="Paliers de validation" onClose={onClose} onSubmit={async () => {
      const r = await put('/settings', { 'validation.threshold': String(threshold), _reason: reason });
      notify(r._pending ? r._pending.message : 'Paliers de validation enregistrés', r._pending ? 'info' : 'success');
      onSaved();
    }}>
      <Field label="Palier par carte (FCFA)" hint="Au-dessus, la recharge doit être validée par le DAF (ou son délégué)">
        <TextInput type="number" min="0" value={threshold} onChange={setThreshold} required />
      </Field>
      <div style={{ marginTop: 12 }}>
        <Field label="Motif" hint="Paramètre financier : appliqué après l'approbation d'une seconde personne (Admin ou DAF)">
          <TextInput value={reason} onChange={setReason} required />
        </Field>
      </div>
    </FormModal>
  );
}

export default function Validations() {
  const notify = useToast();
  const { user, can } = useSession();
  const [params, setParams] = useSearchParams();
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState('');
  const [page, setPage] = useState(0);
  const [modal, setModal] = useState(false);
  const [, tick] = useState(0);

  const summary = useApi('/validations/summary');
  const pending = useApi(`/validations?status=EN_ATTENTE&size=50${kind ? `&kind=${kind}` : ''}`);
  const history = useApi(`/validations?status=HISTORIQUE&page=${page}&size=10`);
  const detail = useApi(selected ? `/validations/${selected}` : null);
  const settings = useApi(can('PARAMETRES') ? '/settings' : null);
  const reloadAll = () => {
    summary.reload();
    pending.reload();
    history.reload();
    detail.reload();
  };
  useLiveReload(reloadAll, ['operation'], 4000);
  useInterval(() => tick((n) => n + 1), 30_000);

  // sélection par défaut : la première demande en attente
  useEffect(() => {
    const items = pending.data?.items;
    if (!items) return;
    if (!selected || (!items.some((r) => r.id === selected) && !params.get('id'))) {
      setSelected(items[0]?.id || null);
    }
  }, [pending.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const decide = async (approve) => {
    setBusy(true);
    try {
      const r = await post(`/validations/${selected}/${approve ? 'approve' : 'refuse'}`, { comment: comment || null });
      notify(approve ? (r.status === 'APPROUVEE' ? 'Demande approuvée · la carte est créditée' : 'Étape approuvée · transmise à l’étape suivante')
        : 'Demande refusée', approve ? 'success' : 'info');
      setComment('');
      if (params.get('id')) setParams({});
      reloadAll();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const s = summary.data;
  return (
    <>
      <PageHeader
        title="Validations"
        subtitle="Montants au-dessus du palier : DAF (ou son délégué) · actions sensibles de l'Admin : une seconde personne"
        actions={can('PARAMETRES') && <Button icon={Settings} disabled={!settings.data} onClick={() => setModal(true)}>Paliers de validation</Button>}
      />

      <div className="grid g4 mb20">
        <Kpi icon={Clock} tone="orange" label="En attente" value={s ? s.pending : '…'} foot={s ? `${money(s.pendingAmount)} au total` : ''} />
        <Kpi icon={CircleCheck} tone="green" label="Approuvées ce mois" value={s ? s.approvedMonth : '…'} foot={s ? money(s.approvedMonthAmount) : ''} />
        <Kpi icon={CircleX} tone="red" label="Refusées ce mois" value={s ? s.refusedMonth : '…'} foot={s?.lastRefusedAt ? `dernière : ${dayMonth(s.lastRefusedAt)}` : 'aucune'} />
        <Kpi icon={Timer} label="Délai moyen" value={s ? num(s.avgDelayMinutes) : '…'} unit="min"
          foot={s?.avgDelayMinutesPrev ? <><Trend value={`${num(Math.abs(s.avgDelayMinutes - s.avgDelayMinutesPrev))} min`}
            down={s.avgDelayMinutes < s.avgDelayMinutesPrev} good={s.avgDelayMinutes < s.avgDelayMinutesPrev} /> vs mois dernier</> : 'ce mois'} />
      </div>

      <div className="grid split mb20" style={{ gridTemplateColumns: '360px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Demandes en attente</div>
            <Select value={kind} onChange={setKind} placeholder="Toutes"
              options={[{ value: 'RECHARGE', label: 'Recharges' }, { value: 'RECHARGE_MASSE', label: 'En masse' }, { value: 'OUVERTURE_PDV', label: 'Points de vente' },
                { value: 'DROITS_ROLE', label: 'Droits des rôles' }, { value: 'PARAMETRES', label: 'Paramètres' }, { value: 'AJUSTEMENT_SOLDE', label: 'Ajustements' },
                { value: 'COMPTE_SENSIBLE', label: 'Comptes Admin / DAF' }]} />
          </div>
          <div className="stack" style={{ gap: 10, marginTop: 16 }}>
            {pending.data?.items.length === 0 && <Empty>Aucune demande en attente</Empty>}
            {(pending.data?.items || []).map((r) => {
              const active = r.id === selected;
              return (
                <button
                  key={r.id}
                  onClick={() => setSelected(r.id)}
                  style={{
                    textAlign: 'left', borderRadius: 10, padding: '14px 16px', background: active ? '#f7faff' : '#fff',
                    border: active ? '2px solid var(--primary)' : '1px solid var(--border)',
                  }}
                >
                  <div className="row between">
                    <span className="strong" style={{ fontSize: 14 }}>{r.beneficiary}</span>
                    <span className="muted" style={{ fontSize: 12.5 }}>{ago(r.requestedAt)}</span>
                  </div>
                  <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{r.summary.split(' · ')[0]}</div>
                  <div className="row between" style={{ marginTop: 12 }}>
                    <span className="num" style={{ fontSize: 17, fontWeight: 700 }}>{r.amount ? `${fcfa(r.amount)} F` : KIND_LABELS[r.kind]}</span>
                    <Badge tone="orange">{r.statusLabel}</Badge>
                  </div>
                </button>
              );
            })}
          </div>
        </Card>

        <Card className="card-pad" style={{ padding: 24 }}>
          {!selected && <Empty>Sélectionnez une demande</Empty>}
          {selected && (
            <Async state={detail} minHeight={300}>
              {(v) => (
                <>
                  <div className="row between">
                    <div className="card-title">Détail de la demande</div>
                    <span className="muted" style={{ fontSize: 13 }}>
                      {v.canDecide ? `Vous validez en tant que ${ROLE_LABELS[user.role] || user.roleLabel}` : v.status === 'EN_ATTENTE'
                        ? `En attente : ${(v.awaitingRoles || []).map((r) => ROLE_LABELS[r] || r).join(' ou ')}` : v.statusLabel}
                    </span>
                  </div>
                  <div className="row between" style={{ alignItems: 'flex-start', marginTop: 16 }}>
                    <div>
                      <div className="muted">{v.amount ? 'Montant demandé' : KIND_LABELS[v.kind]}</div>
                      <div className="num" style={{ fontSize: 34, fontWeight: 800, letterSpacing: '-0.02em', marginTop: 6 }}>
                        {v.amount ? <>{fcfa(v.amount)} <small className="muted" style={{ fontSize: 15, fontWeight: 600 }}>FCFA</small></> : v.beneficiary}
                      </div>
                    </div>
                    <div style={{ textAlign: 'right' }}>
                      <div className="muted" style={{ fontSize: 13 }}>Réf.</div>
                      <div className="strong num" style={{ letterSpacing: '.04em' }}>{v.ref}</div>
                    </div>
                  </div>
                  <div className="row" style={{ gap: 8, marginTop: 12 }}>
                    {v.aboveThreshold && <Badge tone="orange">Au-dessus du palier de {fcfa(s?.threshold)} F</Badge>}
                    {v.companyCard && <Badge tone="blue">Carte entreprise</Badge>}
                    <StatusBadge status={v.statusLabel} />
                  </div>

                  <div className="grid g2" style={{ gap: 36, marginTop: 16 }}>
                    <div>
                      <Detail label={v.kind === 'OUVERTURE_PDV' ? 'Point de vente' : 'Bénéficiaire'} value={v.beneficiary} />
                      <Detail label="Demandeur" value={[v.requestedByName, v.requestedFrom].filter(Boolean).join(' · ')} />
                      <Detail label="Source" value={v.source || v.summary} />
                    </div>
                    <div>
                      {v.kind === 'OUVERTURE_PDV' && v.pdv ? (
                        <Detail label="Période" value={`${dayMonth(v.pdv.startDate)} → ${dayMonth(v.pdv.endDate)} · ${num(v.pdv.cardsPlanned)} cartes`} />
                      ) : (
                        <Detail label="Cartes" value={(v.cards || []).slice(0, 4).map((c) => c.masked).join(' · ') + ((v.cards || []).length > 4 ? ` +${v.cards.length - 4}` : '') || '—'} />
                      )}
                      <Detail label="Date" value={dateTime(v.requestedAt)} />
                      <Detail label="Paiement" value={v.paymentInfo || '—'} />
                    </div>
                  </div>

                  {v.sensitive && v.payload && <Payload json={v.payload} />}
                  <div className="section-label" style={{ marginTop: 20 }}>Circuit de validation</div>
                  <div className="grid g2" style={{ gap: 14 }}>
                    {(v.steps || []).map((st) => (
                      <div key={st.stepNo} className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '14px 16px' }}>
                        <Avatar initials={st.initials || st.roles.map((r) => (ROLE_LABELS[r] || r)[0]).join('')} color={avatarColor(st.decidedByName || st.roles[0])} size={40} photo={fileUrl(st.photoUrl)} />
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div className="muted" style={{ fontSize: 12.5 }}>{st.roles.map((r) => ROLE_LABELS[r] || r).join(' ou ')}</div>
                          <div className="strong">{st.decidedByName || st.approvers}</div>
                          {st.delegatedFromName && <div className="muted" style={{ fontSize: 12.5 }}>par délégation de {st.delegatedFromName}</div>}
                          {st.comment && <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>« {st.comment} »</div>}
                        </div>
                        <div style={{ textAlign: 'right' }}>
                          <StatusBadge status={{ APPROUVE: 'Approuvé', REFUSE: 'Refusée', EN_ATTENTE: 'En attente', A_VENIR: 'À valider' }[st.status]} />
                          <div className="muted num" style={{ fontSize: 12.5, marginTop: 6 }}>{st.decidedAt ? dateTime(st.decidedAt) : '—'}</div>
                        </div>
                      </div>
                    ))}
                  </div>

                  {v.status === 'EN_ATTENTE' && (
                    <>
                      <div className="section-label" style={{ marginTop: 20 }}>Commentaire</div>
                      <textarea className="textarea" placeholder="Ajouter un commentaire pour le demandeur (facultatif)…" value={comment}
                        onChange={(e) => setComment(e.target.value)} disabled={!v.canDecide} />
                      <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
                        {!v.canDecide && <span className="muted" style={{ marginRight: 'auto', fontSize: 13 }}>
                          {v.requestedByName === user.name ? 'Vous ne pouvez pas valider votre propre demande.'
                            : 'Cette demande ne relève pas de votre rôle (ni d’une délégation active).'}
                        </span>}
                        <Button variant="danger" icon={X} style={{ height: 42 }} disabled={!v.canDecide || busy} onClick={() => decide(false)}>Refuser</Button>
                        <Button variant="success" icon={Check} style={{ height: 42 }} disabled={!v.canDecide || busy} onClick={() => decide(true)}>
                          Approuver {v.amount ? money(v.amount) : ''}
                        </Button>
                      </div>
                    </>
                  )}
                </>
              )}
            </Async>
          )}
        </Card>
      </div>

      <Card>
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Historique des validations</div>
          <Button size="sm" icon={Download} disabled={!history.data?.items.length} onClick={() => exportCsv('validations.csv', [
            { label: 'Réf.', value: 'ref' }, { label: 'Bénéficiaire', value: 'beneficiary' }, { label: 'Montant (F)', value: 'amount' },
            { label: 'Demandé par', value: 'requestedByName' }, { label: 'Décidé par', value: 'deciders' }, { label: 'Décision', value: 'statusLabel' },
            { label: 'Quand', value: (r) => dateTime(r.decidedAt) },
          ], history.data.items)}>Exporter</Button>
        </div>
        <Async state={history}>
          {(data) => (data.items.length === 0 ? <Empty>Aucune décision</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead>
                  <tr><th>Réf.</th><th>Bénéficiaire</th><th className="r">Montant (F)</th><th>Demandé par</th><th>Validé / refusé par</th><th>Décision</th><th>Quand</th></tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id} className="tr-click" onClick={() => setSelected(r.id)}>
                      <td className="strong num" style={{ letterSpacing: '.04em' }}>{r.ref}</td>
                      <td>{r.beneficiary}</td>
                      <td className="r num strong">{r.amount ? fcfa(r.amount) : '—'}</td>
                      <td>{r.requestedByName}</td>
                      <td>{r.deciders || '—'}</td>
                      <td><StatusBadge status={r.statusLabel} /></td>
                      <td className="muted num">{dateTime(r.decidedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="demandes" />
            </>
          ))}
        </Async>
      </Card>

      <Delegations canCreate={['DAF', 'ADMIN'].includes(user.role)} />

      {modal && settings.data && <ThresholdModal settings={settings.data} onClose={() => setModal(false)} onSaved={() => { settings.reload(); summary.reload(); }} />}
    </>
  );
}
