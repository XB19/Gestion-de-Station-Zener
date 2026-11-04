import { useState } from 'react';
import { Settings, Clock, CircleCheck, CircleX, Timer, SlidersHorizontal, X, Check, Download } from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar } from '../components/ui.jsx';

const REQUESTS = [
  { id: 'VAL-2026-1184', who: 'Translom SARL', what: 'Recharge flotte · 2 cartes', amount: '1 950 000', when: 'il y a 12 min', status: 'Attente DAF' },
  { id: 'VAL-2026-1185', who: 'Lomé Logistique', what: 'Recharge en masse · 18 cartes', amount: '3 600 000', when: 'il y a 40 min', status: 'Attente Comptable' },
  { id: 'VAL-2026-1186', who: 'Agro-Plus Togo', what: 'Recharge unitaire', amount: '750 000', when: 'il y a 1 h', status: 'Attente Comptable' },
  { id: 'VAL-2026-1187', who: 'Komi Mensah', what: 'Recharge VIP', amount: '500 000', when: 'il y a 2 h', status: 'Attente Comptable' },
];

const HISTORY = [
  ['VAL-2026-1183', 'Lomé Logistique', '2 800 000', 'Essi L.', 'Abla G. · Mawuli K.', 'Approuvée', '01/11 11:20'],
  ['VAL-2026-1179', 'BTP Kara Construction', '1 200 000', 'Afi D.', 'Abla G.', 'Refusée', '28/10 15:02'],
  ['VAL-2026-1176', 'Agro-Plus Togo', '650 000', 'Essi L.', 'Abla G.', 'Approuvée', '27/10 09:41'],
];

function Detail({ label, value }) {
  return (
    <div className="kv" style={{ padding: '12px 0' }}>
      <span className="k">{label}</span>
      <span className="v">{value}</span>
    </div>
  );
}

export default function Validations() {
  const [selected, setSelected] = useState(REQUESTS[0].id);
  const req = REQUESTS.find((r) => r.id === selected);

  return (
    <>
      <PageHeader
        title="Validations"
        subtitle="Approbation des montants au-dessus du palier · Comptable et DAF pour les cartes entreprises"
        actions={<Button icon={Settings}>Paliers de validation</Button>}
      />

      <div className="grid g4 mb20">
        <Kpi icon={Clock} tone="orange" label="En attente" value="4" foot="6 800 000 F au total" />
        <Kpi icon={CircleCheck} tone="green" label="Approuvées ce mois" value="38" foot="142,6 M F" />
        <Kpi icon={CircleX} tone="red" label="Refusées" value="3" foot="dernier : 28 oct." />
        <Kpi icon={Timer} label="Délai moyen" value="42" unit="min" foot={<><Trend value="8 min" down good /> vs octobre</>} />
      </div>

      <div className="grid mb20" style={{ gridTemplateColumns: '360px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Demandes en attente</div>
            <Button size="sm" icon={SlidersHorizontal}>Filtrer</Button>
          </div>
          <div className="stack" style={{ gap: 10, marginTop: 16 }}>
            {REQUESTS.map((r) => {
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
                    <span className="strong" style={{ fontSize: 14 }}>{r.who}</span>
                    <span className="muted" style={{ fontSize: 12.5 }}>{r.when}</span>
                  </div>
                  <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{r.what}</div>
                  <div className="row between" style={{ marginTop: 12 }}>
                    <span className="num" style={{ fontSize: 17, fontWeight: 700 }}>{r.amount} F</span>
                    <Badge tone="orange">{r.status}</Badge>
                  </div>
                </button>
              );
            })}
          </div>
        </Card>

        <Card className="card-pad" style={{ padding: 24 }}>
          <div className="row between">
            <div className="card-title">Détail de la demande</div>
            <span className="muted" style={{ fontSize: 13 }}>Vous validez en tant que DAF</span>
          </div>
          <div className="row between" style={{ alignItems: 'flex-start', marginTop: 16 }}>
            <div>
              <div className="muted">Montant demandé</div>
              <div className="num" style={{ fontSize: 34, fontWeight: 800, letterSpacing: '-0.02em', marginTop: 6 }}>
                {req.amount} <small className="muted" style={{ fontSize: 15, fontWeight: 600 }}>FCFA</small>
              </div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <div className="muted" style={{ fontSize: 13 }}>Réf.</div>
              <div className="strong num" style={{ letterSpacing: '.04em' }}>{req.id}</div>
            </div>
          </div>
          <div className="row" style={{ gap: 8, marginTop: 12 }}>
            <Badge tone="orange">Au-dessus du palier de 500 000 F</Badge>
            <Badge tone="blue">Carte entreprise</Badge>
          </div>

          <div className="grid g2" style={{ gap: 36, marginTop: 16 }}>
            <div>
              <Detail label="Entreprise" value={req.who} />
              <Detail label="Demandeur" value="Essi Lawson · Siège" />
              <Detail label="Source" value="Import en masse" />
            </div>
            <div>
              <Detail label="Cartes" value="···0101 · ···0115" />
              <Detail label="Date" value="02/11/2026 · 10:28" />
              <Detail label="Paiement" value={<span className="row" style={{ gap: 4 }}>Virement reçu <Check size={15} /></span>} />
            </div>
          </div>

          <div className="section-label" style={{ marginTop: 20 }}>Circuit de validation</div>
          <div className="grid g2" style={{ gap: 14 }}>
            {[
              { i: 'AG', c: 'teal', role: 'Comptable', n: 'Abla Gbeasor', s: 'Approuvé', t: '10:51' },
              { i: 'MK', c: 'orange', role: 'DAF', n: 'Mawuli Kpodar', s: 'En attente', t: '—' },
            ].map((v) => (
              <div key={v.role} className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '14px 16px' }}>
                <Avatar initials={v.i} color={v.c} size={40} />
                <div style={{ flex: 1 }}>
                  <div className="muted" style={{ fontSize: 12.5 }}>{v.role}</div>
                  <div className="strong">{v.n}</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <StatusBadge status={v.s} />
                  <div className="muted num" style={{ fontSize: 12.5, marginTop: 6 }}>{v.t}</div>
                </div>
              </div>
            ))}
          </div>

          <div className="section-label" style={{ marginTop: 20 }}>Commentaire</div>
          <textarea className="textarea" placeholder="Ajouter un commentaire pour le demandeur (facultatif)…" />
          <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
            <Button variant="danger" icon={X} style={{ height: 42 }}>Refuser</Button>
            <Button variant="success" icon={Check} style={{ height: 42 }}>Approuver {req.amount} F</Button>
          </div>
        </Card>
      </div>

      <Card>
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Historique des validations</div>
          <Button size="sm" icon={Download}>Exporter</Button>
        </div>
        <table className="table">
          <thead>
            <tr><th>Réf.</th><th>Bénéficiaire</th><th className="r">Montant (F)</th><th>Demandé par</th><th>Validé / refusé par</th><th>Décision</th><th>Quand</th></tr>
          </thead>
          <tbody>
            {HISTORY.map(([ref, b, m, d, v, s, q]) => (
              <tr key={ref}>
                <td className="strong num" style={{ letterSpacing: '.04em' }}>{ref}</td>
                <td>{b}</td>
                <td className="r num strong">{m}</td>
                <td>{d}</td>
                <td>{v}</td>
                <td><StatusBadge status={s} /></td>
                <td className="muted num">{q}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
