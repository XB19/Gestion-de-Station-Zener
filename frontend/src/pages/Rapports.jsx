import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Mail, Download, FileBarChart, Printer, Check, Save, RefreshCw, Send, Coins } from 'lucide-react';
import { PageHeader, Card, Badge, Button, Segmented, Trend, Avatar, IcoBox, Async, Empty, Pager, StatusBadge, Select, KV } from '../components/ui.jsx';
import { GroupedBars, hourlyBars } from '../components/charts.jsx';
import { FormModal, Field, TextInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { avatarColor, dateTime, exportCsv, fcfa, initials, isoDay, liters, longDate, money, num, PAYMENT_LABELS, pct, time } from '../lib/format.js';

const TONE = { 'À commenter': 'orange', Brouillon: 'blue', Commenté: 'green', Incomplet: 'red' };
const yesterday = () => isoDay(new Date(Date.now() - 86400000));

function Change({ value }) {
  if (value === null || value === undefined) return <span className="muted">—</span>;
  return <Trend value={pct(value)} down={value < 0} />;
}

/** Rapport journalier d'une station : chiffres, litres par heure, postes, commentaire du gérant. */
function DailyReport({ id, onChanged }) {
  const notify = useToast();
  const { can } = useSession();
  const r = useApi(`/reports/daily/${id}`);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setComment(r.data?.comment || '');
  }, [r.data?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (fn, message) => {
    setBusy(true);
    try {
      await fn();
      notify(message);
      r.reload();
      onChanged();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Async state={r} minHeight={400}>
      {(rep) => {
        const d = rep.data;
        const bars = hourlyBars(d.litersByHour);
        const done = rep.status === 'COMMENTE';
        return (
          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Rapport journalier · {rep.stationName}</div>
              <div className="row" style={{ gap: 8 }}>
                <Button size="sm" icon={Printer} onClick={() => window.print()}>PDF</Button>
                {can('RAPPORTS') && !done && (
                  <Button size="sm" icon={RefreshCw} disabled={busy}
                    onClick={() => act(() => post(`/reports/daily/generate${qs({ day: rep.day, stationId: rep.stationId })}`), 'Rapport recalculé')}>
                    Recalculer
                  </Button>
                )}
              </div>
            </div>
            <div className="row" style={{ gap: 8, marginTop: 10 }}>
              <Badge tone="blue">Généré automatiquement · {dateTime(rep.generatedAt)}</Badge>
              <Badge tone={TONE[rep.statusLabel] || 'gray'}>{rep.statusLabel}</Badge>
              {d.offline && <Badge tone="red">Station hors ligne · données partielles</Badge>}
            </div>

            <div className="grid g4" style={{ gap: 12, marginTop: 18 }}>
              {[
                ['Recette', `${fcfa(d.revenue)} F`, <Change value={d.revenueChangePct} />],
                ['Volume', liters(d.volume), <Change value={d.volumeChangePct} />],
                ['Transactions', num(d.transactions), <Change value={d.transactionsChangePct} />],
                ['Écart caisse', `${fcfa(d.cashGap)} F`, d.cashGap ? <span style={{ color: 'var(--red)', fontWeight: 600 }}>À justifier</span>
                  : <span style={{ color: 'var(--green)', fontWeight: 600 }}>Conforme</span>],
              ].map(([l, v, f]) => (
                <div key={l} className="mini-stat">
                  <div className="l">{l}</div>
                  <div className="v num">{v}</div>
                  <div style={{ fontSize: 13, marginTop: 4 }}>{f}</div>
                </div>
              ))}
            </div>

            <div className="row between" style={{ marginTop: 22 }}>
              <div className="section-label" style={{ margin: 0 }}>Litres par heure</div>
              <div className="row" style={{ gap: 16 }}>
                <span className="legend"><i style={{ background: '#1b66b8' }} /> Super</span>
                <span className="legend"><i style={{ background: '#8cc63f' }} /> Gasoil</span>
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <GroupedBars data={bars.data} labels={bars.labels} height={170} />
            </div>

            <div className="section-label" style={{ marginTop: 22 }}>Rapports de poste</div>
            <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
              <table className="table">
                <thead>
                  <tr><th>Poste</th><th>Chef de poste</th><th className="r">Litres</th><th className="r">Recette (F)</th><th>Écart</th></tr>
                </thead>
                <tbody>
                  {(d.shifts || []).length === 0 && <tr><td colSpan={5}><Empty>Aucun poste</Empty></td></tr>}
                  {(d.shifts || []).map((sh, i) => (
                    <tr key={i}>
                      <td>{sh.label}{sh.mode === 'AUTONOME' ? ' · autonome' : ''}</td>
                      <td>{sh.chief || '—'}</td>
                      <td className="r num">{num(sh.liters)}</td>
                      <td className="r num">{fcfa(sh.revenue)}</td>
                      <td>
                        {sh.mode === 'AUTONOME' && !sh.chief ? <Badge tone="navy">Mode auto</Badge>
                          : !sh.closed ? <Badge tone="orange">En cours</Badge>
                            : <Badge tone={sh.gap ? 'red' : 'green'}>{sh.gap ? `${sh.gap > 0 ? '−' : '+'}${fcfa(Math.abs(sh.gap))} F` : '0 F'}</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="grid g2" style={{ gap: 20, marginTop: 18 }}>
              <div>
                <div className="section-label">Encaissements</div>
                {Object.entries(d.byPayment || {}).map(([k, v]) => <KV key={k} k={PAYMENT_LABELS[k] || k} v={`${fcfa(v)} F`} />)}
              </div>
              <div>
                <div className="section-label">Incidents</div>
                <KV k="Paiements par carte refusés" v={num(d.incidents?.cardRefusals)} />
                <KV k="Changements de mode" v={num(d.incidents?.modeChanges)} />
                <KV k="Alertes de stock" v={num(d.incidents?.stockAlerts)} />
              </div>
            </div>

            <div className="section-label" style={{ marginTop: 22 }}>Commentaire du gérant</div>
            <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
              <textarea
                className="textarea"
                style={{ border: 0, minHeight: 100, borderRadius: 0, fontSize: 14, lineHeight: 1.6, boxShadow: 'none' }}
                placeholder="Le rapport est généré automatiquement : commentez les écarts, incidents et actions prévues…"
                value={comment}
                disabled={done || !can('RAPPORTS')}
                onChange={(e) => setComment(e.target.value)}
              />
              <div className="row" style={{ gap: 12, padding: '12px 16px', background: 'var(--surface-2)', borderTop: '1px solid var(--border-2)' }}>
                <Avatar initials={initials(rep.commentByName || '?')} color={avatarColor(rep.commentBy)} size={34} />
                <div style={{ flex: 1 }}>
                  <div className="strong">{rep.commentByName || 'Pas encore commenté'}</div>
                  <div className="muted" style={{ fontSize: 13 }}>
                    {done ? `Validé ${dateTime(rep.validatedAt)}` : rep.commentAt ? `Brouillon enregistré ${time(rep.commentAt)}` : 'À commenter par le gérant'}
                  </div>
                </div>
                {!done && can('RAPPORTS') && (
                  <>
                    <Button size="sm" icon={Save} disabled={busy || comment === (rep.comment || '')}
                      onClick={() => act(() => put(`/reports/daily/${id}/comment`, { comment }), 'Brouillon enregistré')}>
                      Enregistrer le brouillon
                    </Button>
                    <Button size="sm" variant="primary" icon={Check} disabled={busy || !comment.trim()}
                      onClick={() => act(() => post(`/reports/daily/${id}/validate`, { comment }), 'Rapport validé')}>
                      Valider le rapport
                    </Button>
                  </>
                )}
              </div>
            </div>
          </Card>
        );
      }}
    </Async>
  );
}

function CloseShiftModal({ shift, onClose, onSaved }) {
  const notify = useToast();
  const [amount, setAmount] = useState(String(Math.max(0, shift.remainingCash)));
  const [note, setNote] = useState('');
  return (
    <FormModal title={`Clôture du poste · ${shift.employeeName}`} submitLabel="Clôturer le poste" onClose={onClose} onSubmit={async () => {
      const r = await post(`/shifts/${shift.id}/close`, { remittedCash: Number(amount), note: note || null });
      notify(r.remainingCash > 0 ? `Poste clôturé · reste à remettre ${money(r.remainingCash)}` : 'Poste clôturé · caisse juste');
      onSaved();
    }}>
      <KV k="Espèces attendues" v={money(shift.expectedCash)} />
      <KV k="Déjà remis" v={money(shift.remittedCash)} />
      <KV k="Reste à remettre" v={money(shift.remainingCash)} />
      <div style={{ marginTop: 14 }}>
        <Field label="Remise finale (F)"><TextInput type="number" min="0" value={amount} onChange={setAmount} required /></Field>
      </div>
      <div style={{ marginTop: 14 }}>
        <Field label="Note"><TextInput value={note} onChange={setNote} placeholder="Explication d’un écart…" /></Field>
      </div>
    </FormModal>
  );
}

/** Postes du jour : ventes, espèces attendues, remises, écarts ; clôture par le chef de poste ou le gérant. */
function ShiftReports({ day, stationId }) {
  const notify = useToast();
  const { can } = useSession();
  const [page, setPage] = useState(0);
  const [closing, setClosing] = useState(null);
  const shifts = useApi(`/shifts${qs({ day, stationId, page, size: 15 })}`);
  useLiveReload(shifts.reload, ['shift', 'sale'], 5000);
  const remit = async (sh) => {
    const v = window.prompt(`Remise partielle de ${sh.employeeName} (FCFA)`, String(Math.max(0, sh.remainingCash)));
    if (!v) return;
    try {
      await post(`/shifts/${sh.id}/remit`, { amount: Number(v) });
      notify('Remise enregistrée');
      shifts.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Card>
      <div className="card-head" style={{ paddingBottom: 14 }}>
        <div>
          <div className="card-title">Rapports de poste · {longDate(day)}</div>
          <div className="card-sub">Espèces attendues = ventes en espèces du poste · reste à remettre = attendu − remis</div>
        </div>
      </div>
      <Async state={shifts}>
        {(data) => (data.items.length === 0 ? <Empty>Aucun poste ce jour-là</Empty> : (
          <>
            <table className="table">
              <thead>
                <tr><th>Employé</th><th>Station · poste</th><th>Horaires</th><th className="r">Attendu (F)</th><th className="r">Remis (F)</th><th className="r">Reste</th><th>État</th><th /></tr>
              </thead>
              <tbody>
                {data.items.map((sh) => (
                  <tr key={sh.id}>
                    <td>
                      <div className="row" style={{ gap: 10 }}>
                        <Avatar initials={sh.employeeInitials} color={avatarColor(sh.employeeId)} size={30} />
                        <div><div className="cell-title">{sh.employeeName}</div><div className="cell-sub">{sh.roleLabel}</div></div>
                      </div>
                    </td>
                    <td>{sh.stationName}{sh.shiftName ? ` · ${sh.shiftName}` : ''}</td>
                    <td className="num muted">{time(sh.startedAt)} → {sh.endedAt ? time(sh.endedAt) : '…'}</td>
                    <td className="r num">{fcfa(sh.expectedCash)}</td>
                    <td className="r num">{fcfa(sh.remittedCash)}</td>
                    <td className="r num strong" style={{ color: sh.remainingCash > 0 && sh.status === 'CLOS' ? 'var(--red)' : undefined }}>{fcfa(sh.remainingCash)}</td>
                    <td><StatusBadge status={sh.statusLabel} /></td>
                    <td className="r" style={{ whiteSpace: 'nowrap' }}>
                      {can('CLOTURER_POSTE') && (sh.status !== 'CLOS' || sh.remainingCash > 0) && (
                        <Button size="sm" icon={Coins} onClick={() => remit(sh)}>Remise</Button>
                      )}
                      {can('CLOTURER_POSTE') && sh.status !== 'CLOS' && (
                        <Button size="sm" variant="primary" style={{ marginLeft: 6 }} onClick={() => setClosing(sh)}>Clôturer</Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager data={data} page={page} onPage={setPage} label="postes" />
          </>
        ))}
      </Async>
      {closing && <CloseShiftModal shift={closing} onClose={() => setClosing(null)} onSaved={shifts.reload} />}
    </Card>
  );
}

function Transactions({ day, stationId }) {
  const [page, setPage] = useState(0);
  const sales = useApi(`/sales${qs({ from: day, to: day, stationId, page, size: 20 })}`);
  useLiveReload(sales.reload, ['sale'], 4000);
  return (
    <Card>
      <div className="card-head" style={{ paddingBottom: 14 }}>
        <div className="card-title">Transactions · {longDate(day)}</div>
        <Button size="sm" icon={Download} disabled={!sales.data?.items.length} onClick={() => exportCsv(`transactions-${day}.csv`, [
          { label: 'Date', value: (x) => dateTime(x.at) }, { label: 'Lieu', value: (x) => x.stationName || x.pdvName }, { label: 'Type', value: 'kind' },
          { label: 'Détail', value: 'description' }, { label: 'Vendeur', value: 'employeeName' }, { label: 'Paiement', value: 'paymentMethod' },
          { label: 'Montant (F)', value: 'total' },
        ], sales.data.items)}>Exporter la page</Button>
      </div>
      <Async state={sales}>
        {(data) => (data.items.length === 0 ? <Empty>Aucune transaction</Empty> : (
          <>
            <table className="table">
              <thead><tr><th>Heure</th><th>Lieu</th><th>Détail</th><th>Vendeur</th><th>Paiement</th><th className="r">Montant (F)</th></tr></thead>
              <tbody>
                {data.items.map((x) => (
                  <tr key={x.id}>
                    <td className="num muted">{time(x.at, true)}</td>
                    <td>{x.stationName || x.pdvName}{x.pumpCode ? ` · ${x.pumpCode}` : ''}{x.mode === 'AUTONOME' ? ' · auto' : ''}</td>
                    <td>{x.description}</td>
                    <td className="muted">{x.employeeName || '—'}</td>
                    <td>{PAYMENT_LABELS[x.paymentMethod]}{x.cardNumber ? ` ···${x.cardNumber.slice(-4)}` : ''}</td>
                    <td className="r num strong">{fcfa(x.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager data={data} page={page} onPage={setPage} label="transactions" />
          </>
        ))}
      </Async>
    </Card>
  );
}

function EmailSummary({ day }) {
  const notify = useToast();
  const { can } = useSession();
  const preview = useApi(`/reports/email-summary/preview?day=${day}`);
  const settings = useApi('/settings');
  const [f, setF] = useState(null);
  useEffect(() => {
    if (settings.data) setF({ time: settings.data['email.summary_time'], recipients: settings.data['email.summary_recipients'] });
  }, [settings.data]);
  return (
    <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
      <Card className="card-pad">
        <div className="card-title">Aperçu du résumé quotidien</div>
        <Async state={preview}>
          {(p) => (
            <>
              <div className="muted" style={{ marginTop: 6, fontSize: 13 }}>À : {p.recipients.join(', ')}</div>
              <div className="strong" style={{ marginTop: 12 }}>{p.subject}</div>
              <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', fontSize: 14, lineHeight: 1.6, marginTop: 12, background: 'var(--surface-2)', padding: 16, borderRadius: 10 }}>
                {p.body}
              </pre>
            </>
          )}
        </Async>
      </Card>
      <Card className="card-pad">
        <div className="card-title">Envoi automatique</div>
        {f && (
          <>
            <div style={{ marginTop: 14 }}><Field label="Heure d’envoi"><TextInput type="time" value={f.time} onChange={(v) => setF({ ...f, time: v })} disabled={!can('PARAMETRES')} /></Field></div>
            <div style={{ marginTop: 14 }}><Field label="Destinataires (séparés par des virgules)" hint="Les gérants le reçoivent aussi"><TextInput value={f.recipients} onChange={(v) => setF({ ...f, recipients: v })} disabled={!can('PARAMETRES')} /></Field></div>
          </>
        )}
        {can('PARAMETRES') && (
          <>
            <Button block variant="primary" icon={Save} style={{ marginTop: 16 }} onClick={async () => {
              try {
                await put('/settings', { 'email.summary_time': f.time, 'email.summary_recipients': f.recipients });
                notify('Planification enregistrée');
                settings.reload();
                preview.reload();
              } catch (e) {
                notify(e.message, 'error');
              }
            }}>Enregistrer</Button>
            <Button block icon={Send} style={{ marginTop: 10 }} onClick={async () => {
              try {
                await post(`/reports/email-summary/send?day=${day}`);
                notify('Résumé ajouté à la file d’envoi des emails');
              } catch (e) {
                notify(e.message, 'error');
              }
            }}>Envoyer maintenant</Button>
          </>
        )}
      </Card>
    </div>
  );
}

export default function Rapports() {
  const { user, can } = useSession();
  const [params] = useSearchParams();
  const [tab, setTab] = useState('Rapports journaliers');
  const [day, setDay] = useState(params.get('day') || yesterday());
  const [stationId, setStationId] = useState(params.get('station') ? Number(params.get('station')) : null);
  const reports = useApi(`/reports/daily?day=${day}`);
  const stations = useApi('/stations');
  useEffect(() => {
    const items = reports.data?.items;
    if (items?.length && !items.some((r) => r.stationId === stationId)) setStationId(items[0].stationId);
  }, [reports.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const selected = reports.data?.items.find((r) => r.stationId === stationId);
  const t = reports.data?.totals;

  return (
    <>
      <PageHeader
        title="Rapports"
        subtitle="Rapports journaliers et de poste générés automatiquement · le gérant commente au lieu de rédiger"
        actions={
          <>
            {!user.stationBound && <Button icon={Mail} onClick={() => setTab('Résumé email')}>Planifier le résumé email</Button>}
            <Button variant="primary" icon={Download} disabled={!reports.data?.items.length} onClick={() => exportCsv(`rapports-${day}.csv`, [
              { label: 'Station', value: 'stationName' }, { label: 'Jour', value: 'day' }, { label: 'Recette (F)', value: 'revenue' },
              { label: 'Volume (L)', value: 'volume' }, { label: 'Transactions', value: 'transactions' }, { label: 'Écart caisse (F)', value: 'cashGap' },
              { label: 'Statut', value: 'statusLabel' }, { label: 'Commentaire', value: 'comment' },
            ], reports.data.items)}>Exporter</Button>
          </>
        }
      />

      <div className="row between mb20">
        <Segmented
          value={tab}
          onChange={setTab}
          items={[
            { label: 'Rapports journaliers', count: t?.reports },
            ...(can('CLOTURER_POSTE', 'RAPPORTS') ? [{ label: 'Rapports de poste' }] : []),
            ...(can('TRANSACTIONS') ? [{ label: 'Transactions' }] : []),
            ...(!user.stationBound ? [{ label: 'Résumé email' }] : []),
          ]}
        />
        <div className="row" style={{ gap: 8 }}>
          {tab !== 'Rapports journaliers' && tab !== 'Résumé email' && !user.stationBound && (
            <Select value={stationId ?? ''} onChange={(v) => setStationId(v ? Number(v) : null)} placeholder="Toutes les stations"
              options={(stations.data || []).map((x) => ({ value: x.id, label: x.shortName }))} />
          )}
          <label className="input" style={{ height: 38 }}>
            <input type="date" className="num" value={day} max={isoDay()} onChange={(e) => e.target.value && setDay(e.target.value)} />
          </label>
        </div>
      </div>

      {tab === 'Rapports journaliers' && (
        <div className="grid" style={{ gridTemplateColumns: '340px minmax(0, 1fr)', alignItems: 'start' }}>
          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Rapports du {day.slice(8, 10)}/{day.slice(5, 7)}</div>
              {t && <span className="muted" style={{ fontSize: 13 }}>{t.commented}/{t.reports} commentés</span>}
            </div>
            <div style={{ marginTop: 14 }}>
              {reports.data?.items.length === 0 && (
                <Empty>
                  Pas encore de rapport pour ce jour (générés chaque soir).
                  {day === isoDay() && can('RAPPORTS') && (
                    <div style={{ marginTop: 10 }}>
                      <Button size="sm" onClick={() => post('/reports/daily/generate').then(reports.reload)}>Générer maintenant</Button>
                    </div>
                  )}
                </Empty>
              )}
              {(reports.data?.items || []).map((r) => {
                const active = r.stationId === stationId;
                return (
                  <button
                    key={r.id}
                    onClick={() => setStationId(r.stationId)}
                    className="row"
                    style={{
                      width: '100%', gap: 14, textAlign: 'left', padding: '12px 14px', borderRadius: 10, marginBottom: 6,
                      background: active ? '#f5f9fe' : 'transparent', border: active ? '1px solid #bcd3ee' : '1px solid transparent',
                    }}
                  >
                    <IcoBox icon={FileBarChart} tone="blue" size={34} />
                    <div style={{ flex: 1 }}>
                      <div className="strong" style={{ fontSize: 14 }}>{r.stationShortName}</div>
                      <div className="muted num" style={{ fontSize: 13, marginTop: 2 }}>{fcfa(r.revenue)} F · {num(r.volume)} L</div>
                    </div>
                    <Badge tone={TONE[r.statusLabel] || 'gray'}>{r.statusLabel}</Badge>
                  </button>
                );
              })}
            </div>
          </Card>
          {selected ? <DailyReport id={selected.id} onChanged={reports.reload} /> : <Card className="card-pad"><Empty>Sélectionnez un rapport</Empty></Card>}
        </div>
      )}
      {tab === 'Rapports de poste' && <ShiftReports day={day} stationId={stationId} />}
      {tab === 'Transactions' && <Transactions day={day} stationId={stationId} />}
      {tab === 'Résumé email' && <EmailSummary day={day} />}
    </>
  );
}
