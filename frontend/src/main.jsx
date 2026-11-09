import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import '@fontsource/inter/900.css';
import './styles/global.css';
import App from './App.jsx';

// Sans session, les pages protégées redirigent vers l'écran de connexion
if (!window.location.hash) {
  window.location.hash = '#/';
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
