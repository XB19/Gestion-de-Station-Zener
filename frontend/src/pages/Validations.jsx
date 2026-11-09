import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Settings, Clock, CircleCheck, CircleX, Timer, X, Check, Download } from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar, Async, Empty, Pager, Select } from '../components/ui.jsx';
import { FormModal, Field, TextInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload, useInterval } from '../api/hooks.js';
import { post, put } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, avatarColor, dateTime, dayMonth, exportCsv, fcfa, money, num } from '../lib/format.js';

const ROLE_LABELS = { COMPTABLE: 'Comptable', DAF: 'DAF', ADMIN: 'Admin' };
const KIND_LABELS = { RECHARGE: 'Recharge', RECHARGE_MASSE: 'Recharge en masse', OUVERTURE_PDV: 'Ouverture de point de vente' };

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
  const [daf, setDaf] = useState(settings['validation.company_needs_daf'] === 'true');
  return (
    <FormModal title="Paliers de validation" onClose={onClose} onSubmit={async () => {
      await put('/settings', { 'validation.threshold': String(threshold), 'validation.company_needs_daf': String(daf) });
      notify('Paliers de validation enregistrés');
      onSaved();
    }}>
      <Field label="Palier par carte (FCFA)" hint="Au-dessus, la recharge doit être validée par la comptabilité">
        <TextInput type="number" min="0" value={threshold} onChange={setThreshold} required />
      </Field>
      <label className="row" style={{ gap: 10, marginTop: 16 }}>
        <input type="checkbox" checked={daf} onChange={(e) => setDaf(e.target.checked)} />
        Cartes entreprises : validation du DAF après la Comptable
      </label>
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
        subtitle="Approbation des montants au-dessus du palier · Comptable et DAF pour les cartes entreprises"
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

      <div className="grid mb20" style={{ gridTemplateColumns: '360px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Demandes en attente</div>
            <Select value={kind} onChange={setKind} placeholder="Toutes"
              options={[{ value: 'RECHARGE', label: 'Recharges' }, { value: 'RECHARGE_MASSE', label: 'En masse' }, { value: 'OUVERTURE_PDV', label: 'Points de vente' }]} />
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

                  <div className="section-label" style={{ marginTop: 20 }}>Circuit de validation</div>
                  <div className="grid g2" style={{ gap: 14 }}>
                    {(v.steps || []).map((st) => (
                      <div key={st.stepNo} className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '14px 16px' }}>
                        <Avatar initials={st.initials || st.roles.map((r) => (ROLE_LABELS[r] || r)[0]).join('')} color={avatarColor(st.decidedByName || st.roles[0])} size={40} />
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div className="muted" style={{ fontSize: 12.5 }}>{st.roles.map((r) => ROLE_LABELS[r] || r).join(' ou ')}</div>
                          <div className="strong">{st.decidedByName || st.approvers}</div>
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
                          {v.requestedByName === user.name ? 'Vous ne pouvez pas valider votre propre demande.' : 'Cette étape ne relève pas de votre rôle.'}
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
              <table className="table">
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
              </table>
              <Pager data={data} page={page} onPage={setPage} label="demandes" />
            </>
          ))}
        </Async>
      </Card>

      {modal && settings.data && <ThresholdModal settings={settings.data} onClose={() => setModal(false)} onSaved={() => { settings.reload(); summary.reload(); }} />}
    </>
  );
}
