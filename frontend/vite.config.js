import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// base './' : les fichiers construits doivent se charger via file:// dans Electron
export default defineConfig({
  plugins: [react()],
  base: './',
  server: { port: 5173 },
});
