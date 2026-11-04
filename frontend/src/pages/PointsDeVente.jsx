import { useState } from 'react';
import {
  Map as MapIcon, Plus, Store, CreditCard, HandCoins, Clock, CalendarDays, Users, CloudOff, Banknote, Smartphone,
  Printer, CircleCheck,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar, IcoBox } from '../components/ui.jsx';

const PDVS = [
  { id: 'siege', icon: Store, tone: 'blue', name: 'Siège Lomé', sub: 'Permanent · Boulevard du 13 Janvier', status: 'Ouvert', agents: 3, cards: '640 / 900 cartes', amount: '1 240 000 F' },
  { id: 'foire', icon: CalendarDays, tone: 'purple', name: 'Foire Internationale de Lomé', sub: 'Temporaire · 30 oct. → 8 nov. · J-6', status: 'Ouvert', agents: 4, cards: '214 / 300 cartes', amount: '386 000 F' },
  { id: 'agro', icon: CalendarDays, tone: 'purple', name: 'Stand Agro-Plus Togo', sub: 'Temporaire · Zone portuaire · 2 → 3 nov.', status: 'Hors ligne', agents: 2, cards: '38 / 100 cartes', amount: '94 000 F', sync: '12 op. en attente de synchro' },
  { id: 'kara', icon: CalendarDays, tone: 'purple', name: 'Campagne Université de Kara', sub: 'Temporaire · Demande · 10 → 14 nov.', status: 'À valider', agents: 3, cards: '0 / 250 cartes', amount: '— F' },
];

const AGENTS = [
  { i: 'AD', c: 'green', n: 'Afi Dzifa Adjo', r: 'Agent · pièce d’identité vérifiée', v: '92' },
  { i: 'MK', c: 'navy', n: 'Mawuena Kossi', r: 'Agent', v: '71' },
  { i: 'SA', c: 'teal', n: 'Sélom Ahadji', r: 'Agent', v: '51' },
  { i: 'DA', c: 'orange', n: 'Délali Agbo', r: 'Superviseur', v: '—' },
];

export default function PointsDeVente() {
  const [selected, setSelected] = useState('foire');

  return (
    <>
      <PageHeader
        title="Points de vente"
        subtitle="Point de vente permanent au siège et points de vente temporaires · fonctionne hors connexion"
        actions={
          <>
            <Button icon={MapIcon}>Carte du réseau</Button>
            <Button variant="primary" icon={Plus}>Nouveau point de vente</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Store} label="Points de vente ouverts" value="3" foot="1 permanent · 2 temporaires" />
        <Kpi icon={CreditCard} tone="green" label="Cartes vendues aujourd’hui" value="286" foot={<><Trend value="31 %" /> vs lundi dernier</>} />
        <Kpi icon={HandCoins} tone="navy" label="Encaissé aujourd’hui" value="1 720 000" unit="FCFA" foot="Espèces 58 % · Mobile money 42 %" />
        <Kpi icon={Clock} tone="orange" label="Demandes d’ouverture" value="2" foot="en attente Admin / DAF" />
      </div>

      <div className="grid" style={{ gridTemplateColumns: '440px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Points de vente</div>
            <Button size="sm" icon={Plus}>Demander une ouverture</Button>
          </div>
          <div className="stack" style={{ gap: 12, marginTop: 18 }}>
            {PDVS.map((p) => {
              const active = p.id === selected;
              return (
                <button
                  key={p.id}
                  onClick={() => setSelected(p.id)}
                  style={{
                    textAlign: 'left', borderRadius: 10, padding: '14px 16px', background: '#fff',
                    border: active ? '2px solid var(--primary)' : '1px solid var(--border)',
                  }}
                >
                  <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}>
                    <IcoBox icon={p.icon} tone={p.tone} size={34} />
                    <div style={{ flex: 1 }}>
                      <div className="strong" style={{ fontSize: 14 }}>{p.name}</div>
                      <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{p.sub}</div>
                    </div>
                    {p.status === 'Hors ligne' ? <Badge tone="orange" dot>Hors ligne</Badge> : <StatusBadge status={p.status} />}
                  </div>
                  <div className="row" style={{ marginTop: 12, gap: 18, color: 'var(--muted)', fontSize: 13 }}>
                    <span className="row" style={{ gap: 6 }}><Users size={15} /> {p.agents} agents</span>
                    <span className="row num" style={{ gap: 6 }}><CreditCard size={15} /> {p.cards}</span>
                    <span className="strong num" style={{ marginLeft: 'auto', color: 'var(--text)' }}>{p.amount}</span>
                  </div>
                  {p.sync && (
                    <div className="row" style={{ gap: 6, marginTop: 10, color: 'var(--orange)', fontSize: 13 }}>
                      <CloudOff size={15} /> {p.sync}
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </Card>

        <div className="stack">
          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Foire Internationale de Lomé · clôture</div>
              <span className="muted" style={{ fontSize: 13 }}>Fin prévue le 8 nov. · clôture manuelle possible</span>
            </div>
            <div className="row" style={{ gap: 8, marginTop: 12 }}>
              <Badge tone="purple">Temporaire</Badge>
              <Badge tone="gray">Validée par le DAF · 24 oct.</Badge>
              <Badge tone="green" dot>Synchronisé · il y a 30 s</Badge>
            </div>

            <div className="section-label" style={{ marginTop: 18 }}>Rapprochement des cartes</div>
            <div className="grid g4" style={{ gap: 12 }}>
              {[['Cartes attribuées', '300', 'var(--text)'], ['Cartes vendues', '214', 'var(--primary)'], ['Cartes rendues', '86', 'var(--green)'], ['Écart', '0', 'var(--green)']].map(([l, v, c]) => (
                <div key={l} className="mini-stat">
                  <div className="l">{l}</div>
                  <div className="v num" style={{ color: c, fontSize: 24 }}>{v}</div>
                </div>
              ))}
            </div>

            <div className="section-label" style={{ marginTop: 22 }}>Comptage des encaissements</div>
            {[
              { icon: Banknote, l: 'Espèces', expected: '226 000', counted: '226 000', res: <Badge tone="green">Conforme</Badge> },
              { icon: Smartphone, l: 'Mobile money (Flooz / T-Money)', expected: '160 000', counted: '158 000', res: <Badge tone="red">−2 000 F</Badge> },
            ].map((r) => (
              <div key={r.l} className="row" style={{ gap: 16, padding: '12px 0', borderBottom: '1px solid var(--border-2)' }}>
                <IcoBox icon={r.icon} tone="gray" />
                <span className="strong" style={{ flex: 1 }}>{r.l}</span>
                <div style={{ textAlign: 'right' }}>
                  <div className="muted" style={{ fontSize: 12 }}>Attendu</div>
                  <div className="num">{r.expected}</div>
                </div>
                <div>
                  <div className="muted" style={{ fontSize: 12, textAlign: 'right' }}>Compté</div>
                  <label className="input" style={{ width: 150 }}>
                    <input className="num strong" style={{ textAlign: 'right' }} defaultValue={r.counted} />
                  </label>
                </div>
                <div style={{ width: 100, textAlign: 'right' }}>{r.res}</div>
              </div>
            ))}

            <div className="row between" style={{ background: 'var(--navy)', color: '#fff', borderRadius: 12, padding: '16px 22px', marginTop: 18 }}>
              <div>
                <div style={{ fontSize: 13, opacity: 0.75 }}>Total encaissé · 214 cartes vendues</div>
                <div className="num" style={{ fontSize: 24, fontWeight: 700, marginTop: 4 }}>384 000 FCFA</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontSize: 13, opacity: 0.75 }}>Écart global</div>
                <div className="num" style={{ fontSize: 21, fontWeight: 700, color: '#ff7b7b', marginTop: 4 }}>−2 000 F</div>
              </div>
            </div>
            <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
              <Button icon={Printer}>Imprimer</Button>
              <Button variant="primary" icon={CircleCheck}>Clôturer et générer le rapport</Button>
            </div>
          </Card>

          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Agents affectés</div>
              <span className="muted" style={{ fontSize: 13 }}>Ventes de cartes</span>
            </div>
            <div style={{ marginTop: 8 }}>
              {AGENTS.map((a) => (
                <div key={a.n} className="list-item" style={{ borderBottom: 0, padding: '9px 0' }}>
                  <Avatar initials={a.i} color={a.c} size={38} />
                  <div style={{ flex: 1 }}>
                    <div className="strong">{a.n}</div>
                    <div className="muted" style={{ fontSize: 13 }}>{a.r}</div>
                  </div>
                  <span className="strong num" style={{ width: 50, textAlign: 'right' }}>{a.v}</span>
                  <span className="muted" style={{ width: 60, textAlign: 'right' }}>cartes</span>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
