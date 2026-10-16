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
const usbNetworkTab = document.querySelector('#usb-network-tab');
const usbTab = document.querySelector('#usb-tab');
const wifiPanel = document.querySelector('#wifi-panel');
const usbNetworkPanel = document.querySelector('#usb-network-panel');
const usbPanel = document.querySelector('#usb-panel');
const wifiHelp = document.querySelector('#wifi-help');
const usbNetworkHelp = document.querySelector('#usb-network-help');
const usbHelp = document.querySelector('#usb-help');
const pairingTitle = document.querySelector('#pairing-title');
const pairingIntro = document.querySelector('#pairing-intro');
const usbNetworkSummary = document.querySelector('#usb-network-summary');
const usbNetworkDetail = document.querySelector('#usb-network-detail');
const usbDevices = document.querySelector('#usb-devices');
const usbSummary = document.querySelector('#usb-summary');
const usbCount = document.querySelector('#usb-count');
const usbNotice = document.querySelector('#usb-notice');
let usbChecking = false;
let openingSerial = null;
let method = 'wifi';
let pairingActive = false;

function selectMethod(nextMethod) {
  method = nextMethod;
  for (const [tab, name] of [[wifiTab, 'wifi'], [usbNetworkTab, 'usb-network'], [usbTab, 'usb-mirror']]) {
    const active = name === method;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
  }
  const mirror = method === 'usb-mirror';
  wifiPanel.hidden = mirror;
  wifiPanel.setAttribute('aria-labelledby', method === 'usb-network' ? 'usb-network-tab' : 'wifi-tab');
  usbNetworkPanel.hidden = method !== 'usb-network';
  usbNetworkHelp.hidden = method !== 'usb-network';
  wifiHelp.hidden = method !== 'wifi';
  usbPanel.hidden = !mirror;
  usbHelp.hidden = !mirror;
  pairingTitle.textContent = method === 'usb-network' ? 'Secure USB pairing' : 'Secure local pairing';
  pairingIntro.textContent = method === 'usb-network'
    ? 'Choose the detected USB network, then scan the QR in Virlin on your phone.'
    : 'Choose this computer’s network address, then scan the QR in Virlin on your phone.';
  if (mirror) refreshUsb(); else loadAddresses();
}

wifiTab.addEventListener('click', () => selectMethod('wifi'));
usbNetworkTab.addEventListener('click', () => selectMethod('usb-network'));
usbTab.addEventListener('click', () => selectMethod('usb-mirror'));

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
    const usbEntries = entries.filter(entry => entry.kind === 'usb_tether');
    usbNetworkSummary.textContent = usbEntries.length ? 'USB network detected' : 'Waiting for USB tethering';
    usbNetworkDetail.textContent = usbEntries.length
      ? `${usbEntries.map(entry => `${entry.name} · ${entry.address}`).join(', ')}. No Developer options needed.`
      : 'Connect the cable and enable USB tethering on your phone. This list updates automatically.';
    if (pairingActive) return;
    const selectable = method === 'usb-network' ? usbEntries : entries.filter(entry => entry.kind !== 'usb_tether');
    const selected = addresses.value;
    addresses.replaceChildren();
    for (const entry of selectable) {
      const option = document.createElement('option');
      option.value = entry.address;
      option.textContent = `${entry.name} · ${entry.address}`;
      addresses.append(option);
    }
    if (selectable.some(entry => entry.address === selected)) addresses.value = selected;
    start.disabled = selectable.length === 0;
    const ready = selectable.length > 0;
    setStatus(ready ? 'Ready to generate a private, one-time QR.' : method === 'usb-network'
      ? 'Waiting for USB tethering. Turn it on in your phone’s Settings.'
      : 'No private Wi-Fi or hotspot address found. Connect both devices to the same network.', !ready);
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
    pairingActive = true;
    setStatus('Waiting for approval on your phone…');
  } catch (error) { setStatus(String(error), true); start.disabled = false; }
});
cancel.addEventListener('click', async () => {
  try { await invoke('cancel_pairing'); } catch (error) { setStatus(String(error), true); return; }
  qrWrap.hidden = true;
  cancel.hidden = true;
  pairingActive = false;
  setStatus('Disconnected. You can create a fresh invitation.');
  loadAddresses();
});
setInterval(async () => {
  if (cancel.hidden) return;
  try { setStatus(await invoke('pairing_status')); } catch (error) { setStatus(String(error), true); }
}, 1000);
loadAddresses();
refreshUsb();
setInterval(refreshUsb, 3000);
setInterval(() => { if (method === 'usb-network') loadAddresses(); }, 5000);
