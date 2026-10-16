import { invoke } from '@tauri-apps/api/core';
import './style.css';

const addresses = document.querySelector('#addresses');
const start = document.querySelector('#start');
const cancel = document.querySelector('#cancel');
const status = document.querySelector('#status');
const qrWrap = document.querySelector('#qr-wrap');
const canvas = document.querySelector('#qr');
const expiry = document.querySelector('#expiry');
const wifiTab = document.querySelector('#wifi-tab');
const usbTab = document.querySelector('#usb-tab');
const wifiPanel = document.querySelector('#wifi-panel');
const usbPanel = document.querySelector('#usb-panel');
const wifiHelp = document.querySelector('#wifi-help');
const usbHelp = document.querySelector('#usb-help');
const usbDevices = document.querySelector('#usb-devices');
const usbSummary = document.querySelector('#usb-summary');
const usbCount = document.querySelector('#usb-count');
const usbNotice = document.querySelector('#usb-notice');
let usbChecking = false;
let openingSerial = null;

function selectMethod(method) {
  const usb = method === 'usb';
  wifiTab.classList.toggle('active', !usb);
  usbTab.classList.toggle('active', usb);
  wifiTab.setAttribute('aria-selected', String(!usb));
  usbTab.setAttribute('aria-selected', String(usb));
  wifiPanel.hidden = usb;
  wifiHelp.hidden = usb;
  usbPanel.hidden = !usb;
  usbHelp.hidden = !usb;
  if (usb) refreshUsb();
}

wifiTab.addEventListener('click', () => selectMethod('wifi'));
usbTab.addEventListener('click', () => selectMethod('usb'));

function setUsbNotice(message, error = false) {
  usbNotice.textContent = message;
  usbNotice.classList.toggle('error', error);
}

async function openUsbPhone(serial) {
  if (openingSerial) return;
  openingSerial = serial;
  setUsbNotice('Opening Virlin on your phone…');
  await refreshUsb();
  try {
    await invoke('open_virlin', { serial });
    setUsbNotice('Virlin is open in a mirrored window. Disconnecting USB ends the mirror.');
  } catch (error) {
    setUsbNotice(String(error), true);
  } finally {
    openingSerial = null;
    refreshUsb();
  }
}

async function refreshUsb() {
  if (usbChecking) return;
  usbChecking = true;
  try {
    const devices = (await invoke('list_devices')).filter(device => device.kind === 'USB');
    usbCount.hidden = devices.length === 0;
    usbCount.textContent = String(devices.length);
    usbSummary.textContent = devices.length === 0 ? 'No phone detected' : `${devices.length} phone${devices.length === 1 ? '' : 's'} detected`;
    usbDevices.replaceChildren();
    if (!devices.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.innerHTML = '<span class="phone-symbol">▯</span><strong>Waiting for a USB phone</strong><span>Plug in and unlock your phone. This list updates automatically.</span>';
      usbDevices.append(empty);
    }
    for (const device of devices) {
      const row = document.createElement('div');
      row.className = 'device';
      const details = document.createElement('div');
      details.className = 'device-details';
      const name = document.createElement('strong');
      name.textContent = device.model || 'Android phone';
      const description = document.createElement('span');
      description.textContent = device.state === 'device' ? 'Ready to open Virlin' : device.state === 'unauthorized' ? 'Unlock your phone and approve USB debugging' : `USB connected · ${device.state}`;
      details.append(name, description);
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'primary';
      button.textContent = openingSerial === device.serial ? 'Opening…' : 'Open Virlin';
      button.disabled = device.state !== 'device' || openingSerial !== null;
      button.addEventListener('click', () => openUsbPhone(device.serial));
      row.append(details, button);
      usbDevices.append(row);
    }
  } catch (error) {
    usbSummary.textContent = 'USB detection unavailable';
    setUsbNotice(String(error), true);
  } finally {
    usbChecking = false;
  }
}

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
refreshUsb();
setInterval(refreshUsb, 3000);
