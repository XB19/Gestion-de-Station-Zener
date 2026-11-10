import { useEffect, useState } from 'react';
import { Fuel, Play, Pause, Coins, Banknote, Smartphone, CreditCard, Droplet, Receipt, History, CircleAlert } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, KV, Async, Empty } from '../components/ui.jsx';
import { useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { useLiveEvent } from '../api/live.jsx';
import { useSession } from '../api/session.jsx';
import { api, post, qs } from '../api/client.js';
import { dateTime, fcfa, hhmm, liters, money, num, PAYMENT_LABELS, time } from '../lib/format.js';

const PRESETS = [5, 10, 20, 30, 50];

/** Saisie d'une vente à la pompe (mode conventionnel ; en autonome, la carte et le PIN sont exigés). */
function PumpSale({ station, onSold }) {
  const notify = useToast();
  const usable = (station.pumps || []).filter((p) => p.state !== 'HORS_SERVICE');
  const [pumpId, setPumpId] = useState(usable[0]?.id);
  const [qty, setQty] = useState('20');
  const [payment, setPayment] = useState('ESPECES');
  const [cardNumber, setCardNumber] = useState('');
  const [pin, setPin] = useState('');
  const [ref, setRef] = useState('');
  const [quote, setQuote] = useState(null);
  const [busy, setBusy] = useState(false);
  const pump = usable.find((p) => p.id === Number(pumpId));
  const autonomous = station.mode === 'AUTONOME';

  useEffect(() => {
    if (!pump) return undefined;
    const card = payment === 'CARTE' && cardNumber.replace(/\D/g, '').length === 12 ? cardNumber : null;
    const t = setTimeout(() => {
      api(`/sales/quote${qs({ productCode: pump.productCode, cardNumber: card })}`).then(setQuote).catch(() => setQuote(null));
    }, 250);
    return () => clearTimeout(t);
  }, [pump?.productCode, payment, cardNumber]); // eslint-disable-line react-hooks/exhaustive-deps

  const amount = quote ? Math.round(Number(qty || 0) * quote.unitPrice) : null;
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const s = await post('/sales/fuel', {
        uuid: crypto.randomUUID(), pumpId: Number(pumpId), liters: Number(qty), paymentMethod: payment,
        paymentRef: payment === 'MOBILE_MONEY' ? ref || null : null,
        cardNumber: payment === 'CARTE' ? cardNumber : null, pin: payment === 'CARTE' ? pin || null : null,
      });
      notify(`Vente enregistrée · ${num(qty, 1)} L · ${money(s.total)}`);
      setPin('');
      setRef('');
      onSold();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="row between">
        <div className="card-title">Vente à la pompe</div>
        <Badge tone={autonomous ? 'navy' : 'blue'}>{autonomous ? 'Mode autonome · carte + PIN' : 'Mode conventionnel'}</Badge>
      </div>
      <form onSubmit={submit}>
        <label className="field-label" style={{ marginTop: 16 }}>Pistolet</label>
        <div className="grid g4" style={{ gap: 8 }}>
          {(station.pumps || []).map((p) => {
            const down = p.state === 'HORS_SERVICE';
            const on = Number(pumpId) === p.id;
            return (
              <button key={p.id} type="button" disabled={down} onClick={() => setPumpId(p.id)}
                style={{ borderRadius: 9, padding: '10px 12px', textAlign: 'left', background: on ? '#f7faff' : '#fff',
                  border: on ? '2px solid var(--primary)' : down ? '1px solid #f3b4b4' : '1px solid var(--border)' }}>
                <div className="strong">{p.code}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{down ? 'Hors service' : p.productName}</div>
              </button>
            );
          })}
        </div>
        <label className="field-label" style={{ marginTop: 16 }}>Litres</label>
        <div className="row" style={{ gap: 8 }}>
          {PRESETS.map((v) => (
            <button key={v} type="button" className="btn num" onClick={() => setQty(String(v))}
              style={String(v) === qty ? { borderColor: 'var(--primary)', color: 'var(--primary)', background: 'var(--primary-50)' } : undefined}>
              {v} L
            </button>
          ))}
          <label className="input" style={{ flex: 1 }}>
            <input className="num strong" type="number" min="0.1" step="0.1" value={qty} onChange={(e) => setQty(e.target.value)} required />
            <span className="muted">L</span>
          </label>
        </div>
        <label className="field-label" style={{ marginTop: 16 }}>Paiement</label>
        <div className="row" style={{ gap: 8 }}>
          {[['ESPECES', Banknote], ['MOBILE_MONEY', Smartphone], ['CARTE', CreditCard]].map(([m, Icon]) => (
            <button key={m} type="button" className="btn" onClick={() => setPayment(m)} disabled={autonomous && m !== 'CARTE'}
              style={payment === m ? { borderColor: 'var(--primary)', color: 'var(--primary)', background: 'var(--primary-50)' } : undefined}>
              <Icon size={16} /> {PAYMENT_LABELS[m]}
            </button>
          ))}
        </div>
        {payment === 'MOBILE_MONEY' && (
          <label className="input" style={{ marginTop: 10 }}><input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="Référence TMoney / Flooz" /></label>
        )}
        {payment === 'CARTE' && (
          <div className="form-grid" style={{ marginTop: 10 }}>
            <label className="input"><input className="num" value={cardNumber} onChange={(e) => setCardNumber(e.target.value)} placeholder="N° de carte" required /></label>
            <label className="input"><input type="password" inputMode="numeric" value={pin} onChange={(e) => setPin(e.target.value)} placeholder={autonomous ? 'PIN (obligatoire)' : 'PIN (facultatif)'} /></label>
          </div>
        )}
        <div className="row between" style={{ background: 'var(--navy)', color: '#fff', borderRadius: 12, padding: '14px 20px', marginTop: 16 }}>
          <div>
            <div style={{ fontSize: 13, opacity: 0.75 }}>{pump?.productName} · {quote ? `${fcfa(quote.unitPrice)} F/L` : '…'}{quote && quote.profile !== 'GRAND_PUBLIC' ? ` · tarif ${quote.profile.toLowerCase().replace('_', ' ')}` : ''}</div>
            <div className="num" style={{ fontSize: 24, fontWeight: 700, marginTop: 2 }}>{amount != null ? `${fcfa(amount)} F` : '—'}</div>
          </div>
          <Button variant="primary" icon={Receipt} type="submit" disabled={busy || !pump || !Number(qty)} style={{ height: 44 }}>Encaisser</Button>
        </div>
      </form>
    </Card>
  );
}

export default function MonPoste() {
  const notify = useToast();
  const { user } = useSession();
  const me = useApi('/shifts/me');
  const station = useApi(user.stationId ? `/stations/${user.stationId}` : null);
  const [config, setConfig] = useState('');
  useLiveReload(() => {
    me.reload();
    station.reload();
  }, ['shift', 'station'], 3000);
  useLiveEvent(['sale'], (_, e) => {
    if (e?.stationId === user.stationId) me.reload();
  });

  const act = async (path, message) => {
    try {
      await post(`/shifts/${path}`, path === 'start' ? { configId: config ? Number(config) : null, pumps: 'P1, P2, P3, P4, P5, P6, P7, P8' } : {});
      notify(message);
      me.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Async state={me} minHeight={400}>
      {(m) => {
        const sh = m.shift;
        const open = m.current;
        return (
          <>
            <PageHeader
              title="Mon poste"
              subtitle={`${user.name} · ${user.stationName || 'aucune station'} · ventes, espèces à remettre et clôture`}
              actions={
                <>
                  {!open && (
                    <>
                      <label className="select" style={{ height: 38 }}>
                        <select value={config} onChange={(e) => setConfig(e.target.value)}>
                          <option value="">Poste du moment</option>
                          {(m.configs || []).map((c) => <option key={c.id} value={c.id}>{c.name} {hhmm(c.startTime)}–{hhmm(c.endTime)}</option>)}
                        </select>
                      </label>
                      <Button variant="primary" icon={Play} onClick={() => act('start', 'Prise de poste enregistrée')}>Prendre mon poste</Button>
                    </>
                  )}
                  {open && sh.status === 'EN_POSTE' && <Button icon={Pause} onClick={() => act('pause', 'Poste en pause')}>Pause</Button>}
                  {open && sh.status === 'PAUSE' && <Button variant="primary" icon={Play} onClick={() => act('resume', 'Reprise du poste')}>Reprendre</Button>}
                </>
              }
            />

            {m.outstanding > 0 && (
              <div className="alert-box red mb20">
                <CircleAlert size={18} style={{ flexShrink: 0, marginTop: 1 }} />
                <span><b>Reste à remettre sur vos postes clos : {money(m.outstanding)}.</b> Remettez-le au chef de poste ou au gérant.</span>
              </div>
            )}

            <div className="grid g4 mb20">
              <Kpi icon={Banknote} tone="orange" label={open ? 'Espèces à remettre' : 'Reste à remettre (dernier poste)'} value={sh ? fcfa(sh.remainingCash) : '—'} unit="FCFA"
                foot={sh ? `attendu ${fcfa(sh.expectedCash)} · remis ${fcfa(sh.remittedCash)}` : 'aucun poste'} />
              <Kpi icon={Receipt} label="Recette du poste" value={sh ? fcfa(sh.revenue) : '—'} unit="FCFA" foot={sh ? `${num(sh.transactions)} ventes` : ''} />
              <Kpi icon={Droplet} tone="green" label="Litres servis" value={sh ? num(sh.liters) : '—'} unit="L" foot={sh?.shiftName ? `Poste ${sh.shiftName.toLowerCase()}` : ''} />
              <Kpi icon={Coins} tone="navy" label="Paiements" value={sh ? fcfa(sh.cardSales + sh.mobileMoneySales) : '—'} unit="FCFA"
                foot={sh ? `carte ${fcfa(sh.cardSales)} · mobile ${fcfa(sh.mobileMoneySales)}` : ''} />
            </div>

            <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px', alignItems: 'start' }}>
              <div className="stack">
                {open && sh.status === 'EN_POSTE' && station.data && <PumpSale station={station.data} onSold={() => { me.reload(); station.reload(); }} />}
                {open && sh.status === 'PAUSE' && <Card className="card-pad"><Empty>Poste en pause · reprenez-le pour enregistrer des ventes</Empty></Card>}
                {!open && <Card className="card-pad"><Empty>Prenez votre poste pour enregistrer des ventes à la pompe.</Empty></Card>}

                <Card>
                  <div className="card-head" style={{ paddingBottom: 12 }}>
                    <div className="card-title">{open ? 'Ventes du poste en cours' : 'Ventes du dernier poste'}</div>
                    <span className="muted" style={{ fontSize: 13 }}>{sh ? `depuis ${time(sh.startedAt)}` : ''}</span>
                  </div>
                  {!sh?.sales?.length ? <Empty>Aucune vente</Empty> : (
                    <table className="table">
                      <thead><tr><th>Heure</th><th>Détail</th><th>Pistolet</th><th>Paiement</th><th className="r">Montant (F)</th></tr></thead>
                      <tbody>
                        {sh.sales.slice(0, 30).map((x) => (
                          <tr key={x.id}>
                            <td className="num muted">{time(x.at, true)}</td>
                            <td>{x.description}</td>
                            <td>{x.pumpCode || '—'}</td>
                            <td>{PAYMENT_LABELS[x.paymentMethod]}{x.cardNumber ? ` ···${x.cardNumber.slice(-4)}` : ''}</td>
                            <td className="r num strong">{fcfa(x.total)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </Card>
              </div>

              <div className="stack">
                <Card className="card-pad">
                  <div className="row between">
                    <div className="card-title">Clôture</div>
                    {sh && <StatusBadge status={sh.statusLabel} />}
                  </div>
                  {sh ? (
                    <div style={{ marginTop: 8 }}>
                      <KV k="Début" v={dateTime(sh.startedAt)} />
                      {sh.endedAt && <KV k="Fin" v={dateTime(sh.endedAt)} />}
                      <KV k="Ventes en espèces" v={money(sh.cashSales)} />
                      <KV k="Espèces attendues" v={money(sh.expectedCash)} />
                      <KV k="Déjà remis" v={money(sh.remittedCash)} />
                      <KV k={sh.remainingCash < 0 ? 'Excédent' : 'Reste à remettre'} v={<span style={{ color: sh.remainingCash > 0 ? 'var(--red)' : 'var(--green)', fontWeight: 700 }}>{money(Math.abs(sh.remainingCash))}</span>} />
                      {sh.closedByName && <KV k="Clôturé par" v={sh.closedByName} />}
                      <div className="section-label" style={{ marginTop: 14 }}>Remises</div>
                      {(sh.remittances || []).length === 0 && <div className="muted" style={{ fontSize: 13 }}>Aucune remise pour le moment</div>}
                      {(sh.remittances || []).map((r) => (
                        <div key={r.id} className="kv"><span className="k">{time(r.at)} · {r.receivedByName}</span><span className="v num">{money(r.amount)}</span></div>
                      ))}
                      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>La remise et la clôture sont enregistrées par le chef de poste ou le gérant (Rapports › Rapports de poste).</div>
                    </div>
                  ) : <Empty>Aucun poste</Empty>}
                </Card>

                <Card className="card-pad">
                  <div className="row" style={{ gap: 8 }}><History size={17} /><div className="card-title">Mes derniers postes</div></div>
                  <div style={{ marginTop: 8 }}>
                    {(m.history || []).length === 0 && <Empty>Aucun poste</Empty>}
                    {(m.history || []).slice(0, 8).map((h) => (
                      <div key={h.id} className="kv">
                        <span className="k">{dateTime(h.startedAt)}{h.shiftName ? ` · ${h.shiftName}` : ''}</span>
                        <span className="v">
                          <span className="num" style={{ color: h.status === 'CLOS' && h.remainingCash > 0 ? 'var(--red)' : undefined }}>
                            {h.status === 'CLOS' ? (h.remainingCash ? `${h.remainingCash > 0 ? '−' : '+'}${fcfa(Math.abs(h.remainingCash))} F` : 'Juste') : h.statusLabel}
                          </span>
                        </span>
                      </div>
                    ))}
                  </div>
                </Card>

                {station.data && (
                  <Card className="card-pad">
                    <div className="row" style={{ gap: 8 }}><Fuel size={17} /><div className="card-title">Cuves de la station</div></div>
                    <div style={{ marginTop: 8 }}>
                      {(station.data.tanks || []).map((t) => (
                        <div key={t.id} className="kv"><span className="k">{t.productName}</span><span className="v num">{liters(t.volume)} · {num(t.pct)} %</span></div>
                      ))}
                    </div>
                  </Card>
                )}
              </div>
            </div>
          </>
        );
      }}
    </Async>
  );
}
