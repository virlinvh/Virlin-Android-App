import { invoke } from '@tauri-apps/api/core';
import './style.css';

const devicesEl = document.querySelector('#devices');
const summaryEl = document.querySelector('#summary');
const noticeEl = document.querySelector('#notice');
const refreshButton = document.querySelector('#refresh');
let busy = false;

function notice(message, error = false) {
  noticeEl.textContent = message;
  noticeEl.classList.toggle('error', error);
}

async function refresh() {
  if (busy) return;
  refreshButton.disabled = true;
  try {
    const devices = await invoke('list_devices');
    devicesEl.replaceChildren();
    const phones = devices.filter((device) => device.kind !== 'Emulator');
    summaryEl.textContent = phones.length ? `${phones.length} phone${phones.length === 1 ? '' : 's'} found` : 'No phone found yet';
    if (!phones.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.innerHTML = '<span class="phone-symbol">▯</span><strong>Connect your phone</strong><span>Plug in a USB cable, unlock the phone, and approve USB debugging.</span>';
      devicesEl.append(empty);
    }
    for (const device of phones) {
      const row = document.createElement('div');
      row.className = 'device';
      const details = document.createElement('div');
      details.className = 'device-details';
      const name = document.createElement('strong');
      name.textContent = device.model || 'Android phone';
      const state = document.createElement('span');
      state.textContent = `${device.kind} · ${device.state === 'device' ? 'Ready' : device.state === 'unauthorized' ? 'Approve the prompt on your phone' : device.state}`;
      details.append(name, state);
      const button = document.createElement('button');
      button.className = 'primary';
      button.textContent = 'Open Virlin';
      button.disabled = device.state !== 'device';
      button.addEventListener('click', () => openDevice(device.serial, button));
      row.append(details, button);
      devicesEl.append(row);
    }
    if (devices.some((device) => device.kind === 'Emulator')) {
      const hint = document.createElement('p');
      hint.className = 'hint';
      hint.textContent = 'Android emulators are hidden here so the desktop connector opens your phone, not a test device.';
      devicesEl.append(hint);
    }
  } catch (error) {
    summaryEl.textContent = 'Connection tools unavailable';
    notice(String(error), true);
  } finally {
    refreshButton.disabled = false;
  }
}

async function openDevice(serial, button) {
  busy = true;
  button.disabled = true;
  button.textContent = 'Opening…';
  notice('Opening Virlin on your phone…');
  try {
    await invoke('open_virlin', { serial });
    notice('Virlin is open in the mirrored window. Close that window to end mirroring.');
  } catch (error) {
    notice(String(error), true);
  } finally {
    busy = false;
    button.disabled = false;
    button.textContent = 'Open Virlin';
  }
}

refreshButton.addEventListener('click', refresh);
refresh();
setInterval(() => { if (!busy) refresh(); }, 5000);
