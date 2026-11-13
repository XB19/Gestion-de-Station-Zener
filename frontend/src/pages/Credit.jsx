import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Landmark, Receipt, AlertTriangle, Wallet, FilePlus2, Banknote, Printer, X } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Segmented, Async, Empty, Pager } from '../components/ui.jsx';
import { Modal, FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, qs } from '../api/client.js';
import { dateTime, fcfa, longDate, money, num, masked } from '../lib/format.js';

const BILL = {
  A_PAYER: ['À payer', 'blue'], PARTIELLE: ['Payée en partie', 'orange'], PAYEE: ['Payée', 'green'],
  EN_RETARD: ['En retard', 'red'], REPORTEE: ['Reportée', 'gray'],
};
const PAYMENTS = [
  { value: 'VIREMENT', label: 'Virement' }, { value: 'CHEQUE', label: 'Chèque' }, { value: 'ESPECES', label: 'Espèces' },
  { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'CARTE_BANCAIRE', label: 'Carte bancaire' },
];
const PAYMENT_LABEL = Object.fromEntries(PAYMENTS.map((p) => [p.value, p.label]));
const FILTERS = ['Ouverts', 'En retard', 'Tous'];
const monthLabel = (d) => new Date(d).toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' });

function previousMonth() {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function GenerateModal({ onClose, onSaved }) {
  const notify = useToast();
  const [month, setMonth] = useState(previousMonth());
  return (
    <FormModal title="Établir les relevés du mois" submitLabel="Établir" onClose={onClose} onSubmit={async () => {
      const r = await post('/credit/bills/generate', { month });
      notify(r.created ? `${r.created} relevé(s) établi(s) pour ${r.month}` : `Aucun nouveau relevé pour ${r.month} (déjà établis ou sans consommation)`,
        r.created ? 'success' : 'info');
      onSaved();
    }}>
      <Field label="Mois" hint="Un relevé par client à crédit ; les impayés des mois précédents y sont reportés. Fait automatiquement le jour paramétré.">
        <TextInput type="month" value={month} onChange={setMonth} required />
      </Field>
    </FormModal>
  );
}

function PayModal({ bill, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ amount: String(bill.remaining), paymentMethod: 'VIREMENT', paymentRef: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Règlement · ${bill.ref}`} submitLabel="Enregistrer le règlement" onClose={onClose} onSubmit={async () => {
      await post(`/credit/bills/${bill.id}/payments`, { amount: Number(f.amount), paymentMethod: f.paymentMethod,
        paymentRef: f.paymentRef || null, note: f.note || null });
      notify(`Règlement de ${money(Number(f.amount))} enregistré · cartes reconstituées`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Montant (F)" hint={`Reste dû : ${money(bill.remaining)}`}>
          <TextInput type="number" min="1" max={bill.remaining} value={f.amount} onChange={set('amount')} required autoFocus />
        </Field>
        <Field label="Mode"><SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={PAYMENTS} /></Field>
        <Field label="Référence"><TextInput value={f.paymentRef} onChange={set('paymentRef')} placeholder="N° de virement, de chèque…" /></Field>
        <Field label="Note"><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
      <div className="muted" style={{ marginTop: 12, fontSize: 13 }}>
        Le règlement reconstitue le crédit des cartes du client, en commençant par les plus endettées.
      </div>
    </FormModal>
  );
}

function BillModal({ id, onClose, onChanged }) {
  const bill = useApi(`/credit/bills/${id}`);
  const [pay, setPay] = useState(false);
  return (
    <Modal title="Relevé de crédit" wide onClose={onClose} footer={
      <>
        <Button icon={Printer} onClick={() => window.print()}>Imprimer</Button>
        {bill.data && bill.data.remaining > 0 && bill.data.status !== 'REPORTEE' && (
          <Button variant="primary" icon={Banknote} onClick={() => setPay(true)}>Enregistrer un règlement</Button>
        )}
      </>
    }>
      <Async state={bill}>
        {(b) => (
          <>
            <div className="row between" style={{ flexWrap: 'wrap', rowGap: 8 }}>
              <div>
                <div className="strong" style={{ fontSize: 17 }}>{b.holderName}</div>
                <div className="muted" style={{ fontSize: 13 }}>{[b.holderAddress, b.holderPhone].filter(Boolean).join(' · ')}</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div className="strong num">{b.ref}</div>
                <div className="muted" style={{ fontSize: 13 }}>{monthLabel(b.periodStart)} · échéance {longDate(b.dueDate)}</div>
                <Badge tone={BILL[b.status]?.[1]}>{BILL[b.status]?.[0]}</Badge>
              </div>
            </div>
            <div className="grid g4" style={{ gap: 10, marginTop: 14 }}>
              {[['Report des mois précédents', b.previousDue], ['Consommation du mois', b.consumption], ['Total dû', b.amountDue], ['Reste à payer', b.remaining]].map(([l, v], i) => (
                <div key={l} style={{ background: 'var(--surface-2)', borderRadius: 9, padding: '10px 12px' }}>
                  <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                  <div className="strong num" style={{ marginTop: 4, color: i === 3 && v > 0 ? 'var(--red)' : undefined }}>{money(v)}</div>
                </div>
              ))}
            </div>
            {b.byCard.length > 0 && (
              <>
                <div className="section-label" style={{ marginTop: 16 }}>Par carte</div>
                <div className="table-wrap"><table className="table">
                  <thead><tr><th>Carte</th><th>Véhicule</th><th className="r">Pleins</th><th className="r">Montant</th></tr></thead>
                  <tbody>{b.byCard.map((c) => (
                    <tr key={c.number}><td className="num">{masked(c.number)}</td><td>{c.vehiclePlate || '—'}</td><td className="r num">{c.fills}</td><td className="r num strong">{fcfa(c.amount)}</td></tr>
                  ))}</tbody>
                </table></div>
              </>
            )}
            <div className="section-label" style={{ marginTop: 16 }}>Détail ({b.lines.length} achat(s) à crédit)</div>
            {b.lines.length === 0 ? <Empty>Aucun achat à crédit ce mois</Empty> : (
              <div className="table-wrap" style={{ maxHeight: 280, overflow: 'auto' }}><table className="table">
                <thead><tr><th>Date</th><th>Carte</th><th>Véhicule · chauffeur</th><th>Station</th><th>Détail</th><th className="r">Montant</th></tr></thead>
                <tbody>{b.lines.map((l, i) => (
                  <tr key={i}>
                    <td className="muted num">{dateTime(l.at)}</td><td className="num">{masked(l.number)}</td>
                    <td>{[l.vehiclePlate, l.driverName].filter(Boolean).join(' · ') || '—'}</td><td>{l.stationName || '—'}</td>
                    <td className="muted">{l.detail || '—'}</td><td className="r num strong">{fcfa(l.amount)}</td>
                  </tr>
                ))}</tbody>
              </table></div>
            )}
            <div className="section-label" style={{ marginTop: 16 }}>Règlements</div>
            {b.payments.length === 0 ? <div className="muted" style={{ fontSize: 13 }}>Aucun règlement</div> : (
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Date</th><th>Mode</th><th>Référence</th><th>Reçu par</th><th className="r">Montant</th></tr></thead>
                <tbody>{b.payments.map((p, i) => (
                  <tr key={i}><td className="muted num">{dateTime(p.at)}</td><td>{PAYMENT_LABEL[p.paymentMethod] || p.paymentMethod}</td>
                    <td>{p.paymentRef || '—'}</td><td>{p.employeeName || '—'}</td><td className="r num strong" style={{ color: 'var(--green)' }}>{fcfa(p.amount)}</td></tr>
                ))}</tbody>
              </table></div>
            )}
            {pay && <PayModal bill={b} onClose={() => setPay(false)} onSaved={() => { bill.reload(); onChanged(); }} />}
          </>
        )}
      </Async>
    </Modal>
  );
}

export default function Credit() {
  const clients = useApi('/credit/clients');
  const [holder, setHolder] = useState(null);
  const [filter, setFilter] = useState(FILTERS[0]);
  const [page, setPage] = useState(0);
  const [modal, setModal] = useState(null);
  const status = { Ouverts: 'OUVERTS', 'En retard': 'EN_RETARD', Tous: undefined }[filter];
  const bills = useApi(`/credit/bills${qs({ holderId: holder?.id, status, page, size: 15 })}`);
  const cards = useApi(holder ? `/credit/clients/${holder.id}/cards` : null);
  const reload = () => {
    clients.reload();
    bills.reload();
    cards.reload();
  };
  useLiveReload(() => clients.reload(), ['operation'], 15000);

  const rows = clients.data || [];
  const total = (k) => rows.reduce((s, r) => s + (Number(r[k]) || 0), 0);

  return (
    <>
      <PageHeader title="Clients à crédit" subtitle="Cartes à crédit (paiement au-delà du solde jusqu'au plafond), relevé mensuel au client, règlements · plafond par carte sur la fiche de la carte"
        actions={<Button variant="primary" icon={FilePlus2} onClick={() => setModal('generate')}>Établir les relevés</Button>} />
      <div className="grid g4">
        <Kpi icon={Landmark} label="Crédit accordé" value={fcfa(total('creditLimit'))} unit="F" foot={`${rows.length} client(s) · ${num(total('cards'))} carte(s)`} />
        <Kpi icon={Wallet} tone="orange" label="Crédit utilisé" value={fcfa(total('used'))} unit="F" foot={`dont ${money(total('monthToDate'))} ce mois`} />
        <Kpi icon={Receipt} tone="navy" label="Relevés à encaisser" value={fcfa(total('billedDue'))} unit="F" foot="reste dû sur les relevés ouverts" />
        <Kpi icon={AlertTriangle} tone="red" label="Relevés en retard" value={num(total('overdue'))} foot="échéance dépassée" />
      </div>

      <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1.1fr) minmax(0, 1fr)', alignItems: 'start', marginTop: 16 }}>
        <Card>
          <div className="card-head"><div className="card-title">Clients</div></div>
          <Async state={clients}>
            {(list) => (!list.length ? <Empty>Aucun client à crédit · définissez un plafond de crédit sur une carte</Empty> : (
              <div className="table-wrap"><table className="table">
                <thead><tr><th>Client</th><th className="r">Cartes</th><th className="r">Plafond</th><th className="r">Utilisé</th><th className="r">Relevés dus</th><th /></tr></thead>
                <tbody>
                  {list.map((c) => {
                    const ratio = c.creditLimit ? Math.round((c.used / c.creditLimit) * 100) : 0;
                    return (
                      <tr key={c.id} onClick={() => { setHolder(holder?.id === c.id ? null : c); setPage(0); }} style={{ cursor: 'pointer',
                        background: holder?.id === c.id ? 'var(--primary-50)' : undefined }}>
                        <td className="strong">{c.name}{c.overdue > 0 && <> <Badge tone="red">{c.overdue} en retard</Badge></>}</td>
                        <td className="r num">{c.cards}</td>
                        <td className="r num">{fcfa(c.creditLimit)}</td>
                        <td className="r num" style={{ color: ratio > 85 ? 'var(--red)' : undefined }}>{fcfa(c.used)} <span className="muted">({ratio} %)</span></td>
                        <td className="r num strong">{fcfa(c.billedDue)}</td>
                        <td className="muted" style={{ fontSize: 12 }}>{holder?.id === c.id ? 'filtré' : ''}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table></div>
            ))}
          </Async>
          {holder && (
            <Async state={cards}>
              {(list) => (
                <div style={{ padding: '4px 20px 16px' }}>
                  <div className="section-label">Cartes à crédit · {holder.name}</div>
                  {list.map((c) => (
                    <div key={c.id} className="row between" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)', fontSize: 13.5 }}>
                      <Link to={`/cartes/${c.id}`} className="num strong">{c.numberFormatted}</Link>
                      <span className="muted">{[c.groupName, c.vehiclePlate, c.driverName].filter(Boolean).join(' · ')}</span>
                      <span className="num">solde <b style={{ color: c.balance < 0 ? 'var(--red)' : undefined }}>{fcfa(c.balance)}</b> / plafond {fcfa(c.creditLimit)}</span>
                    </div>
                  ))}
                </div>
              )}
            </Async>
          )}
        </Card>

        <Card>
          <div className="card-head" style={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 8 }}>
            <div className="card-title">Relevés{holder ? ` · ${holder.name}` : ''}</div>
            <span className="row" style={{ gap: 8 }}>
              {holder && <Button size="sm" icon={X} onClick={() => setHolder(null)}>Tous les clients</Button>}
              <Segmented items={FILTERS} value={filter} onChange={(v) => { setFilter(v); setPage(0); }} />
            </span>
          </div>
          <Async state={bills}>
            {(data) => (!data.items.length ? <Empty>Aucun relevé</Empty> : (
              <>
                <div className="table-wrap"><table className="table">
                  <thead><tr><th>Relevé</th><th>Client</th><th>Mois</th><th className="r">Dû</th><th className="r">Reste</th><th>Échéance</th><th>État</th></tr></thead>
                  <tbody>
                    {data.items.map((b) => (
                      <tr key={b.id} onClick={() => setModal({ bill: b.id })} style={{ cursor: 'pointer' }}>
                        <td className="num strong">{b.ref}</td>
                        <td>{b.holderName}</td>
                        <td>{monthLabel(b.periodStart)}</td>
                        <td className="r num">{fcfa(b.amountDue)}</td>
                        <td className="r num strong" style={{ color: b.remaining > 0 && b.status !== 'REPORTEE' ? 'var(--red)' : undefined }}>{fcfa(b.remaining)}</td>
                        <td className="num muted">{longDate(b.dueDate)}</td>
                        <td><Badge tone={BILL[b.status]?.[1]}>{BILL[b.status]?.[0]}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table></div>
                <Pager data={data} page={page} onPage={setPage} label="relevés" />
              </>
            ))}
          </Async>
        </Card>
      </div>

      {modal === 'generate' && <GenerateModal onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.bill && <BillModal id={modal.bill} onClose={() => setModal(null)} onChanged={reload} />}
    </>
  );
}
