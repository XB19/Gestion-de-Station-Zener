// Lance Electron en retirant ELECTRON_RUN_AS_NODE (défini par le terminal de VS Code),
// sinon Electron démarre comme un simple Node.js et la fenêtre ne s'ouvre pas.
const { spawn } = require('child_process');
const electron = require('electron');

const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;
if (process.argv.includes('--dev')) env.VITE_DEV_SERVER_URL = 'http://localhost:5173';

const child = spawn(electron, ['.'], { stdio: 'inherit', env, windowsHide: false });
child.on('close', (code) => process.exit(code ?? 0));
