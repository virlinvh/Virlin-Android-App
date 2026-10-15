import { invoke } from '@tauri-apps/api/core';
import './style.css';

const addresses = document.querySelector('#addresses');
const start = document.querySelector('#start');
const cancel = document.querySelector('#cancel');
const status = document.querySelector('#status');
const qrWrap = document.querySelector('#qr-wrap');
const canvas = document.querySelector('#qr');
const expiry = document.querySelector('#expiry');

function setStatus(message, error = false) {
  status.textContent = message;
  status.classList.toggle('error', error);
}

async function loadAddresses() {
  try {
    const entries = await invoke('list_lan_addresses');
    addresses.replaceChildren();
    for (const entry of entries) {
      const option = document.createElement('option');
      option.value = entry.address;
      option.textContent = `${entry.name} · ${entry.address}`;
      addresses.append(option);
    }
    start.disabled = entries.length === 0;
    setStatus(entries.length ? 'Ready to generate a private, one-time QR.' : 'No private Wi-Fi or hotspot address found. Connect both devices to the same network.', !entries.length);
  } catch (error) { setStatus(String(error), true); start.disabled = true; }
}

function drawQr(rows) {
  const quiet = 4;
  const size = rows.length + quiet * 2;
  const scale = 5;
  canvas.width = canvas.height = size * scale;
  const context = canvas.getContext('2d');
  context.fillStyle = '#fff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = '#102d25';
  rows.forEach((row, y) => [...row].forEach((bit, x) => {
    if (bit === '1') context.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale);
  }));
}

start.addEventListener('click', async () => {
  start.disabled = true;
  try {
    const offer = await invoke('start_pairing', { address: addresses.value });
    drawQr(offer.rows);
    expiry.textContent = `Expires at ${new Date(offer.expires_at * 1000).toLocaleTimeString()} · ${offer.address}`;
    qrWrap.hidden = false;
    cancel.hidden = false;
    setStatus('Waiting for approval on your phone…');
  } catch (error) { setStatus(String(error), true); start.disabled = false; }
});
cancel.addEventListener('click', async () => {
  try { await invoke('cancel_pairing'); } catch (error) { setStatus(String(error), true); return; }
  qrWrap.hidden = true;
  cancel.hidden = true;
  start.disabled = false;
  setStatus('Disconnected. You can create a fresh invitation.');
});
setInterval(async () => {
  if (cancel.hidden) return;
  try { setStatus(await invoke('pairing_status')); } catch (error) { setStatus(String(error), true); }
}, 1000);
loadAddresses();
