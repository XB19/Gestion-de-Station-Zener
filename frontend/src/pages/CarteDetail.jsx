import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, Lock, Pencil, Wallet, Fuel, Droplet, Flame, Clock, ChevronDown, ArrowRight } from 'lucide-react';
import { PageHeader, Card, Badge, StatusBadge, Button, Checkbox, Toggle, KV, BankCard, Progress } from '../components/ui.jsx';

const PRODUCTS = [
  { name: 'Super', icon: Fuel, color: 'var(--primary)', on: true },
  { name: 'Gasoil', icon: Fuel, color: 'var(--green)', on: true },
  { name: 'Pétrole', icon: Fuel, color: 'var(--orange)', on: false },
  { name: 'Lubrifiants', icon: Droplet, color: 'var(--primary)', on: true },
  { name: 'Gaz', icon: Flame, color: 'var(--orange)', on: false },
];

const LIMITS = [
  { l: 'Plafond journalier', v: '200 000', pct: 71, used: '142 000 F utilisés', color: 'var(--primary)' },
  { l: 'Plafond mensuel', v: '3 000 000', pct: 47, used: '1 418 200 F utilisés', color: 'var(--green-500)' },
  { l: 'Plafond par transaction', v: '80 000', pct: 0, used: '— utilisés', color: 'var(--primary)' },
];

const DAYS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];

const OPS = [
  ['02/11 14:02', 'Vente Gasoil · 120 L', 'Zener Kara Centre', '−81 000', 'P3 · Autonome'],
  ['02/11 06:18', 'Vente Gasoil · 95 L', 'Zener Sokodé', '−64 125', 'P1 · Komlan T.'],
  ['01/11 10:30', 'Recharge en masse', 'Siège Lomé', '+2 500 000', 'Validée par DAF'],
  ['31/10 17:44', 'Vente Lubrifiant 5 L', 'Zener Bè Kpota', '−22 500', 'Boutique'],
  ['31/10 07:02', 'Refus · hors horaires', 'Zener Atakpamé', '0', '04:51 < 05:00'],
];

export default function CarteDetail() {
  const navigate = useNavigate();
  const [products, setProducts] = useState(PRODUCTS.map((p) => p.on));
  const [days, setDays] = useState([true, true, true, true, true, true, false]);

  return (
    <>
      <PageHeader
        breadcrumb={
          <div className="breadcrumb">
            <Link to="/cartes">Cartes</Link> <ChevronRight size={14} /> <Link to="/cartes">Cartes flottes</Link>
            <ChevronRight size={14} /> <span className="cur num">6011 2847 0093</span>
          </div>
        }
        title="Carte ···0093 · Translom SARL"
        tags={
          <>
            <StatusBadge status="Active" />
            <Badge tone="blue">Flotte</Badge>
            <Badge tone="gray">Carte à puce</Badge>
            <Badge tone="blue" style={{ background: 'var(--primary-50)' }}>Groupe : Camions Nord</Badge>
          </>
        }
        actions={
          <>
            <Button variant="danger" icon={Lock}>Bloquer</Button>
            <Button icon={Pencil}>Modifier</Button>
            <Button variant="primary" icon={Wallet} onClick={() => navigate('/rechargements')}>Recharger</Button>
          </>
        }
      />

      <div className="grid" style={{ gridTemplateColumns: '350px minmax(0, 1fr)', alignItems: 'start' }}>
        <div className="stack">
          <BankCard number="6011 2847 0093" holder="TRANSLOM SARL" exp="12/28" large />

          <Card className="card-pad">
            <div className="muted">Solde disponible</div>
            <div className="num" style={{ fontSize: 30, fontWeight: 700, margin: '12px 0 18px', letterSpacing: '-0.02em' }}>
              1 284 500 <small className="muted" style={{ fontSize: 14, fontWeight: 600 }}>FCFA</small>
            </div>
            <div className="grid g2" style={{ gap: 12 }}>
              {[['Rechargé ce mois', '2 500 000 F'], ['Consommé ce mois', '1 418 200 F']].map(([l, v]) => (
                <div key={l} style={{ background: 'var(--surface-2)', borderRadius: 9, padding: '12px 14px' }}>
                  <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                  <div className="strong num" style={{ marginTop: 4 }}>{v}</div>
                </div>
              ))}
            </div>
          </Card>

          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Informations</div>
              <button className="link-more"><Pencil size={16} /></button>
            </div>
            <div style={{ marginTop: 8 }}>
              <KV k="Entreprise" v="Translom SARL" />
              <KV k="Véhicule" v="TG-4521-BK · Camion" />
              <KV k="Chauffeur" v="Kodjo Agbodjan" />
              <KV k="Créée le" v="12 janv. 2025 · Siège" />
              <KV k="Point de vente" v="Siège Lomé · Essi L." />
              <KV k="Validation requise" v="Comptable + DAF" />
            </div>
          </Card>
        </div>

        <div className="stack">
          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Permissions</div>
              <span className="muted" style={{ fontSize: 13 }}>Appliquées par toutes les stations, y compris en mode autonome</span>
            </div>

            <div className="section-label" style={{ marginTop: 16 }}>Produits autorisés</div>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(5, 1fr)', gap: 10 }}>
              {PRODUCTS.map((p, i) => {
                const Icon = p.icon;
                const on = products[i];
                return (
                  <button
                    key={p.name}
                    onClick={() => setProducts(products.map((x, j) => (j === i ? !x : x)))}
                    className="row"
                    style={{
                      gap: 10, height: 44, padding: '0 14px', borderRadius: 9, background: '#fff', fontWeight: 600,
                      border: on ? '1.5px solid var(--primary)' : '1px solid var(--border)',
                    }}
                  >
                    <Checkbox checked={on} />
                    <Icon size={17} color={p.color} strokeWidth={1.8} />
                    {p.name}
                  </button>
                );
              })}
            </div>

            <div className="grid g3" style={{ gap: 14, marginTop: 18 }}>
              {LIMITS.map((x) => (
                <div key={x.l} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: '16px 18px' }}>
                  <div className="row between">
                    <span className="strong">{x.l}</span>
                    <button className="link-more"><Pencil size={15} /></button>
                  </div>
                  <div className="num" style={{ fontSize: 21, fontWeight: 700, marginTop: 12 }}>
                    {x.v} <small className="muted" style={{ fontSize: 12.5 }}>FCFA</small>
                  </div>
                  <div className="row" style={{ gap: 12, marginTop: 16 }}>
                    <Progress value={x.pct} color={x.color} />
                    <span className="muted num" style={{ fontSize: 12.5 }}>{x.pct} %</span>
                  </div>
                  <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>{x.used}</div>
                </div>
              ))}
            </div>

            <div className="row" style={{ gap: 28, marginTop: 20, alignItems: 'flex-start' }}>
              <div>
                <div className="section-label">Jours autorisés</div>
                <div className="row" style={{ gap: 8 }}>
                  {DAYS.map((d, i) => (
                    <button
                      key={i}
                      onClick={() => setDays(days.map((x, j) => (j === i ? !x : x)))}
                      style={{
                        width: 38, height: 34, borderRadius: 7, border: 0, fontWeight: 600,
                        background: days[i] ? 'var(--primary)' : 'var(--chip)', color: days[i] ? '#fff' : 'var(--muted-2)',
                      }}
                    >
                      {d}
                    </button>
                  ))}
                </div>
              </div>
              <div>
                <div className="section-label">Horaires</div>
                <div className="row" style={{ gap: 10 }}>
                  <span className="input num" style={{ width: 110, color: 'var(--text)' }}><Clock size={16} color="var(--muted-2)" /> 05:00</span>
                  <span className="muted">à</span>
                  <span className="input num" style={{ width: 110, color: 'var(--text)' }}><Clock size={16} color="var(--muted-2)" /> 21:00</span>
                </div>
              </div>
              <div style={{ marginLeft: 'auto' }}>
                <div className="section-label">Stations</div>
                <span className="select" style={{ height: 40 }}><Fuel size={16} /> Toutes (42) <ChevronDown size={15} /></span>
              </div>
            </div>

            <div className="grid g2" style={{ gap: 14, marginTop: 20 }}>
              {[
                ['Utilisable en mode autonome', 'La carte peut servir sans pompiste, dans les limites ci-dessus'],
                ['Validation des recharges élevées', 'Au-dessus de 500 000 F : Comptable puis DAF'],
              ].map(([t, s]) => (
                <div key={t} className="row" style={{ gap: 14, background: 'var(--surface-2)', borderRadius: 10, padding: '16px 18px' }}>
                  <Toggle />
                  <div>
                    <div className="strong">{t}</div>
                    <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>{s}</div>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          <Card>
            <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
              <div className="card-title">Dernières opérations</div>
              <Button size="sm" iconRight={ArrowRight}>Tout l’historique</Button>
            </div>
            <table className="table">
              <thead>
                <tr><th>Date</th><th>Opération</th><th>Station</th><th className="r">Montant (F)</th><th>Détail</th></tr>
              </thead>
              <tbody>
                {OPS.map(([d, o, s, m, det]) => (
                  <tr key={d}>
                    <td className="muted num">{d}</td>
                    <td className="strong">{o}</td>
                    <td>{s}</td>
                    <td className="r num strong" style={{ color: m.startsWith('+') ? 'var(--green)' : m === '0' ? 'var(--red)' : undefined }}>{m}</td>
                    <td className="muted">{det}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
      </div>
    </>
  );
}
