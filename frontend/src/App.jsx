import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { SessionProvider, RequireAuth } from './api/session.jsx';
import { LiveProvider } from './api/live.jsx';
import { ToastProvider } from './components/feedback.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Stations from './pages/Stations.jsx';
import StationDetail from './pages/StationDetail.jsx';
import Cartes from './pages/Cartes.jsx';
import CarteDetail from './pages/CarteDetail.jsx';
import Rechargements from './pages/Rechargements.jsx';
import Validations from './pages/Validations.jsx';
import VentesStocks from './pages/VentesStocks.jsx';
import PointsDeVente from './pages/PointsDeVente.jsx';
import Employes from './pages/Employes.jsx';
import Rapports from './pages/Rapports.jsx';
import Journal from './pages/Journal.jsx';
import Clients from './pages/Clients.jsx';

// HashRouter : fonctionne aussi en file:// dans Electron
export default function App() {
  return (
    <HashRouter>
      <SessionProvider>
        <LiveProvider>
          <ToastProvider>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<RequireAuth><Layout /></RequireAuth>}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/stations" element={<Stations />} />
          <Route path="/stations/:id" element={<StationDetail />} />
          <Route path="/cartes" element={<Cartes />} />
          <Route path="/cartes/:id" element={<CarteDetail />} />
          <Route path="/rechargements" element={<Rechargements />} />
          <Route path="/validations" element={<Validations />} />
          <Route path="/ventes-stocks" element={<VentesStocks />} />
          <Route path="/points-de-vente" element={<PointsDeVente />} />
          <Route path="/employes" element={<Employes />} />
          <Route path="/clients" element={<Clients />} />
          <Route path="/rapports" element={<Rapports />} />
          <Route path="/journal" element={<Journal />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
          </ToastProvider>
        </LiveProvider>
      </SessionProvider>
    </HashRouter>
  );
}
