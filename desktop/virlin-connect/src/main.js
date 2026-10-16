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
const workspace = document.querySelector('#workspace');
const workspaceStatus = document.querySelector('#workspace-status');
const projectList = document.querySelector('#project-list');
const refreshProjects = document.querySelector('#refresh-projects');
const createForm = document.querySelector('#create-form');
const createHeading = document.querySelector('#create-heading');
const createTitle = document.querySelector('#create-title');
const createDescription = document.querySelector('#create-description');
let createTarget = null;
let pendingCreate = null;
const bulkForm = document.querySelector('#bulk-form');
const bulkHeading = document.querySelector('#bulk-heading');
const bulkSource = document.querySelector('#bulk-source');
const bulkPreview = document.querySelector('#bulk-preview');
const bulkSubmit = document.querySelector('#bulk-submit');
let bulkTarget = null;
let bulkDraft = null;
let usbChecking = false;
let openingSerial = null;
let method = 'wifi';
let pairingActive = false;
let selectedProjectId = null;
let projectTree = null;
let projectQuery = '';
let projectFilter = 'ALL';
const projectSearch = document.querySelector('#project-search');
const projectTotal = document.querySelector('#project-total');
const directoryControls = document.querySelector('#directory-controls');
const addProject = document.querySelector('#add-project');
const bulkProjects = document.querySelector('#bulk-projects');
const filterChips = [...document.querySelectorAll('.filter-chip')];

projectSearch.addEventListener('input', () => {
  projectQuery = projectSearch.value.trim().toLocaleLowerCase();
  if (projectTree) renderProjects(projectTree);
});
for (const chip of filterChips) chip.addEventListener('click', () => {
  projectFilter = chip.dataset.filter;
  for (const item of filterChips) {
    const selected = item === chip;
    item.classList.toggle('active', selected);
    item.setAttribute('aria-pressed', String(selected));
  }
  if (projectTree) renderProjects(projectTree);
});
document.querySelector('#workspace-disconnect').addEventListener('click', () => cancel.click());
document.querySelector('#workspace-mobile-disconnect').addEventListener('click', () => cancel.click());

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
  workspace.hidden = mirror || !status.textContent.startsWith('Connected');
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
  const workspaceActive = method !== 'usb-mirror' && message.startsWith('Connected');
  workspace.hidden = !workspaceActive;
  document.body.classList.toggle('workspace-active', workspaceActive);
}

function beginCreate(kind, ownerType = null, ownerId = null) {
  bulkForm.hidden = true;
  createTarget = { kind, ownerType, ownerId };
  pendingCreate = null;
  createHeading.textContent = `New ${kind === 'task' && ownerType === 'task' ? 'subtask' : kind}`;
  createForm.hidden = false;
  createTitle.value = '';
  createDescription.value = '';
  createTitle.focus();
  createForm.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
function beginBulk(target, ownerType = null, ownerId = null) {
  createForm.hidden = true;
  bulkTarget = { target, ownerType, ownerId };
  bulkDraft = null;
  bulkSource.value = '';
  bulkPreview.hidden = true;
  bulkSubmit.textContent = 'Preview import';
  bulkHeading.textContent = `Import ${target}`;
  bulkForm.hidden = false;
  bulkSource.focus();
  bulkForm.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
document.querySelector('#bulk-projects').addEventListener('click', () => beginBulk('projects'));
document.querySelector('#bulk-cancel').addEventListener('click', () => { bulkForm.hidden = true; bulkTarget = bulkDraft = null; });
bulkSource.addEventListener('input', () => { bulkDraft = null; bulkPreview.hidden = true; bulkSubmit.textContent = 'Preview import'; });
bulkForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!bulkTarget) return;
  bulkSubmit.disabled = true;
  try {
    if (!bulkDraft) {
      const key = crypto.randomUUID();
      const result = JSON.parse(await invoke('preview_workspace_bulk', { ...bulkTarget, source: bulkSource.value, key }));
      if (result.type !== 'bulk_preview') throw new Error(result.detail || result.code || 'Preview failed');
      bulkDraft = { key, count: result.count };
      bulkPreview.textContent = result.lines.join('\n');
      bulkPreview.hidden = false;
      bulkSubmit.textContent = `Import ${result.count} items on phone`;
      workspaceStatus.textContent = 'Review the outline, then confirm the import.';
    } else {
      const result = JSON.parse(await invoke('apply_workspace_bulk', { key: bulkDraft.key }));
      if (result.type !== 'bulk_created') throw new Error(result.code || 'Import failed');
      bulkForm.hidden = true;
      bulkTarget = bulkDraft = null;
      await loadProjects();
      workspaceStatus.textContent = `Imported ${result.count} items on your phone.`;
    }
  } catch (error) { workspaceStatus.textContent = `Import not confirmed: ${String(error)}. Check your phone before retrying.`; }
  finally { bulkSubmit.disabled = false; }
});
document.querySelector('#add-project').addEventListener('click', () => beginCreate('project'));
document.querySelector('#create-cancel').addEventListener('click', () => { createForm.hidden = true; createTarget = null; pendingCreate = null; });
createForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!createTarget) return;
  const submit = createForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  const fingerprint = JSON.stringify({ ...createTarget, title: createTitle.value, description: createDescription.value });
  if (!pendingCreate || pendingCreate.fingerprint !== fingerprint) pendingCreate = { fingerprint, key: crypto.randomUUID() };
  const key = pendingCreate.key;
  try {
    const result = JSON.parse(await invoke('create_workspace_item', {
      ...createTarget, title: createTitle.value, description: createDescription.value, key,
    }));
    if (result.v !== 1 || result.type !== 'created' || !result.id) throw new Error(result.code || 'Creation failed');
    createForm.hidden = true;
    createTarget = null;
    pendingCreate = null;
    await loadProjects();
    workspaceStatus.textContent = 'Created on your phone.';
  } catch (error) { workspaceStatus.textContent = `Could not confirm creation: ${String(error)}. Check your phone before retrying.`; }
  finally { submit.disabled = false; }
});

function treeRow(title, detail, depth = 0, actions = []) {
  const row = document.createElement('div');
  row.className = `tree-row ${depth ? 'nested' : ''}`;
  row.style.setProperty('--depth', Math.min(depth, 12));
  const content = document.createElement('div');
  const name = document.createElement('strong');
  name.textContent = title;
  const meta = document.createElement('span');
  meta.textContent = detail;
  content.append(name, meta);
  const controls = document.createElement('div');
  controls.className = 'tree-actions';
  for (const action of actions) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = action.label;
    button.addEventListener('click', () => action.mode === 'bulk'
      ? beginBulk(action.target, action.ownerType, action.ownerId)
      : beginCreate(action.kind, action.ownerType, action.ownerId));
    controls.append(button);
  }
  row.append(content, controls);
  return row;
}

function sectionHeading(label, count) {
  const heading = document.createElement('div');
  heading.className = 'project-section-heading';
  const title = document.createElement('h3');
  title.textContent = label;
  const number = document.createElement('span');
  number.textContent = String(count);
  heading.append(title, number);
  return heading;
}

function projectCard(project, tree) {
  const card = document.createElement('button');
  card.className = 'project-card';
  card.type = 'button';
  const icon = document.createElement('span');
  icon.className = 'project-icon';
  icon.setAttribute('aria-hidden', 'true');
  const iconGlyphs = { code: '〈〉', business: '▣', brain: '✿', tools: '⚒', education: '◆', cloud: '☁', book: '▤', design: '◇', target: '◎', rocket: '➤' };
  icon.textContent = project.hasCustomIcon ? project.title.slice(0, 1).toUpperCase()
    : iconGlyphs[project.iconId] || project.title.slice(0, 1).toUpperCase();
  icon.dataset.icon = project.iconId || 'default';
  const content = document.createElement('span');
  content.className = 'project-card-content';
  const title = document.createElement('strong');
  title.textContent = project.title;
  const meta = document.createElement('small');
  const streams = tree.workstreams.filter(item => item.projectId === project.id).length;
  meta.textContent = `${streams} WorkStream${streams === 1 ? '' : 's'}`;
  content.append(title, meta);
  const ring = document.createElement('span');
  ring.className = 'project-progress';
  const percent = Number.isFinite(project.progressPercent) ? Math.max(0, Math.min(100, project.progressPercent)) : null;
  ring.style.setProperty('--progress', percent === null ? '0%' : `${percent}%`);
  ring.textContent = percent === null ? '—' : `${percent}%`;
  ring.setAttribute('aria-label', percent === null ? 'No structured progress' : `${percent}% complete`);
  const arrow = document.createElement('span');
  arrow.className = 'project-arrow';
  arrow.textContent = '›';
  card.append(icon, content, ring, arrow);
  card.addEventListener('click', () => { selectedProjectId = project.id; renderProjects(tree); });
  return card;
}

function renderProjects(tree) {
  projectList.replaceChildren();
  const heading = document.querySelector('#workspace-heading');
  const project = tree.projects.find(item => item.id === selectedProjectId);
  directoryControls.hidden = Boolean(project);
  addProject.hidden = Boolean(project);
  bulkProjects.hidden = Boolean(project);
  projectTotal.hidden = Boolean(project);
  if (!project) {
    selectedProjectId = null;
    heading.textContent = 'Project';
    projectTotal.textContent = `${tree.projects.length} total`;
    const filtered = tree.projects.filter(item => {
      if (projectFilter === 'ALL') return item.title.toLocaleLowerCase().includes(projectQuery);
      const matches = tree.workstreams.some(stream => {
        if (stream.projectId !== item.id) return false;
        if (projectFilter === 'NEED_YOU') return stream.effectiveState === 'CHECK';
        if (projectFilter === 'PROCESSING') return !stream.externalTrackingPaused &&
          (stream.state === 'PROCESSING' || (stream.state === 'CHECK' && stream.hasProcessingStarted));
        if (projectFilter === 'READY') return stream.effectiveState === 'READY' && !stream.externalTrackingPaused;
        if (projectFilter === 'PAUSED') return stream.externalTrackingPaused || stream.effectiveState === 'PAUSED';
        return stream.effectiveState === projectFilter;
      });
      return matches && item.title.toLocaleLowerCase().includes(projectQuery);
    });
    const grid = document.createElement('div');
    grid.className = 'project-grid';
    for (const item of filtered) grid.append(projectCard(item, tree));
    projectList.append(grid);
    if (!filtered.length) {
      const empty = document.createElement('p');
      empty.className = 'project-empty';
      empty.textContent = tree.projects.length === 0
        ? 'No projects on your phone yet. Create one to get started.'
        : 'No projects match this search and filter.';
      projectList.append(empty);
    }
    return;
  }
  heading.textContent = project.title;
  const back = document.createElement('button');
  back.className = 'project-back';
  back.type = 'button';
  back.textContent = '← All projects';
  back.addEventListener('click', () => { selectedProjectId = null; renderProjects(tree); });
  projectList.append(back);
  const actions = document.createElement('div');
  actions.className = 'project-detail-actions';
  for (const action of [
    { label: '+ Workstream', kind: 'workstream', ownerType: 'project', ownerId: project.id },
    { label: '+ Task', kind: 'task', ownerType: 'project', ownerId: project.id },
    { label: 'Bulk workstreams', mode: 'bulk', target: 'workstreams', ownerType: 'project', ownerId: project.id },
    { label: 'Bulk tasks', mode: 'bulk', target: 'tasks', ownerType: 'project', ownerId: project.id },
  ]) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = action.label;
    button.addEventListener('click', () => action.mode === 'bulk'
      ? beginBulk(action.target, action.ownerType, action.ownerId)
      : beginCreate(action.kind, action.ownerType, action.ownerId));
    actions.append(button);
  }
  projectList.append(actions);
  const appendTasks = (owner, ownerId, depth, ancestors = new Set()) => {
    for (const task of tree.tasks.filter(item => item[owner] === ownerId &&
        (owner === 'parentTaskId' || !item.parentTaskId) &&
        (owner !== 'projectId' || !item.workStreamId))) {
      if (ancestors.has(task.id)) continue;
      projectList.append(treeRow(task.title, task.status, depth,
        [{ label: '+ Subtask', kind: 'task', ownerType: 'task', ownerId: task.id },
          { label: 'Bulk', mode: 'bulk', target: 'subtasks', ownerType: 'task', ownerId: task.id }]));
      appendTasks('parentTaskId', task.id, depth + 1, new Set([...ancestors, task.id]));
    }
  };
  const streams = tree.workstreams.filter(item => item.projectId === project.id)
    .sort((a, b) => a.sortOrder - b.sortOrder);
  projectList.append(sectionHeading('Workstreams', streams.length));
  for (const stream of streams) {
    projectList.append(treeRow(stream.title, `Workstream · ${stream.state}`, 0,
      [{ label: '+ Task', kind: 'task', ownerType: 'workstream', ownerId: stream.id },
        { label: 'Bulk', mode: 'bulk', target: 'tasks', ownerType: 'workstream', ownerId: stream.id }]));
    appendTasks('workStreamId', stream.id, 1);
  }
  const standalone = tree.tasks.filter(item => item.projectId === project.id && !item.parentTaskId && !item.workStreamId);
  projectList.append(sectionHeading('Standalone tasks', standalone.length));
  appendTasks('projectId', project.id, 0);
}

async function loadProjects() {
  refreshProjects.disabled = true;
  workspaceStatus.textContent = 'Reading projects from your phone…';
  try {
    const tree = JSON.parse(await invoke('project_tree'));
    if (tree.type === 'error') throw new Error(tree.code || 'Phone rejected project read');
    if (tree.v !== 1 || tree.type !== 'tree' || !Array.isArray(tree.projects) ||
        !Array.isArray(tree.workstreams) || !Array.isArray(tree.tasks)) throw new Error('Unsupported phone response');
    projectTree = tree;
    renderProjects(tree);
    workspaceStatus.textContent = `${tree.projects.length} project${tree.projects.length === 1 ? '' : 's'} · stored on your phone`;
  } catch (error) {
    workspaceStatus.textContent = `Could not load projects: ${String(error)}`;
  } finally { refreshProjects.disabled = false; }
}
refreshProjects.addEventListener('click', loadProjects);

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
  wasConnected = false;
  projectTree = null;
  setStatus('Disconnected. You can create a fresh invitation.');
  loadAddresses();
});
let wasConnected = false;
setInterval(async () => {
  if (cancel.hidden) return;
  try {
    const current = await invoke('pairing_status');
    setStatus(current);
    const connected = current.startsWith('Connected');
    if (connected && !wasConnected) loadProjects();
    wasConnected = connected;
  } catch (error) { setStatus(String(error), true); }
}, 1000);
loadAddresses();
refreshUsb();
setInterval(refreshUsb, 3000);
setInterval(() => { if (method === 'usb-network') loadAddresses(); }, 5000);
