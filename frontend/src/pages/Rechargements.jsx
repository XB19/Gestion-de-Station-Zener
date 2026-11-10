import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  History, Wallet, Send, FileSpreadsheet, Download, Upload, Check, CircleCheck, CircleX, Smartphone, Search,
} from 'lucide-react';
import { PageHeader, Card, Badge, StatusBadge, Button, Segmented, Checkbox, KV, Async, Empty, Pager } from '../components/ui.jsx';
import { useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, download, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { cardNumber, dateTime, fcfa, money, PAYMENT_LABELS, time } from '../lib/format.js';

export function ValidationSteps({ steps }) {
  return (
    <div>
      {steps.map((st, i) => (
        <div key={st.t} className="row" style={{ alignItems: 'flex-start', gap: 14, paddingBottom: 16 }}>
          <span
            style={{
              width: 30, height: 30, borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center',
              fontWeight: 600, fontSize: 13,
              background: st.refused ? 'var(--red-500)' : st.done ? 'var(--green-500)' : st.current ? 'var(--primary-50)' : 'var(--chip)',
              color: st.done || st.refused ? '#fff' : st.current ? 'var(--primary)' : 'var(--muted)',
            }}
          >
            {st.refused ? <CircleX size={16} /> : st.done ? <Check size={16} strokeWidth={3} /> : i + 1}
          </span>
          <div>
            <div className="strong">{st.t}</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>{st.s}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

const AMOUNTS = [5000, 10000, 25000, 50000];
const PAYMENTS = [
  { value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' },
  { value: 'VIREMENT', label: 'Virement' }, { value: 'CARTE_BANCAIRE', label: 'Carte bancaire' },
];

/** Choix du montant : boutons rapides + saisie libre. */
function AmountPicker({ value, onChange }) {
  const [other, setOther] = useState(!AMOUNTS.includes(value));
  return (
    <>
      <div className="grid auto-cols" style={{ gridTemplateColumns: 'repeat(5, 1fr)', gap: 8, '--col': '96px' }}>
        {AMOUNTS.map((a) => (
          <button
            key={a}
            type="button"
            className="btn num"
            onClick={() => { setOther(false); onChange(a); }}
            style={{ padding: 0, fontWeight: 500, ...(!other && value === a ? { borderColor: 'var(--primary)', borderWidth: 1.5, color: 'var(--primary)', background: 'var(--primary-50)' } : {}) }}
          >
            {fcfa(a)}
          </button>
        ))}
        <button type="button" className="btn" onClick={() => setOther(true)}
          style={{ padding: 0, fontWeight: 500, ...(other ? { borderColor: 'var(--primary)', borderWidth: 1.5, color: 'var(--primary)', background: 'var(--primary-50)' } : {}) }}>
          Autre
        </button>
      </div>
      {other && (
        <label className="input" style={{ height: 42, marginTop: 8 }}>
          <input className="num strong" type="number" min="1" value={value || ''} onChange={(e) => onChange(Number(e.target.value))} placeholder="Montant en FCFA" autoFocus />
          <span className="muted">F</span>
        </label>
      )}
    </>
  );
}

/** Recharge unitaire au guichet. */
function UnitRecharge({ initialCard, threshold, onDone }) {
  const notify = useToast();
  const [number, setNumber] = useState(initialCard || '');
  const [card, setCard] = useState(null);
  const [error, setError] = useState(null);
  const [amount, setAmount] = useState(25000);
  const [payment, setPayment] = useState('ESPECES');
  const [ref, setRef] = useState('');
  const [busy, setBusy] = useState(false);

  const lookup = async (n = number) => {
    setError(null);
    setCard(null);
    if (n.replace(/\D/g, '').length !== 12) return;
    try {
      setCard(await api(`/cards/lookup/${n.replace(/\D/g, '')}`));
    } catch (e) {
      setError(e.message);
    }
  };
  useEffect(() => {
    if (initialCard) lookup(initialCard);
  }, [initialCard]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await post('/recharges', { cardNumber: number, amount, paymentMethod: payment, paymentRef: ref || null });
      notify(r.status === 'CREDITEE' ? `Carte créditée de ${money(amount)}` : `Au-dessus du palier : demande de validation envoyée (${money(amount)})`,
        r.status === 'CREDITEE' ? 'success' : 'info');
      lookup();
      onDone();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="card-title">Recharge unitaire</div>
      <div className="card-sub">Au guichet · au-dessus de {fcfa(threshold)} F la recharge part en validation</div>
      <form onSubmit={submit}>
        <label className="field-label" style={{ marginTop: 18 }}>N° de carte</label>
        <div className="row" style={{ gap: 10 }}>
          <label className="input" style={{ height: 44, flex: 1 }}>
            <input className="num strong" value={number} onChange={(e) => setNumber(e.target.value)} onBlur={() => lookup()} placeholder="6011 2847 0093" required />
          </label>
          <Button type="button" icon={Search} onClick={() => lookup()}>Vérifier</Button>
        </div>
        {error && <div className="form-error">{error}</div>}
        {card && (
          <div className="row" style={{ gap: 16, background: 'var(--surface-2)', borderRadius: 10, padding: '12px 16px', marginTop: 12 }}>
            <div style={{ flex: 1 }}>
              <div className="strong">{card.holderName}</div>
              <div className="muted" style={{ fontSize: 13 }}>{card.typeLabel} · {card.groupName || card.holderKind?.toLowerCase()}</div>
            </div>
            <StatusBadge status={card.statusLabel} />
            <div style={{ textAlign: 'right' }}>
              <div className="muted" style={{ fontSize: 12 }}>Solde</div>
              <div className="strong num">{money(card.balance)}</div>
            </div>
          </div>
        )}
        <label className="field-label" style={{ marginTop: 16 }}>Montant</label>
        <AmountPicker value={amount} onChange={setAmount} />
        {amount > threshold && <Badge tone="orange" style={{ marginTop: 10 }}>Au-dessus du palier : validation Comptable{card?.holderKind === 'ENTREPRISE' ? ' puis DAF' : ''}</Badge>}
        <div className="form-grid" style={{ marginTop: 14 }}>
          <div>
            <label className="field-label">Paiement</label>
            <label className="input">
              <select value={payment} onChange={(e) => setPayment(e.target.value)}>
                {PAYMENTS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
              </select>
            </label>
          </div>
          <div>
            <label className="field-label">Référence</label>
            <label className="input"><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder={payment === 'ESPECES' ? 'Facultatif' : 'N° de transaction'} /></label>
          </div>
        </div>
        <Button variant="primary" icon={Wallet} block style={{ height: 42, marginTop: 16 }} disabled={busy || !card || card.status !== 'ACTIVE' || !amount} type="submit">
          Recharger {amount ? money(amount) : ''}
        </Button>
      </form>
    </Card>
  );
}

/** Recharge à distance : nom + numéro suffisent s'ils concordent. */
function RemoteRecharge({ onDone }) {
  const notify = useToast();
  const [number, setNumber] = useState('6011 3302 1209');
  const [name, setName] = useState('Akossiwa Lawson');
  const [match, setMatch] = useState(null);
  const [amount, setAmount] = useState(25000);
  const [ref, setRef] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setMatch(null);
    if (number.replace(/\D/g, '').length !== 12 || name.trim().length < 3) return undefined;
    const t = setTimeout(() => {
      api(`/recharges/remote-check${qs({ cardNumber: number.replace(/\D/g, ''), name })}`).then((r) => setMatch(r.match)).catch(() => setMatch(false));
    }, 350);
    return () => clearTimeout(t);
  }, [number, name]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await post('/recharges/remote', { cardNumber: number, holderName: name, amount, paymentMethod: 'MOBILE_MONEY', paymentRef: ref || null });
      notify(r.status === 'CREDITEE' ? `Recharge à distance de ${money(amount)} créditée` : 'Recharge à distance en attente de validation',
        r.status === 'CREDITEE' ? 'success' : 'info');
      onDone();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="card-title">Recharge à distance</div>
      <div className="card-sub">Nom + numéro de carte suffisent</div>
      <form onSubmit={submit}>
        <label className="field-label" style={{ marginTop: 18 }}>N° de carte</label>
        <label className="input" style={{ height: 42 }}><input className="num strong" value={number} onChange={(e) => setNumber(e.target.value)} required /></label>
        <label className="field-label" style={{ marginTop: 14 }}>Nom de l’utilisateur</label>
        <label className="input" style={{ height: 42 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} required />
          {match === true && <CircleCheck size={18} color="var(--green)" />}
          {match === false && <CircleX size={18} color="var(--red)" />}
        </label>
        <div className="muted" style={{ fontSize: 13, marginTop: 8, color: match === false ? 'var(--red)' : undefined }}>
          {match === null ? 'Saisissez le nom tel qu’il figure sur la carte' : match ? 'Nom et numéro concordent' : 'Le nom ne correspond pas au titulaire'}
        </div>
        <label className="field-label" style={{ marginTop: 14 }}>Montant</label>
        <AmountPicker value={amount} onChange={setAmount} />
        <label className="field-label" style={{ marginTop: 14 }}>Référence mobile money</label>
        <label className="input" style={{ height: 42 }}><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="TMoney / Flooz" /></label>
        <Button icon={Smartphone} variant="primary" block style={{ height: 42, marginTop: 14 }} type="submit" disabled={busy || !match || !amount}>
          Envoyer via mobile money
        </Button>
      </form>
    </Card>
  );
}

/** Recharge en masse : import d'un fichier, contrôle ligne par ligne, crédit et validation. */
function BulkRecharge({ onDone }) {
  const notify = useToast();
  const fileRef = useRef(null);
  const [batchId, setBatchId] = useState(null);
  const [page, setPage] = useState(0);
  const [payment, setPayment] = useState('VIREMENT');
  const [paymentRef, setPaymentRef] = useState('');
  const [busy, setBusy] = useState(false);
  const batch = useApi(batchId ? `/recharges/batches/${batchId}?page=${page}&size=10` : null);
  const validation = useApi(batch.data?.validation ? `/validations/${batch.data.validation.id}` : null);

  const importFile = async (file) => {
    if (!file) return;
    setBusy(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const b = await api(`/recharges/batches${qs({ paymentMethod: payment, paymentRef })}`, { method: 'POST', form });
      setBatchId(b.id);
      setPage(0);
      notify(`${b.lines} lignes importées et contrôlées`);
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };
  const toggle = async (line, included) => {
    try {
      await put(`/recharges/batches/${batchId}/lines/${line.id}`, { included });
      batch.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const run = async (path, message) => {
    setBusy(true);
    try {
      await post(`/recharges/batches/${batchId}/${path}`);
      notify(message);
      batch.reload();
      onDone();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const b = batch.data;
  const v = validation.data;
  const steps = b ? [
    { t: 'Import & contrôle', s: `${b.createdByName} · ${time(b.createdAt)}`, done: true },
    ...(v?.steps || [{ roles: ['COMPTABLE'], status: 'A_VENIR' }]).map((st) => ({
      t: `Validation ${st.roles.includes('DAF') && st.roles.length === 1 ? 'DAF' : st.roles.includes('COMPTABLE') ? 'Comptable' : st.roles.join(' / ')}`,
      s: st.decidedByName ? `${st.decidedByName} · ${time(st.decidedAt)}` : st.approvers || (b.toValidate ? 'À la soumission' : 'Non requise'),
      done: st.status === 'APPROUVE', refused: st.status === 'REFUSE', current: st.status === 'EN_ATTENTE',
    })),
    { t: 'Crédit des cartes', s: b.status === 'TERMINE' ? 'Terminé' : 'Automatique après approbation', done: b.status === 'TERMINE' },
  ] : [];

  return (
    <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
      <div className="stack">
        <Card className="card-pad">
          <div className="card-title">Rechargement en masse</div>
          <div className="card-sub">Fichier de cartes (.xlsx, .csv) : numéro de carte ; montant ; libellé</div>
          <input ref={fileRef} type="file" accept=".csv,.xlsx,.xls" style={{ display: 'none' }} onChange={(e) => importFile(e.target.files[0])} />
          <div className="row" style={{ gap: 14, marginTop: 18, border: '1.5px dashed #b9cde6', background: '#f7faff', borderRadius: 10, padding: '16px 18px' }}>
            <span className="ico-box tone-green" style={{ width: 44, height: 44 }}><FileSpreadsheet size={21} strokeWidth={1.7} /></span>
            <div style={{ flex: 1 }}>
              <div className="strong">{b?.fileName || 'Aucun fichier importé'}</div>
              <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
                {b ? `${b.lines} lignes · importé ${time(b.createdAt)} par ${b.createdByName}` : 'Téléchargez le modèle, complétez-le puis importez-le'}
              </div>
            </div>
            <Button icon={Download} onClick={() => download('/recharges/template', 'modele_recharges.csv').catch((e) => notify(e.message, 'error'))}>Modèle de fichier</Button>
            <Button icon={Upload} variant={b ? undefined : 'primary'} disabled={busy} onClick={() => fileRef.current?.click()}>{b ? 'Remplacer' : 'Importer'}</Button>
          </div>
          <div className="form-grid" style={{ marginTop: 14 }}>
            <div>
              <label className="field-label">Paiement du lot</label>
              <label className="input">
                <select value={payment} onChange={(e) => setPayment(e.target.value)}>
                  {PAYMENTS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                </select>
              </label>
            </div>
            <div>
              <label className="field-label">Référence</label>
              <label className="input"><input value={paymentRef} onChange={(e) => setPaymentRef(e.target.value)} placeholder="Virement Ecobank…" /></label>
            </div>
          </div>
        </Card>

        {b && (
          <Card>
            <div className="card-head" style={{ paddingBottom: 16 }}>
              <div>
                <div className="card-title">Aperçu de l’import</div>
                <div className="card-sub">Contrôle automatique de chaque ligne avant envoi</div>
              </div>
              <div className="row" style={{ gap: 8 }}>
                <Badge tone="green">{b.ready} prêtes</Badge>
                <Badge tone="orange">{b.toValidate} à valider</Badge>
                <Badge tone="red">{b.errors} erreur{b.errors > 1 ? 's' : ''}</Badge>
              </div>
            </div>
            <div className="table-wrap"><table className="table">
              <thead>
                <tr><th style={{ width: 40 }} /><th>N° de carte</th><th>Titulaire</th><th className="r">Montant (F)</th><th>Contrôle</th></tr>
              </thead>
              <tbody>
                {b.items.map((l) => {
                  const editable = b.status === 'BROUILLON' && !l.rechargeStatus && ['PRETE', 'VALIDATION_REQUISE'].includes(l.checkStatus);
                  return (
                    <tr key={l.id}>
                      <td>{editable ? <Checkbox checked={l.included} onChange={(x) => toggle(l, x)} /> : <Checkbox checked={l.included && !!l.rechargeStatus} />}</td>
                      <td className="num strong" style={{ letterSpacing: '.02em' }}>{l.cardNumberFormatted}</td>
                      <td>{l.holderLabel || '—'}</td>
                      <td className="r num strong">{l.amount != null ? fcfa(l.amount) : '—'}</td>
                      <td>{l.rechargeStatus === 'CREDITEE' ? <StatusBadge status="Créditée" /> : <StatusBadge status={l.checkLabel} />}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table></div>
            <Pager data={{ total: b.lines, size: b.size, items: b.items }} page={page} onPage={setPage} label="lignes" />
          </Card>
        )}
      </div>

      <div className="stack">
        <Card className="card-pad">
          <div className="card-title">Récapitulatif</div>
          {!b && <Empty>Importez un fichier pour voir le récapitulatif</Empty>}
          {b && (
            <>
              <div style={{ marginTop: 8 }}>
                <KV k="Cartes" v={`${b.validCards} retenues`} />
                <KV k="Montant total" v={money(b.totalAmount)} />
                <KV k="Dont soumis à validation" v={money(b.amountToValidate)} />
                <KV k="Palier de validation" v={`${fcfa(b.threshold)} F / carte`} />
                <KV k="Mode de paiement" v={[PAYMENT_LABELS[b.paymentMethod], b.paymentRef].filter(Boolean).join(' · ')} />
                {b.validation && <KV k="Demande" v={`${b.validation.ref}`} />}
              </div>
              <div className="section-label" style={{ marginTop: 16 }}>Circuit de validation</div>
              <ValidationSteps steps={steps} />
              {b.status === 'BROUILLON' && (
                <>
                  <Button variant="primary" icon={Send} block style={{ height: 42 }} disabled={busy || b.validCards === 0}
                    onClick={() => run('submit', b.toValidate ? 'Cartes prêtes créditées, lignes au-dessus du palier envoyées en validation' : 'Lot crédité')}>
                    Soumettre pour validation
                  </Button>
                  <Button block style={{ height: 42, marginTop: 10 }} disabled={busy || !b.readyToCredit}
                    onClick={() => run('credit-ready', `${b.readyToCredit} cartes créditées`)}>
                    Créditer les {b.readyToCredit} cartes prêtes
                  </Button>
                </>
              )}
              {b.status !== 'BROUILLON' && <Badge tone={b.status === 'TERMINE' ? 'green' : 'orange'}>{b.status === 'TERMINE' ? 'Lot terminé' : 'Lot soumis · en validation'}</Badge>}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

export default function Rechargements() {
  const [params] = useSearchParams();
  const { can } = useSession();
  const [mode, setMode] = useState(params.get('mode') === 'masse' ? 'En masse' : 'Recharge unitaire');
  const [page, setPage] = useState(0);
  const history = useApi(`/recharges?page=${page}&size=10`);
  const settings = useApi('/settings');
  const historyRef = useRef(null);
  useLiveReload(history.reload, ['operation'], 4000);
  const threshold = Number(settings.data?.['validation.threshold'] || 500000);

  return (
    <>
      <PageHeader
        title="Rechargements"
        subtitle="Recharges unitaires, à distance et en masse, avec validation des montants élevés"
        actions={<Button icon={History} onClick={() => historyRef.current?.scrollIntoView({ behavior: 'smooth' })}>Historique</Button>}
      />

      <div className="mb20">
        <Segmented
          items={[{ label: 'Recharge unitaire', icon: Wallet }, { label: 'À distance', icon: Send }, { label: 'En masse', icon: FileSpreadsheet }]}
          value={mode}
          onChange={setMode}
        />
      </div>

      {can('RECHARGER') ? (
        <>
          {mode === 'En masse' && <BulkRecharge onDone={history.reload} />}
          {mode !== 'En masse' && (
            <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px', alignItems: 'start' }}>
              {mode === 'Recharge unitaire' ? <UnitRecharge initialCard={params.get('card')} threshold={threshold} onDone={history.reload} />
                : <RemoteRecharge onDone={history.reload} />}
              <Card className="card-pad">
                <div className="card-title">Circuit de validation</div>
                <div className="card-sub">Au-dessus du palier de {fcfa(threshold)} F par carte</div>
                <div style={{ marginTop: 16 }}>
                  <ValidationSteps steps={[
                    { t: 'Demande de recharge', s: 'Guichet, à distance ou en masse', done: true },
                    { t: 'Validation Comptable', s: 'Montant et paiement vérifiés' },
                    { t: 'Validation DAF', s: 'Cartes entreprises uniquement' },
                    { t: 'Crédit de la carte', s: 'Automatique après approbation' },
                  ]} />
                </div>
              </Card>
            </div>
          )}
        </>
      ) : (
        <Card className="card-pad mb20"><Empty>Consultation seule : vous n’avez pas le droit de recharger des cartes.</Empty></Card>
      )}

      <div ref={historyRef} />
      <Card style={{ marginTop: 20 }}>
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Historique des recharges</div>
          <span className="muted" style={{ fontSize: 13 }}>Mise à jour en direct</span>
        </div>
        <Async state={history}>
          {(data) => (data.items.length === 0 ? <Empty>Aucune recharge</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead>
                  <tr><th>Réf.</th><th>Quand</th><th>Carte</th><th>Titulaire</th><th className="r">Montant (F)</th><th>Canal · paiement</th><th>Par · lieu</th><th>Statut</th></tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id}>
                      <td className="strong num">{r.ref}</td>
                      <td className="muted num">{dateTime(r.createdAt)}</td>
                      <td className="num">{cardNumber(r.number)}</td>
                      <td>{r.holderName}</td>
                      <td className="r num strong">{fcfa(r.amount)}</td>
                      <td>{{ UNITAIRE: 'Guichet', DISTANCE: 'À distance', MASSE: 'En masse' }[r.channel]} · {PAYMENT_LABELS[r.paymentMethod]}</td>
                      <td className="muted">{[r.requestedByName, r.place].filter(Boolean).join(' · ')}</td>
                      <td><StatusBadge status={{ CREDITEE: 'Créditée', EN_VALIDATION: 'En validation', REFUSEE: 'Refusée' }[r.status]} /></td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="recharges" />
            </>
          ))}
        </Async>
      </Card>
    </>
  );
}

