import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import './style.css';
import { renderProjectMap } from './project-map.js';
import { MapHistory } from './map-history.js';
import { renderTaskHierarchy } from './task-page.js';
import { creationContext } from './creation-context.js';
import { workstreamVisual, workstreamIconMarkup } from './workstream-visual.js';
import { shiftNoteRuns, toggleNoteMark, setNoteStyle } from './note-edit.js';
import { parseRichNoteHtml, parsePlainNoteText, insertRichNoteBlocks } from './rich-paste.js';
import { createImageEditor } from './image-editor.js';
import { reorderNoteBlocks, noteDropTarget } from './block-reorder.js';
import { createNoteHistory } from './note-history.js';
import { buildNoteOutline, headingLevel, nextHeadingType } from './note-outline.js';
import { noteSelectionOffsets, restoreNoteSelection } from './note-dom-selection.js';
import { checklistSections, checklistCounts, toggleChecklistBlock,
  parseChecklistTasks, appendChecklistTasks } from './checklist-model.js';
import { classifyLink } from './link-preview.js';
import { mountChecklistCreation } from './checklist-create.js';
import { importResponseClipboard, renderPromptResponse, copyPromptResponse } from './prompt-response.js';

let offlineMode = false;
let offlineCopies = [];
let offlineDeviceId = null;
let offlineCaptureBusy = false;
let pendingOfflineRefresh = false;
function invoke(command, args = {}) {
  if (!offlineMode) return tauriInvoke(command, args);
  const deviceId = offlineDeviceId;
  if (command === 'project_tree') return tauriInvoke('offline_copy_read', { deviceId, kind: 'tree' });
  if (command === 'project_knowledge') return tauriInvoke('offline_copy_read', { deviceId, kind: 'knowledge', projectId: args.projectId });
  if (command === 'knowledge_item') return tauriInvoke('offline_copy_read', { deviceId, kind: 'item', projectId: args.projectId, itemId: args.captureId });
  if (command === 'note_image_chunk' || command === 'voice_chunk') return tauriInvoke('offline_copy_media_chunk', {
    deviceId, kind: command === 'note_image_chunk' ? 'image' : 'audio', projectId: args.projectId,
    itemId: args.captureId, mediaId: args.imagePath || args.clipId, offset: args.offset,
  });
  if (['list_lan_addresses', 'start_pairing', 'cancel_pairing', 'pairing_status', 'offline_copy_list'].includes(command))
    return tauriInvoke(command, args);
  return Promise.reject(new Error('Read-only offline copy. Reconnect this phone to make changes.'));
}

const addresses = document.querySelector('#addresses');
const start = document.querySelector('#start');
const cancel = document.querySelector('#cancel');
const status = document.querySelector('#status');
const qrWrap = document.querySelector('#qr-wrap');
const canvas = document.querySelector('#qr');
const expiry = document.querySelector('#expiry');
const qrPlaceholder = document.querySelector('#qr-placeholder');
const pairingReadyLabel = document.querySelector('#pairing-ready-label');
const reconnectMessage = document.querySelector('#reconnect-message');
const wifiTab = document.querySelector('#wifi-tab');
const usbNetworkTab = document.querySelector('#usb-network-tab');
const wifiPanel = document.querySelector('#wifi-panel');
const usbNetworkPanel = document.querySelector('#usb-network-panel');
const wifiHelp = document.querySelector('#wifi-help');
const usbNetworkHelp = document.querySelector('#usb-network-help');
const pairingTitle = document.querySelector('#pairing-title');
const pairingIntro = document.querySelector('#pairing-intro');
const usbNetworkSummary = document.querySelector('#usb-network-summary');
const usbNetworkDetail = document.querySelector('#usb-network-detail');
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
let method = 'wifi';
let pairingActive = false;
let selectedProjectId = null;
let projectTree = null;
let activeMapView = null;
let savedMapUi = null;
let mapHistoryBusy = false;
const mapHistories = new Map();
function historyForMap(deviceId, projectId) {
  const key = `${deviceId || 'unknown'}:${projectId}`;
  if (!mapHistories.has(key)) mapHistories.set(key, new MapHistory());
  return mapHistories.get(key);
}
let projectQuery = '';
let projectFilter = 'ALL';
let projectDetailTab = 'Overview';
let selectedTaskLocation = null;
const knowledgeCache = new Map();
const knowledgeComposer = document.querySelector('#knowledge-composer');
const knowledgeTypePicker = document.querySelector('#knowledge-type-picker');
const knowledgeForm = document.querySelector('#knowledge-form');
const knowledgeCreateSurface = document.createElement('div');
knowledgeCreateSurface.className = 'knowledge-create-surface';
knowledgeCreateSurface.hidden = true;
knowledgeForm.insertBefore(knowledgeCreateSurface, document.querySelector('#knowledge-hint'));
function resetKnowledgeCreationSurface() {
  knowledgeCreateSurface.replaceChildren();
  knowledgeCreateSurface.hidden = true;
  knowledgeComposer.classList.remove('is-dedicated-create', 'is-checklist-create', 'is-link-create');
}
const knowledgeFormHeading = document.querySelector('#knowledge-form-heading');
const knowledgeKind = document.querySelector('#knowledge-kind');
const knowledgeTitle = document.querySelector('#knowledge-title');
const knowledgeUrl = document.querySelector('#knowledge-url');
const knowledgeUrlField = document.querySelector('#knowledge-url-field');
const knowledgeContent = document.querySelector('#knowledge-content');
const knowledgeCode = document.querySelector('#knowledge-code');
const knowledgeCodeField = document.querySelector('#knowledge-code-field');
const knowledgePromptTabs = document.querySelector('#knowledge-prompt-tabs');
const knowledgePromptToolbar = document.querySelector('#knowledge-prompt-toolbar');
const knowledgePromptCopy = document.querySelector('#knowledge-prompt-copy');
const knowledgePromptEdit = document.querySelector('#knowledge-prompt-edit');
let activePromptTab = 'prompt';
function activePromptArea() { return { prompt: knowledgeContent, code: knowledgeCode, response: knowledgeResponse }[activePromptTab]; }
function setPromptEditable(editable) {
  for (const area of [knowledgeContent, knowledgeCode, knowledgeResponse]) area.readOnly = !editable;
  knowledgePromptEdit.textContent = editable ? 'Done editing' : 'Edit section';
  updateResponsePreview();
}
function updateResponsePreview() {
  for (const [tab, area, host] of [['prompt', knowledgeContent, knowledgePromptPreview],
    ['code', knowledgeCode, knowledgeCodePreview], ['response', knowledgeResponse, knowledgeResponsePreview]]) {
    const preview = activePromptTab === tab && area.readOnly;
    area.hidden = preview;
    host.hidden = !preview;
    if (preview) renderPromptResponse(host, area.value, { codeOnly: tab === 'code',
      language: knowledgeLanguage.value, feedback: message => { knowledgeFeedback.textContent = message; } });
  }
}
function selectPromptTab(tab) {
  activePromptTab = tab;
  for (const button of knowledgePromptTabs.querySelectorAll('[data-prompt-tab]')) {
    button.setAttribute('aria-selected', String(button.dataset.promptTab === tab));
    button.tabIndex = button.dataset.promptTab === tab ? 0 : -1;
  }
  knowledgeContent.closest('label').hidden = tab !== 'prompt';
  knowledgeCodeField.hidden = tab !== 'code';
  knowledgeLanguage.closest('label').hidden = tab !== 'code';
  knowledgeResponseField.hidden = tab !== 'response';
  updateResponsePreview();
  sizePromptArea({ prompt: knowledgeContent, code: knowledgeCode, response: knowledgeResponse }[tab]);
}
knowledgePromptTabs.addEventListener('click', event => {
  const tab = event.target.closest('[data-prompt-tab]')?.dataset.promptTab;
  if (tab) selectPromptTab(tab);
});
knowledgePromptCopy.addEventListener('click', async () => {
  try {
    const preview = { prompt: knowledgePromptPreview, code: knowledgeCodePreview, response: knowledgeResponsePreview }[activePromptTab];
    if (!preview.hidden)
      await copyPromptResponse(activePromptArea().value, preview);
    else await navigator.clipboard.writeText(activePromptArea().value);
    knowledgeFeedback.textContent = `${activePromptTab[0].toUpperCase()}${activePromptTab.slice(1)} copied.`;
  } catch { knowledgeFeedback.textContent = 'Clipboard access was denied. Select the text to copy it.'; }
});
knowledgePromptEdit.addEventListener('click', () => {
  setPromptEditable(activePromptArea().readOnly);
  if (!activePromptArea().readOnly) activePromptArea().focus();
});
function sizePromptArea(area) {
  if (!area || !knowledgeComposer.classList.contains('is-prompt-page')) return;
  area.style.height = 'auto';
  area.style.height = `${Math.max(440, area.scrollHeight)}px`;
}
const knowledgePromptFields = document.querySelector('#knowledge-prompt-fields');
const knowledgeDescription = document.querySelector('#knowledge-description');
const knowledgeTags = document.querySelector('#knowledge-tags');
const knowledgeMode = document.querySelector('#knowledge-mode');
const knowledgeLanguage = document.querySelector('#knowledge-language');
const knowledgeResponseField = document.querySelector('#knowledge-response-field');
const knowledgeResponse = document.querySelector('#knowledge-response');
const knowledgeResponsePreview = document.createElement('div');
knowledgeResponsePreview.hidden = true;
knowledgeResponsePreview.setAttribute('aria-label', 'Formatted response');
knowledgeResponse.after(knowledgeResponsePreview);
const knowledgePromptPreview = document.createElement('div');
knowledgePromptPreview.hidden = true; knowledgeContent.after(knowledgePromptPreview);
const knowledgeCodePreview = document.createElement('div');
knowledgeCodePreview.hidden = true; knowledgeCode.after(knowledgeCodePreview);
knowledgeResponse.addEventListener('paste', event => {
  if (knowledgeResponse.readOnly || !event.clipboardData) return;
  event.preventDefault();
  try {
    const text = importResponseClipboard(event.clipboardData.getData('text/html'), event.clipboardData.getData('text/plain'));
    const nextLength = knowledgeResponse.value.length - (knowledgeResponse.selectionEnd - knowledgeResponse.selectionStart) + text.length;
    if (nextLength > knowledgeResponse.maxLength) {
      knowledgeFeedback.textContent = 'Response exceeds the 32,768-character limit. Nothing was truncated or pasted.';
      return;
    }
    knowledgeResponse.setRangeText(text, knowledgeResponse.selectionStart, knowledgeResponse.selectionEnd, 'end');
    knowledgeResponse.dispatchEvent(new Event('input', { bubbles: true }));
    setPromptEditable(false);
    knowledgeFeedback.textContent = 'Response pasted with formatting. Choose Edit section to edit the source.';
  } catch {
    knowledgeFeedback.textContent = 'This formatted paste could not be imported. Your existing response is unchanged.';
  }
});
for (const area of [knowledgeContent, knowledgeCode, knowledgeResponse])
  area.addEventListener('input', () => sizePromptArea(area));
const knowledgeContentLabel = document.querySelector('#knowledge-content-label');
const knowledgeHint = document.querySelector('#knowledge-hint');
const knowledgeFeedback = document.querySelector('#knowledge-feedback');
const knowledgeSubmit = document.querySelector('#knowledge-submit');
const knowledgeViewer = document.querySelector('#knowledge-viewer');
const knowledgeViewerTitle = document.querySelector('#knowledge-viewer-title');
const knowledgeViewerDocumentTitle = document.querySelector('#knowledge-viewer-document-title');
const knowledgeViewerKind = document.querySelector('#knowledge-viewer-kind');
const knowledgeViewerSummary = document.querySelector('#knowledge-viewer-summary');
const knowledgeViewerContent = document.querySelector('#knowledge-viewer-content');
const knowledgeViewerExtra = document.querySelector('#knowledge-viewer-extra');
const knowledgeViewerFeedback = document.querySelector('#knowledge-viewer-feedback');
const knowledgeCopy = document.querySelector('#knowledge-copy');
const knowledgeCopyFormatted = document.querySelector('#knowledge-copy-formatted');
const knowledgeEdit = document.querySelector('#knowledge-edit');
const knowledgeEditor = document.querySelector('#knowledge-editor');
const knowledgeEditorTitle = document.querySelector('#knowledge-editor-title');
const knowledgeEditorBlocks = document.querySelector('#knowledge-editor-blocks');
const knowledgeEditorFeedback = document.querySelector('#knowledge-editor-feedback');
const knowledgeEditorSave = document.querySelector('#knowledge-editor-save');
const knowledgeEditorMode = document.querySelector('#knowledge-editor-mode');
let knowledgeCapabilities = null;
let knowledgeVisibleItem = null;
let activeChecklistDialog = null;
function disposeChecklistDialog() {
  const dialog = activeChecklistDialog;
  activeChecklistDialog = null;
  if (dialog?.open) dialog.close();
  dialog?.remove();
}
let knowledgeEditing = null;
let activeNoteBlockId = null;
let rememberedNoteSelection = null;
let knowledgeEditMode = false;
let noteHeadingFoldMode = false;
let noteHistory = null;
let noteChange = null;
let draggedNoteBlockId = null;
let knowledgeEditingPrompt = null;
let knowledgeEditingLink = null;
let knowledgeLinkDraft = null;
let voiceLoadGeneration = 0;
let voicePlaybackUrl = null;
const noteImageUrls = new Map();
const noteImageEditor = createImageEditor(document.querySelector('#knowledge-image-editor'), async (blockId, file, report) => {
  const edit = knowledgeEditing;
  const block = edit?.blocks.find(item => item.id === blockId && item.type === 'IMAGE');
  if (!edit || !block || knowledgeBusy) throw new Error('The note is no longer available for editing');
  knowledgeBusy = true;
  try {
    const imagePath = await uploadNoteImage(edit.projectId, file, progress => report(`Uploading edited image… ${progress}%`));
    noteImageUrls.set(imagePath, URL.createObjectURL(file));
    block.imagePath = imagePath;
    edit.dirty = true;
    knowledgeEditMode = true;
    activeNoteBlockId = blockId;
    renderKnowledgeEditor();
    knowledgeEditorFeedback.textContent = 'Edited image ready. Save the note to confirm it on your phone.';
  } finally { knowledgeBusy = false; updateKnowledgeDocumentStatus(); }
});
let knowledgeProjectId = null;
let knowledgeTaskId = null;
let knowledgeTaskReturn = null;
let knowledgePending = null;
let knowledgeBusy = false;
let knowledgeTrigger = null;
let knowledgeFilter = 'All';
let knowledgeQuery = '';
let knowledgeWorkstream = '';
let knowledgeRecentFirst = true;
const projectSearch = document.querySelector('#project-search');
const projectTotal = document.querySelector('#project-total');
const directoryControls = document.querySelector('#directory-controls');
const contextAdd = document.querySelector('#context-add');
const offlineHomeCard = document.querySelector('#offline-home-card');
const offlineOpen = document.querySelector('#offline-open');
const offlineReconnect = document.querySelector('#offline-reconnect');
const bulkProjects = document.querySelector('#bulk-projects');
const filterChips = [...document.querySelectorAll('.filter-chip')];
const projectBreadcrumb = document.querySelector('#project-breadcrumb');
const projectBreadcrumbName = document.querySelector('#project-breadcrumb-name');
const projectSubtitle = document.querySelector('#project-subtitle');
const composer = document.querySelector('#workspace-composer');
const composerHeading = document.querySelector('#composer-heading');
const composerContext = document.querySelector('#composer-context');
const composerFeedback = document.querySelector('#composer-feedback');
const composerKinds = document.querySelector('#composer-kinds');
const composerWorkstream = document.querySelector('#composer-workstream');
const composerStandalone = document.querySelector('#composer-standalone');
const composerCreateTab = document.querySelector('#composer-create-tab');
const composerBulkTab = document.querySelector('#composer-bulk-tab');
let composerTrigger = null;
let composerBusy = false;

document.querySelector('#project-back').addEventListener('click', async () => {
  if (!await allowLeavingKnowledgeEditor()) return;
  selectedProjectId = null;
  projectDetailTab = 'Overview';
  selectedTaskLocation = null;
  if (projectTree) renderProjects(projectTree);
});
document.querySelector('#sidebar-projects').addEventListener('click', async () => {
  if (!await allowLeavingKnowledgeEditor()) return;
  selectedProjectId = null;
  projectDetailTab = 'Overview';
  selectedTaskLocation = null;
  if (projectTree) renderProjects(projectTree);
});
contextAdd.addEventListener('click', () => {
  if (!selectedProjectId) { captureOfflineCopy(false); return; }
  if (offlineMode) return;
  const context = creationContext(selectedProjectId, projectDetailTab, selectedTaskLocation);
  if (context) beginCreate(context.kind, context.ownerType, context.ownerId, context.showProjectKinds);
});

function renderOfflineHome() {
  const home = !selectedProjectId;
  const copy = offlineCopies.find(item => item.deviceId === (offlineMode ? offlineDeviceId : projectTree?.deviceId));
  // Creation belongs to the top-right action. Keep this card only for offline access.
  offlineHomeCard.hidden = !home || !offlineMode || !copy;
  document.querySelector('#offline-home-title').textContent = copy ? `${copy.deviceName} · Offline copy` : 'Offline copy';
  document.querySelector('#offline-home-description').textContent =
    'Browse projects and knowledge in the familiar Virlin pages. Editing and phone actions are unavailable until you reconnect.';
  document.querySelector('#offline-home-stamp').textContent = copy
    ? `Last copied ${new Date(copy.capturedAt * 1000).toLocaleString()} · ${copy.projects} projects` : '';
  offlineOpen.hidden = !offlineMode || !copy || !home;
  offlineReconnect.hidden = !offlineMode || !home;
  document.body.classList.toggle('offline-readonly', offlineMode);
  document.querySelector('.connection-card strong').textContent = offlineMode ? 'Offline copy' : 'Phone connected';
  document.querySelector('.connection-card small').textContent = offlineMode ? 'Read-only on this computer' : 'Work saves on your phone';
  document.querySelector('#workspace-disconnect').textContent = offlineMode ? 'Show pairing QR' : 'Disconnect phone';
  document.querySelector('#workspace-mobile-disconnect').textContent = offlineMode ? '● Offline copy · Reconnect' : '● Phone connected · Disconnect';
  applyReadOnlyControls();
}
function applyReadOnlyControls() {
  const writeLabel = /^(?:\+|add\b|create\b|bulk\b|edit\b|save\b|rename\b|duplicate\b|delete\b|remove\b|complete\b|cancel task\b|start focus\b|pause\b|resume\b|move\b|reorder\b)/i;
  for (const button of workspace.querySelectorAll('button')) {
    if (offlineMode && writeLabel.test(button.textContent.trim())) {
      if (!button.disabled) { button.disabled = true; button.dataset.offlineDisabled = 'true'; }
    } else if (!offlineMode && button.dataset.offlineDisabled === 'true') {
      button.disabled = false;
      delete button.dataset.offlineDisabled;
    }
  }
}
new MutationObserver(() => { if (offlineMode) applyReadOnlyControls(); })
  .observe(workspace, { childList: true, subtree: true });
async function refreshOfflineCopies() {
  offlineCopies = await tauriInvoke('offline_copy_list');
  if (!offlineCopies.some(item => item.deviceId === offlineDeviceId)) offlineDeviceId = offlineCopies[0]?.deviceId || null;
  renderOfflineHome();
}
async function captureOfflineCopy(automatic) {
  if (offlineMode || offlineCaptureBusy) return;
  offlineCaptureBusy = true;
  contextAdd.disabled = true;
  workspaceStatus.textContent = automatic ? 'Refreshing the offline copy from your phone…' : 'Creating a read-only copy of available project data…';
  try {
    const summary = await tauriInvoke('offline_copy_capture');
    pendingOfflineRefresh = false;
    offlineDeviceId = summary.deviceId;
    await refreshOfflineCopies();
    workspaceStatus.textContent = `Offline copy saved on this computer · ${summary.projects} projects · read-only when disconnected.`;
  } catch (error) {
    workspaceStatus.textContent = `Offline copy not replaced: ${String(error)}. Any previous copy remains available.`;
  } finally { offlineCaptureBusy = false; contextAdd.disabled = false; renderOfflineHome(); }
}
async function openOfflineCopy() {
  if (!offlineDeviceId) return;
  offlineMode = true;
  knowledgeCapabilities = { knowledgeAudioPlayback: true, knowledgeImageTransfer: true,
    knowledgeNoteEdit: false, knowledgePromptEdit: false, knowledgeLinkEdit: false };
  selectedProjectId = null;
  selectedTaskLocation = null;
  projectDetailTab = 'Overview';
  setStatus('Offline copy · Read-only');
  await loadProjects();
  renderOfflineHome();
}
function showPairingFromOffline() {
  offlineMode = false;
  projectTree = null;
  selectedProjectId = null;
  setStatus('Disconnected. Show a new pairing QR to reconnect.');
  loadAddresses().then(() => { if (!start.disabled) start.click(); });
}
offlineOpen.addEventListener('click', async () => {
  if (!offlineMode) await openOfflineCopy();
  const first = projectTree?.projects?.[0];
  if (first) { selectedProjectId = first.id; renderProjects(projectTree); window.scrollTo(0, 0); }
});
offlineReconnect.addEventListener('click', showPairingFromOffline);

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
function requestDisconnect() {
  if (offlineMode) { showPairingFromOffline(); return; }
  if (knowledgeEditing?.dirty || knowledgeBusy || composerBusy) {
    workspaceStatus.textContent = 'Finish or save your current change on the phone before disconnecting. Offline editing is not available yet.';
    return;
  }
  cancel.click();
}
document.querySelector('#workspace-disconnect').addEventListener('click', requestDisconnect);
document.querySelector('#workspace-mobile-disconnect').addEventListener('click', requestDisconnect);

function selectMethod(nextMethod) {
  method = nextMethod;
  for (const [tab, name] of [[wifiTab, 'wifi'], [usbNetworkTab, 'usb-network']]) {
    const active = name === method;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
  }
  wifiPanel.hidden = false;
  wifiPanel.setAttribute('aria-labelledby', method === 'usb-network' ? 'usb-network-tab' : 'wifi-tab');
  usbNetworkPanel.hidden = method !== 'usb-network';
  usbNetworkHelp.hidden = method !== 'usb-network';
  wifiHelp.hidden = method !== 'wifi';
  workspace.hidden = !status.textContent.startsWith('Connected') && !offlineMode;
  pairingTitle.textContent = 'Pairing code';
  pairingIntro.textContent = 'Virlin → Apps → Virlin Link';
  loadAddresses();
}

wifiTab.addEventListener('click', () => selectMethod('wifi'));
usbNetworkTab.addEventListener('click', () => selectMethod('usb-network'));

function setStatus(message, error = false) {
  status.textContent = message;
  status.classList.toggle('error', error);
  pairingReadyLabel.textContent = error ? 'Connection needed' : message.startsWith('Connected') ? 'Connected' : pairingActive ? 'Waiting for approval' : 'Ready to pair';
  const workspaceActive = message.startsWith('Connected') || offlineMode;
  workspace.hidden = !workspaceActive;
  document.body.classList.toggle('workspace-active', workspaceActive);
}

function composerOwnerName(ownerType, ownerId) {
  const lists = { project: projectTree?.projects, workstream: projectTree?.workstreams, task: projectTree?.tasks };
  return lists[ownerType]?.find(item => item.id === ownerId)?.title || 'Your phone';
}

function composerItemName() {
  if (createTarget?.kind === 'project') return 'project';
  if (createTarget?.kind === 'workstream') return 'workstream';
  if (createTarget?.ownerType === 'project') return 'standalone task';
  return createTarget?.ownerType === 'task' ? 'subtask' : 'task';
}

function updateComposer() {
  const item = composerItemName();
  const bulk = !bulkForm.hidden;
  const plural = bulkTarget?.target || `${item}s`;
  composerContext.textContent = createTarget?.ownerType
    ? `${composerOwnerName(createTarget.ownerType, createTarget.ownerId)} · ${createTarget.ownerType}`
    : 'Projects';
  composerHeading.textContent = bulk ? `Import ${plural}` : `New ${item}`;
  createHeading.textContent = `New ${item}`;
  bulkHeading.textContent = `Import ${plural}`;
  composerWorkstream.setAttribute('aria-pressed', String(createTarget?.kind === 'workstream'));
  composerStandalone.setAttribute('aria-pressed', String(createTarget?.kind === 'task'));
  composerCreateTab.setAttribute('aria-selected', String(!bulk));
  composerBulkTab.setAttribute('aria-selected', String(bulk));
}

function setComposerMode(bulk) {
  createForm.hidden = bulk;
  bulkForm.hidden = !bulk;
  composerFeedback.textContent = '';
  updateComposer();
  (bulk ? bulkSource : createTitle).focus();
}

function openComposer(bulk, showProjectKinds = false) {
  composerTrigger = document.activeElement;
  composerKinds.hidden = !showProjectKinds;
  composer.hidden = false;
  document.body.classList.add('composer-open');
  setComposerMode(bulk);
}

function closeComposer(force = false) {
  if (composerBusy && !force) return;
  if (!force && [createTitle.value, createDescription.value, bulkSource.value].some(value => value.trim()) &&
      !window.confirm('Discard this draft?')) return;
  composer.hidden = true;
  document.body.classList.remove('composer-open');
  createTarget = bulkTarget = bulkDraft = pendingCreate = null;
  composerFeedback.textContent = '';
  if (composerTrigger?.isConnected) composerTrigger.focus();
  composerTrigger = null;
}

function resetBulkDraft() {
  bulkDraft = null;
  bulkPreview.hidden = true;
  bulkSubmit.textContent = 'Preview import';
}

function setComposerKind(kind) {
  if (composerBusy || createTarget?.ownerType !== 'project') return;
  createTarget = { kind, ownerType: 'project', ownerId: createTarget.ownerId };
  bulkTarget = { target: kind === 'workstream' ? 'workstreams' : 'tasks', ownerType: 'project', ownerId: createTarget.ownerId };
  pendingCreate = null;
  resetBulkDraft();
  updateComposer();
}

function beginCreate(kind, ownerType = null, ownerId = null, showProjectKinds = false) {
  createTarget = { kind, ownerType, ownerId };
  bulkTarget = { target: kind === 'project' ? 'projects' : kind === 'workstream' ? 'workstreams'
    : ownerType === 'task' ? 'subtasks' : 'tasks', ownerType, ownerId };
  pendingCreate = null;
  createTitle.value = '';
  createDescription.value = '';
  bulkSource.value = '';
  resetBulkDraft();
  openComposer(false, showProjectKinds);
}

function beginBulk(target, ownerType = null, ownerId = null) {
  bulkTarget = { target, ownerType, ownerId };
  createTarget = { kind: target === 'projects' ? 'project' : target === 'workstreams' ? 'workstream' : 'task', ownerType, ownerId };
  createTitle.value = '';
  createDescription.value = '';
  bulkSource.value = '';
  pendingCreate = null;
  resetBulkDraft();
  openComposer(true, ownerType === 'project' && ['workstreams', 'tasks'].includes(target));
}

composerWorkstream.addEventListener('click', () => setComposerKind('workstream'));
composerStandalone.addEventListener('click', () => setComposerKind('task'));
composerCreateTab.addEventListener('click', () => { if (!composerBusy) setComposerMode(false); });
composerBulkTab.addEventListener('click', () => { if (!composerBusy) setComposerMode(true); });
document.querySelector('#composer-close').addEventListener('click', () => closeComposer());
document.querySelector('#composer-backdrop').addEventListener('click', () => closeComposer());
document.addEventListener('keydown', event => {
  if (composer.hidden) return;
  if (event.key === 'Escape') { event.preventDefault(); closeComposer(); }
  if (event.key !== 'Tab') return;
  const focusable = [...composer.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled)')]
    .filter(item => !item.closest('[hidden]'));
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
});
document.querySelector('#bulk-projects').addEventListener('click', () => beginBulk('projects'));
document.querySelector('#bulk-cancel').addEventListener('click', () => closeComposer());
bulkSource.addEventListener('input', resetBulkDraft);
bulkForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!bulkTarget) return;
  bulkSubmit.disabled = true;
  composerBusy = true;
  composerFeedback.textContent = bulkDraft ? 'Importing on your phone…' : 'Checking the outline on your phone…';
  try {
    if (!bulkDraft) {
      const key = crypto.randomUUID();
      const result = JSON.parse(await invoke('preview_workspace_bulk', { ...bulkTarget, source: bulkSource.value, key }));
      if (result.type !== 'bulk_preview') throw new Error(result.detail || result.code || 'Preview failed');
      bulkDraft = { key, count: result.count };
      bulkPreview.textContent = result.lines.join('\n');
      bulkPreview.hidden = false;
      bulkSubmit.textContent = `Import ${result.count} items on phone`;
      composerFeedback.textContent = 'Review the outline, then confirm the import.';
    } else {
      const result = JSON.parse(await invoke('apply_workspace_bulk', { key: bulkDraft.key }));
      if (result.type !== 'bulk_created') throw new Error(result.detail || result.code || 'Import failed');
      closeComposer(true);
      await loadProjects();
      workspaceStatus.textContent = `Imported ${result.count} items on your phone.`;
    }
  } catch (error) { composerFeedback.textContent = `Import not confirmed: ${String(error)}. Check your phone before retrying.`; }
  finally { bulkSubmit.disabled = false; composerBusy = false; }
});
document.querySelector('#create-cancel').addEventListener('click', () => closeComposer());
createForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!createTarget) return;
  const submit = createForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  composerBusy = true;
  composerFeedback.textContent = 'Creating on your phone…';
  const fingerprint = JSON.stringify({ ...createTarget, title: createTitle.value, description: createDescription.value });
  if (!pendingCreate || pendingCreate.fingerprint !== fingerprint) pendingCreate = { fingerprint, key: crypto.randomUUID() };
  const key = pendingCreate.key;
  try {
    const result = JSON.parse(await invoke('create_workspace_item', {
      ...createTarget, title: createTitle.value, description: createDescription.value, key,
    }));
    if (result.v !== 1 || result.type !== 'created' || !result.id) throw new Error(result.code || 'Creation failed');
    closeComposer(true);
    await loadProjects();
    workspaceStatus.textContent = 'Created on your phone.';
  } catch (error) { composerFeedback.textContent = `Could not confirm creation: ${String(error)}. Check your phone before retrying.`; }
  finally { submit.disabled = false; composerBusy = false; }
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
  card.addEventListener('click', () => {
    selectedProjectId = project.id;
    selectedTaskLocation = null;
    renderProjects(tree);
    window.scrollTo(0, 0);
  });
  return card;
}

function openTaskLocation(type, id, title, path = []) {
  projectDetailTab = 'Overview';
  selectedTaskLocation = { type, id, title, path, filter: 'All' };
  if (projectTree) renderProjects(projectTree);
  window.scrollTo(0, 0);
}

function openProjectKnowledge(projectId, taskId = null, returnScope = null) {
  closeKnowledgePage(false);
  knowledgeTaskId = taskId;
  knowledgeTaskReturn = returnScope ? { ...returnScope, path: [...returnScope.path] } : null;
  selectedTaskLocation = null;
  projectDetailTab = 'Knowledge';
  if (projectTree) renderProjects(projectTree);
  loadKnowledge(projectId);
  loadKnowledgeCapabilities();
  window.scrollTo(0, 0);
}

async function loadKnowledgeCapabilities() {
  if (offlineMode) {
    knowledgeCapabilities = { knowledgeAudioPlayback: true, knowledgeImageTransfer: true,
      knowledgeNoteEdit: false, knowledgePromptEdit: false, knowledgeLinkEdit: false };
    return;
  }
  try {
    const result = JSON.parse(await invoke('knowledge_capabilities'));
    knowledgeCapabilities = result.v === 1 && result.type === 'capabilities' &&
      Array.isArray(result.knowledgeCreate) ? result : { unsupported: true };
  } catch { knowledgeCapabilities = { unsupported: true }; }
  if (projectTree && projectDetailTab === 'Knowledge') renderProjects(projectTree);
  if (!knowledgeBusy && !activeChecklistDialog && knowledgeVisibleItem && knowledgeViewer.classList.contains('is-checklist'))
    renderChecklistViewer(knowledgeVisibleItem.projectId, knowledgeVisibleItem);
}

async function updatePhoneTask(operation, taskId, title = null, preference = null) {
  const key = crypto.randomUUID();
  workspaceStatus.textContent = 'Updating task on your phone…';
  try {
    const result = JSON.parse(await invoke('task_action', { action: operation, taskId, title, preference, key }));
    if (result.v !== 1 || result.type !== 'task_updated' || result.action !== operation)
      throw new Error(result.code || 'Phone did not confirm the task change');
    await loadProjects();
    workspaceStatus.textContent = 'Task updated on your phone.';
  } catch (error) {
    workspaceStatus.textContent = `Task change not confirmed: ${String(error)}. Check your phone before retrying.`;
  }
}

async function reorderPhoneTask(taskId, afterId, expectedSiblingIds) {
  workspaceStatus.textContent = 'Reordering on your phone…';
  try {
    const result = JSON.parse(await invoke('reorder_task', {
      taskId, afterId, expectedSiblingIds, key: crypto.randomUUID(),
    }));
    if (result.v !== 1 || result.type !== 'task_reordered' || result.id !== taskId)
      throw new Error(result.code || 'Phone did not confirm the order');
    await loadProjects();
    workspaceStatus.textContent = 'Task order saved on your phone.';
  } catch (error) {
    workspaceStatus.textContent = `Task order not confirmed: ${String(error)}. Refresh before retrying.`;
  }
}

async function previewPhoneFocus(taskId) {
  const result = JSON.parse(await invoke('focus_preview', { taskId }));
  if (result.v !== 1 || result.type !== 'focus_target' || !result.workItemId)
    throw new Error(result.code || 'Cannot focus this task');
  return result;
}

async function startPhoneFocus(taskId, preview) {
  const result = JSON.parse(await invoke('focus_start', { taskId,
    expectedWorkItemId: preview.workItemId,
    expectedDisplacedStreamId: preview.displacedStreamId || null,
    key: crypto.randomUUID(),
  }));
  if (result.v !== 1 || result.type !== 'focused' || result.workItemId !== preview.workItemId)
    throw new Error(result.code || 'Focus was not confirmed');
  await loadProjects();
  workspaceStatus.textContent = `Focusing ${preview.workItemTitle} on your phone.`;
}

function projectAction(label, handler, className = '') {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.textContent = label;
  button.addEventListener('click', handler);
  return button;
}

function taskRoots(tree, owner, ownerId) {
  return tree.tasks.filter(task => task[owner] === ownerId && !task.parentTaskId)
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
}

function taskBranch(tree, tasks, depth = 0, ancestors = new Set()) {
  const fragment = document.createDocumentFragment();
  for (const task of tasks) {
    if (ancestors.has(task.id)) continue;
    fragment.append(treeRow(task.title, task.status.replaceAll('_', ' '), depth,
      [{ label: '+ Subtask', kind: 'task', ownerType: 'task', ownerId: task.id },
        { label: 'Bulk', mode: 'bulk', target: 'subtasks', ownerType: 'task', ownerId: task.id }]));
    const children = tree.tasks.filter(item => item.parentTaskId === task.id)
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    fragment.append(taskBranch(tree, children, depth + 1, new Set([...ancestors, task.id])));
  }
  return fragment;
}

function detailSection(title, count, actions = []) {
  const section = document.createElement('section');
  section.className = 'project-detail-section';
  const head = document.createElement('div');
  head.className = 'project-detail-section-head';
  const heading = document.createElement('h3');
  heading.textContent = title;
  if (count !== null) {
    const number = document.createElement('span');
    number.className = 'detail-count';
    number.textContent = String(count);
    heading.append(' ', number);
  }
  const controls = document.createElement('div');
  controls.className = 'project-detail-section-actions';
  controls.append(...actions);
  head.append(heading, controls);
  section.append(head);
  return section;
}

async function loadKnowledge(projectId) {
  try {
    const response = JSON.parse(await invoke('project_knowledge', { projectId }));
    if (response.type === 'error') throw new Error(response.code || 'Phone rejected knowledge read');
    if (response.v !== 1 || response.type !== 'knowledge' || response.projectId !== projectId || !Array.isArray(response.items))
      throw new Error('Unsupported phone response');
    knowledgeCache.set(projectId, { items: response.items });
  } catch (error) {
    knowledgeCache.set(projectId, { error: String(error) });
  }
  if (selectedProjectId === projectId && projectDetailTab === 'Knowledge' && projectTree) renderProjects(projectTree);
}

function updateKnowledgeKind() {
  const kind = knowledgeKind.value;
  knowledgeComposer.classList.toggle('is-prompt-page', kind === 'prompt');
  knowledgePromptToolbar.hidden = kind !== 'prompt';
  knowledgePromptTabs.hidden = kind !== 'prompt';
  knowledgeUrlField.hidden = kind !== 'link';
  knowledgePromptFields.hidden = kind !== 'prompt';
  knowledgeResponseField.hidden = kind !== 'prompt' || activePromptTab !== 'response';
  knowledgeCodeField.hidden = kind !== 'prompt' || activePromptTab !== 'code';
  knowledgeContent.closest('label').hidden = kind === 'prompt' && activePromptTab !== 'prompt';
  knowledgeLanguage.closest('label').hidden = kind !== 'prompt' || activePromptTab !== 'code';
  knowledgeUrl.required = kind === 'link';
  knowledgeContent.required = kind !== 'link' && kind !== 'prompt';
  knowledgeContentLabel.textContent = kind === 'link' ? 'Note (optional)' : kind === 'prompt' ? 'Prompt text' :
    kind === 'checklist' ? 'To-dos, one per line' : 'Content';
  knowledgeHint.textContent = kind === 'link' ? 'The URL is stored on your phone; it is not fetched by Windows.' :
    kind === 'checklist' ? 'Each non-empty line becomes a separate item on your phone.' :
    kind === 'note' ? 'Plain text is saved to your phone. Rich formatting is not available on this desktop page yet.' :
    'Saved directly to this project on your phone.';
  knowledgeContent.classList.toggle('knowledge-code-input', kind === 'prompt' && knowledgeMode.value === 'CODE');
  if (kind === 'prompt') selectPromptTab(activePromptTab);
}
knowledgeKind.addEventListener('change', updateKnowledgeKind);
knowledgeMode.addEventListener('change', updateKnowledgeKind);
document.addEventListener('keydown', event => {
  if (knowledgeComposer.hidden || document.querySelector('dialog[open]')) return;
  if (event.key === 'Escape') { event.preventDefault(); closeKnowledgeComposer(); }
});
function openKnowledgeComposer(projectId, trigger, kind = 'note') {
  if (offlineMode || !knowledgeCapabilities || knowledgeCapabilities.unsupported ||
      !knowledgeCapabilities.knowledgeCreate?.includes(kind)) return;
  resetKnowledgeCreationSurface();
  knowledgeLinkDraft = null;
  knowledgeProjectId = projectId;
  knowledgeTrigger = trigger;
  knowledgePending = null;
  knowledgeEditingPrompt = null;
  knowledgeEditingLink = null;
  knowledgeForm.reset();
  knowledgeKind.value = kind;
  knowledgeKind.disabled = true;
  knowledgeFormHeading.textContent = { checklist: 'New to-do list', prompt: 'New prompt', link: 'New link' }[kind] || 'Add knowledge';
  knowledgeTitle.placeholder = { checklist: 'Untitled to-do list', prompt: 'Untitled prompt', link: 'Untitled link' }[kind] || 'Untitled note';
  activePromptTab = 'prompt';
  setPromptEditable(true);
  knowledgeFeedback.textContent = '';
  updateKnowledgeKind();
  projectList.hidden = true;
  knowledgeViewer.hidden = true;
  knowledgeEditor.hidden = true;
  knowledgeComposer.hidden = false;
  if (kind === 'checklist' || kind === 'link') {
    knowledgeComposer.classList.add('is-dedicated-create', `is-${kind}-create`);
    knowledgeCreateSurface.hidden = false;
    if (kind === 'checklist') {
      knowledgeContent.required = false;
      mountChecklistCreation(knowledgeCreateSurface, knowledgeContent, knowledgeFeedback);
    }
    else renderKnowledgeLinkPage({ projectId, title: '', sourceUrl: '', content: '' }, { creating: true, host: knowledgeCreateSurface });
  }
  window.scrollTo(0, 0);
  if (knowledgeKind.value === 'prompt') requestAnimationFrame(() => sizePromptArea(activePromptArea()));
  if (kind === 'link') knowledgeCreateSurface.querySelector('.knowledge-link-pen').focus();
  else knowledgeTitle.focus();
}
function openRichKnowledgeNote(projectId, trigger) {
  activeNoteBlockId = null;
  knowledgeEditMode = true;
  noteHeadingFoldMode = false;
  knowledgeProjectId = projectId;
  knowledgeTrigger = trigger;
  knowledgeEditing = { new: true, projectId, title: '', blocks: [{ id: `desktop-${crypto.randomUUID()}`,
    type: 'PARAGRAPH', text: '', runs: [], align: 'LEFT', indent: 0, checked: false }], dirty: false, pending: null };
  noteHistory = createNoteHistory({ title: knowledgeEditing.title, blocks: knowledgeEditing.blocks });
  knowledgeEditorTitle.value = '';
  knowledgeEditorFeedback.textContent = '';
  document.querySelector('#knowledge-editor-heading').textContent = 'New note';
  renderKnowledgeEditor();
  projectList.hidden = true;
  knowledgeComposer.hidden = true;
  knowledgeViewer.hidden = true;
  knowledgeEditor.hidden = false;
  window.scrollTo(0, 0);
  resizeKnowledgeTitle();
  knowledgeEditorTitle.focus();
}
function openKnowledgeTypePicker(projectId, trigger) {
  if (!knowledgeCapabilities || knowledgeCapabilities.unsupported) return;
  knowledgeProjectId = projectId;
  knowledgeTrigger = trigger;
  document.querySelector('#knowledge-type-project').textContent =
    `${projectTree?.projects?.find(project => project.id === projectId)?.title || 'Project'} · Project knowledge`;
  knowledgeTypeSelection = 'note';
  renderKnowledgeTypeSelection();
  knowledgeTypePicker.showModal();
  knowledgeTypePicker.querySelector('[data-kind="note"]').focus();
}
const knowledgeTypePreviews = {
  note: { title: 'A page to think on', hint: 'Rich text, images, headings and code—ready when you are.', action: 'Create note ↗', markup: '<strong>Untitled note</strong><p>Start with a thought. <b>Give it a heading</b>, add context, then keep going.</p><p>Your work stays organized in this project.</p>' },
  checklist: { title: 'Make progress visible', hint: 'A simple list for work that needs to get done.', action: 'Create to-do list ↗', markup: '<strong>Things to finish</strong><p><em>☑</em> Define the goal</p><p><em>☐</em> Build the first version</p><p><em>☐</em> Review together</p>' },
  prompt: { title: 'Keep the good prompts', hint: 'Save reusable instructions and code with context.', action: 'Create prompt ↗', markup: '<strong>A prompt worth keeping</strong><p>Describe the task and desired result.</p><code>Write a clear summary of the project,<br>then suggest three next steps.</code>' },
  link: { title: 'Hold on to a source', hint: 'Save a reference so it is easy to find later.', action: 'Create link ↗', markup: '<strong>A useful reference</strong><p><u>https://example.com/research</u></p><p>Add a short note about why this matters.</p>' },
};
let knowledgeTypeSelection = 'note';
function renderKnowledgeTypeSelection() {
  const preview = knowledgeTypePreviews[knowledgeTypeSelection];
  knowledgeTypePicker.querySelectorAll('[data-kind]').forEach(button =>
    button.setAttribute('aria-pressed', String(button.dataset.kind === knowledgeTypeSelection)));
  document.querySelector('#knowledge-type-preview-title').textContent = preview.title;
  document.querySelector('#knowledge-type-hint').textContent = preview.hint;
  document.querySelector('#knowledge-type-create').textContent = preview.action;
  document.querySelector('#knowledge-type-preview').innerHTML = preview.markup;
}
document.querySelector('#knowledge-type-close').addEventListener('click', () => knowledgeTypePicker.close());
knowledgeTypePicker.addEventListener('click', event => {
  const button = event.target.closest('[data-kind]');
  if (!button) return;
  knowledgeTypeSelection = button.dataset.kind;
  renderKnowledgeTypeSelection();
});
document.querySelector('#knowledge-type-create').addEventListener('click', () => {
  const kind = knowledgeTypeSelection;
  const projectId = knowledgeProjectId, trigger = knowledgeTrigger;
  knowledgeTypePicker.close();
  if (kind === 'note' && !knowledgeTaskId) openRichKnowledgeNote(projectId, trigger);
  else { openKnowledgeComposer(projectId, trigger, kind);
    if (kind === 'prompt') { knowledgeTitle.focus(); requestAnimationFrame(() => sizePromptArea(activePromptArea())); } }
});
function closeKnowledgePage(restoreFocus = true) {
  if (knowledgeBusy) return;
  disposeChecklistDialog();
  voiceLoadGeneration++;
  if (voicePlaybackUrl) { URL.revokeObjectURL(voicePlaybackUrl); voicePlaybackUrl = null; }
  for (const url of noteImageUrls.values()) URL.revokeObjectURL(url);
  noteImageUrls.clear();
  knowledgeComposer.hidden = true;
  knowledgeViewer.hidden = true;
  knowledgeEditor.hidden = true;
  projectList.hidden = false;
  knowledgeProjectId = null;
  knowledgePending = null;
  knowledgeVisibleItem = null;
  knowledgeLinkDraft = null;
  knowledgeEditing = null;
  noteHeadingFoldMode = false;
  noteHistory = null;
  knowledgeEditingPrompt = null;
  knowledgeEditingLink = null;
  if (restoreFocus) knowledgeTrigger?.focus();
}
function confirmDiscardNote(options = {}) {
  const dialog = document.querySelector('#knowledge-discard-dialog');
  const stay = document.querySelector('#knowledge-discard-stay');
  const discard = document.querySelector('#knowledge-discard-confirm');
  if (dialog.open) return Promise.resolve(false);
  const replacements = [
    [dialog.querySelector('.knowledge-discard-eyebrow'), options.eyebrow],
    [dialog.querySelector('#knowledge-discard-title'), options.title],
    [dialog.querySelector('#knowledge-discard-description'), options.description],
    [discard, options.confirmLabel],
  ].filter(([, text]) => text != null).map(([element, text]) => {
    const previous = element.textContent; element.textContent = text; return [element, previous];
  });
  const previousFocus = document.activeElement;
  if (options.warning) dialog.classList.add('is-warning');
  return new Promise(resolve => {
    const finish = confirmed => {
      stay.removeEventListener('click', keepEditing);
      discard.removeEventListener('click', discardChanges);
      dialog.removeEventListener('cancel', onCancel);
      if (dialog.open) dialog.close();
      for (const [element, text] of replacements) element.textContent = text;
      dialog.classList.remove('is-warning');
      if (!confirmed && previousFocus?.isConnected) previousFocus.focus();
      resolve(confirmed);
    };
    const keepEditing = () => finish(false);
    const discardChanges = () => finish(true);
    const onCancel = event => { event.preventDefault(); finish(false); };
    stay.addEventListener('click', keepEditing);
    discard.addEventListener('click', discardChanges);
    dialog.addEventListener('cancel', onCancel);
    dialog.showModal();
    stay.focus();
  });
}
async function closeKnowledgeEditor() {
  if (knowledgeBusy) return;
  if (knowledgeEditing?.dirty && !await confirmDiscardNote()) return;
  const wasNew = !!knowledgeEditing?.new;
  knowledgeEditing = null;
  noteHistory = null;
  activeNoteBlockId = null;
  knowledgeEditor.hidden = true;
  if (wasNew) { projectList.hidden = false; knowledgeTrigger?.focus(); }
  else { knowledgeViewer.hidden = false; knowledgeEdit.focus(); }
}
async function allowLeavingKnowledgeEditor() {
  return !knowledgeEditing?.dirty || await confirmDiscardNote();
}
document.querySelector('#knowledge-editor-back').addEventListener('click', closeKnowledgeEditor);
document.querySelector('#knowledge-editor-cancel').addEventListener('click', closeKnowledgeEditor);
async function closeKnowledgeComposer() {
  if (knowledgeBusy) return;
  if (knowledgeContent.value || knowledgeTitle.value || knowledgeUrl.value ||
      knowledgeDescription.value || knowledgeCode.value || knowledgeResponse.value ||
      knowledgeLinkDraft?.dirty || knowledgeLinkDraft?.editing) {
    if (!await confirmDiscardNote({ warning: true, eyebrow: 'UNSAVED CHANGES',
      title: 'Leave this draft?',
      description: 'This draft hasn’t been saved to your phone. Keep editing to save your work, or discard the draft to leave this page.',
      confirmLabel: 'Discard draft' })) return;
  }
  if (knowledgeBusy) return;
  if (knowledgeEditingPrompt || knowledgeEditingLink) {
    knowledgeEditingPrompt = null;
    knowledgeEditingLink = null;
    knowledgeComposer.hidden = true;
    knowledgeViewer.hidden = false;
    knowledgeEdit.focus();
  } else closeKnowledgePage();
}
document.querySelector('#knowledge-close').addEventListener('click', closeKnowledgeComposer);
document.querySelector('#knowledge-cancel').addEventListener('click', closeKnowledgeComposer);
document.querySelector('#knowledge-viewer-back').addEventListener('click', async () => {
  if (knowledgeLinkDraft?.dirty || knowledgeLinkDraft?.editing) {
    const dialog = document.querySelector('#knowledge-discard-dialog');
    const title = dialog.querySelector('#knowledge-discard-title');
    const description = dialog.querySelector('#knowledge-discard-description');
    const previousTitle = title.textContent, previousDescription = description.textContent;
    title.textContent = 'Leave this link?';
    description.textContent = 'Changes you haven’t saved to your phone will be lost. You can stay and save them first.';
    const discard = await confirmDiscardNote();
    title.textContent = previousTitle; description.textContent = previousDescription;
    if (!discard) return;
  }
  closeKnowledgePage();
});
knowledgeCopy.addEventListener('click', async () => {
  if (!knowledgeVisibleItem?.canCopyText) return;
  const text = knowledgeVisibleItem.kind === 'prompt' ? knowledgeViewerContent.dataset.promptSource || '' : knowledgeVisibleItem.content;
  try { await navigator.clipboard.writeText(text); knowledgeViewerFeedback.textContent = 'Copied to clipboard.'; }
  catch { knowledgeViewerFeedback.textContent = 'Clipboard access was denied. Select the text to copy it.'; }
});
knowledgeCopyFormatted.addEventListener('click', async () => {
  if (knowledgeVisibleItem?.kind === 'prompt') {
    try {
      await copyPromptResponse(knowledgeViewerContent.dataset.promptSource || '', knowledgeViewerContent);
      knowledgeViewerFeedback.textContent = 'Formatted section copied.';
    } catch { knowledgeViewerFeedback.textContent = 'Formatted clipboard is unavailable. Use Copy text for the original source.'; }
    return;
  }
  if (knowledgeVisibleItem?.kind !== 'note') return;
  const selection = window.getSelection();
  const selected = selection && !selection.isCollapsed && knowledgeViewerContent.contains(selection.anchorNode) &&
    knowledgeViewerContent.contains(selection.focusNode);
  const container = document.createElement('div');
  if (selected) container.append(selection.getRangeAt(0).cloneContents());
  else container.append(knowledgeViewerContent.cloneNode(true));
  container.querySelectorAll('[data-note-copy]').forEach(button => button.remove());
  const html = container.innerHTML;
  const plain = container.innerText || container.textContent || '';
  try {
    await navigator.clipboard.write([new ClipboardItem({
      'text/html': new Blob([html], { type: 'text/html' }),
      'text/plain': new Blob([plain], { type: 'text/plain' }),
    })]);
    knowledgeViewerFeedback.textContent = selected ? 'Selected formatting copied.' : 'Formatted note copied.';
  } catch {
    knowledgeViewerFeedback.textContent = 'Formatted clipboard is unavailable here. Select the rendered note and use Ctrl+C.';
  }
});
function openKnowledgeEditor() {
  const detail = knowledgeVisibleItem;
  if (!detail || detail.kind !== 'note' || !Array.isArray(detail.noteBlocks) ||
      !Number.isInteger(detail.noteRevision) || !knowledgeCapabilities?.knowledgeNoteEdit) return;
  knowledgeEditing = { projectId: detail.projectId, id: detail.id, revision: detail.noteRevision,
    title: detail.title || '', blocks: structuredClone(detail.noteBlocks), dirty: false, pending: null };
  noteHistory = createNoteHistory({ title: knowledgeEditing.title, blocks: knowledgeEditing.blocks });
  activeNoteBlockId = null;
  knowledgeEditMode = false;
  noteHeadingFoldMode = false;
  knowledgeEditorTitle.value = knowledgeEditing.title;
  document.querySelector('#knowledge-editor-heading').textContent = 'Edit note';
  knowledgeEditorFeedback.textContent = '';
  renderKnowledgeEditor();
  knowledgeViewer.hidden = true;
  knowledgeEditor.hidden = false;
  window.scrollTo(0, 0);
  resizeKnowledgeTitle();
  knowledgeEditorMode.focus();
}
function openKnowledgePromptEditor() {
  resetKnowledgeCreationSurface();
  const detail = knowledgeVisibleItem;
  if (!detail || detail.kind !== 'prompt' || !detail.promptCanEdit ||
      !detail.promptEditToken || !knowledgeCapabilities?.knowledgePromptEdit) return;
  knowledgeEditingPrompt = { projectId: detail.projectId, id: detail.id, token: detail.promptEditToken,
    pending: null };
  knowledgePending = null;
  knowledgeProjectId = detail.projectId;
  knowledgeForm.reset();
  knowledgeFormHeading.textContent = 'Edit prompt';
  knowledgeKind.value = 'prompt'; knowledgeKind.disabled = true;
  knowledgeTitle.value = detail.title || '';
  knowledgeDescription.value = detail.description || '';
  knowledgeTags.value = Array.isArray(detail.tags) ? detail.tags.join(', ') : '';
  knowledgeMode.value = 'PROMPT';
  knowledgeLanguage.value = detail.language || '';
  knowledgeContent.value = detail.mode === 'CODE' ? '' : detail.content || '';
  knowledgeCode.value = detail.codeText || (detail.mode === 'CODE' ? detail.content || '' : '');
  knowledgeResponse.value = detail.responseText || '';
  activePromptTab = 'prompt';
  setPromptEditable(true);
  knowledgeFeedback.textContent = '';
  updateKnowledgeKind();
  knowledgeViewer.hidden = true;
  knowledgeComposer.hidden = false;
  window.scrollTo(0, 0);
  requestAnimationFrame(() => sizePromptArea(activePromptArea()));
  knowledgeTitle.focus();
}
function openKnowledgeLinkEditor() {
  resetKnowledgeCreationSurface();
  const detail = knowledgeVisibleItem;
  if (!detail || detail.kind !== 'link' || !detail.linkEditToken ||
      !knowledgeCapabilities?.knowledgeLinkEdit) return;
  knowledgeEditingLink = { projectId: detail.projectId, id: detail.id, token: detail.linkEditToken };
  knowledgePending = null;
  knowledgeProjectId = detail.projectId;
  knowledgeForm.reset();
  knowledgeFormHeading.textContent = 'Edit link';
  knowledgeKind.value = 'link'; knowledgeKind.disabled = true;
  knowledgeTitle.value = detail.title || '';
  knowledgeUrl.value = detail.sourceUrl || '';
  knowledgeContent.value = detail.content || '';
  knowledgeFeedback.textContent = '';
  updateKnowledgeKind();
  knowledgeViewer.hidden = true;
  knowledgeComposer.hidden = false;
  window.scrollTo(0, 0);
  knowledgeUrl.focus();
}
knowledgeEdit.addEventListener('click', () => {
  if (knowledgeVisibleItem?.kind === 'prompt') openKnowledgePromptEditor();
  else if (knowledgeVisibleItem?.kind === 'link') openKnowledgeLinkEditor();
  else openKnowledgeEditor();
});
function resizeKnowledgeTitle() {
  knowledgeEditorTitle.style.height = 'auto';
  knowledgeEditorTitle.style.height = `${knowledgeEditorTitle.scrollHeight}px`;
}
knowledgeEditorTitle.addEventListener('click', () => {
  if (knowledgeEditing && !knowledgeEditMode && !knowledgeBusy) {
    knowledgeEditMode = true;
    renderKnowledgeEditor();
    knowledgeEditorTitle.focus();
  }
});
knowledgeEditorTitle.addEventListener('keydown', event => {
  if (event.key === 'Enter') event.preventDefault();
});
knowledgeEditorTitle.addEventListener('input', () => {
  resizeKnowledgeTitle();
  if (knowledgeEditing) { knowledgeEditing.title = knowledgeEditorTitle.value; noteChange = { kind: 'typing', key: 'title' }; updateKnowledgeDocumentStatus(); }
});
const noteToolbar = document.querySelector('.knowledge-document-toolbar');
const noteUndo = document.createElement('button');
noteUndo.type = 'button'; noteUndo.id = 'knowledge-toolbar-undo'; noteUndo.textContent = '↶ Undo';
noteUndo.title = 'Undo · Ctrl+Z'; noteUndo.setAttribute('aria-label', 'Undo last note edit');
const noteRedo = document.createElement('button');
noteRedo.type = 'button'; noteRedo.id = 'knowledge-toolbar-redo'; noteRedo.textContent = '↷ Redo';
noteRedo.title = 'Redo · Ctrl+Y or Ctrl+Shift+Z'; noteRedo.setAttribute('aria-label', 'Redo last note edit');
const noteHistoryDivider = document.createElement('span');
noteHistoryDivider.className = 'knowledge-toolbar-divider';
noteToolbar.prepend(noteUndo, noteRedo, noteHistoryDivider);
function restoreNoteHistory(direction) {
  if (!knowledgeEditing || !knowledgeEditMode || knowledgeBusy || !noteHistory) return;
  const titleWasFocused = document.activeElement === knowledgeEditorTitle;
  const restored = direction === 'undo' ? noteHistory.undo() : noteHistory.redo();
  if (!restored) return;
  knowledgeEditing.title = restored.title;
  knowledgeEditing.blocks = restored.blocks;
  knowledgeEditing.dirty = noteHistory.state().dirty;
  knowledgeEditing.pending = null;
  knowledgeEditorTitle.value = restored.title;
  if (!restored.blocks.some(block => block.id === activeNoteBlockId)) activeNoteBlockId = restored.blocks[0]?.id || null;
  noteChange = null;
  renderKnowledgeEditor();
  knowledgeEditorFeedback.textContent = direction === 'undo' ? 'Last edit undone.' : 'Edit redone.';
  if (titleWasFocused) knowledgeEditorTitle.focus();
  else activeNoteBlock()?.editor?.focus();
}
knowledgeEditor.addEventListener('keydown', event => {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
  const key = event.key.toLowerCase();
  const direction = key === 'z' ? event.shiftKey ? 'redo' : 'undo' : key === 'y' ? 'redo' : null;
  if (!direction || !knowledgeEditMode) return;
  event.preventDefault();
  restoreNoteHistory(direction);
});
const headingCycle = document.createElement('button');
headingCycle.type = 'button'; headingCycle.id = 'knowledge-toolbar-heading-cycle';
headingCycle.textContent = 'H1 → H2 → H3';
headingCycle.title = 'Cycle the selected heading level';
headingCycle.setAttribute('aria-label', 'Cycle selected heading level');
const noteFoldMode = document.createElement('button');
noteFoldMode.type = 'button'; noteFoldMode.id = 'knowledge-toolbar-fold-headings';
noteFoldMode.innerHTML = '<span aria-hidden="true">☷</span> Fold headings';
noteFoldMode.setAttribute('aria-label', 'Turn on heading folding');
noteFoldMode.setAttribute('aria-pressed', 'false');
const noteFoldStatus = document.createElement('span');
noteFoldStatus.className = 'knowledge-fold-status';
noteFoldStatus.setAttribute('aria-live', 'polite');
const sectionMenu = document.createElement('select');
sectionMenu.id = 'knowledge-toolbar-sections';
sectionMenu.setAttribute('aria-label', 'Fold document sections');
for (const [value, label] of [['', 'Sections ▾'], ['collapse-selected', 'Collapse selected'], ['expand-selected', 'Expand selected'], ['collapse-all', 'Collapse all'], ['expand-all', 'Expand all']]) {
  const option = document.createElement('option'); option.value = value; option.textContent = label; sectionMenu.append(option);
}
document.querySelector('#knowledge-toolbar-code').after(noteFoldMode, headingCycle, sectionMenu, noteFoldStatus);
knowledgeEditorBlocks.addEventListener('dragover', event => {
  if (!knowledgeEditMode || !draggedNoteBlockId) return;
  event.preventDefault();
  if (event.clientY < 70) window.scrollBy(0, -22);
  else if (event.clientY > window.innerHeight - 70) window.scrollBy(0, 22);
});
knowledgeEditorBlocks.addEventListener('drop', event => {
  if (!knowledgeEditMode || !draggedNoteBlockId || event.target.closest('.knowledge-edit-block')) return;
  event.preventDefault();
  const sourceId = draggedNoteBlockId;
  draggedNoteBlockId = null;
  const lastId = knowledgeEditing?.blocks.at(-1)?.id;
  if (!lastId) return;
  const result = reorderNoteBlocks(knowledgeEditing.blocks, sourceId, lastId, 'after');
  if (!result.moved) return;
  knowledgeEditing.blocks = result.blocks;
  knowledgeEditing.dirty = true;
  activeNoteBlockId = sourceId;
  renderKnowledgeEditor();
  knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(sourceId)}"] .knowledge-drag-handle`)?.focus();
});
knowledgeEditorMode.addEventListener('click', () => {
  if (!knowledgeEditing || knowledgeBusy) return;
  knowledgeEditMode = !knowledgeEditMode;
  renderKnowledgeEditor();
  knowledgeEditorMode.focus();
});
function activeNoteBlock() {
  const index = knowledgeEditing?.blocks.findIndex(block => block.id === activeNoteBlockId) ?? -1;
  if (index < 0) return null;
  const row = knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(activeNoteBlockId)}"]`);
  return { block: knowledgeEditing.blocks[index], index, row, editor: row?.querySelector('[data-note-text]') };
}
function insertNoteTextAtSelection(text) {
  const selection = window.getSelection();
  if (!selection?.rangeCount) return;
  const range = selection.getRangeAt(0);
  range.deleteContents();
  const node = document.createTextNode(text);
  range.insertNode(node);
  range.setStartAfter(node); range.collapse(true);
  selection.removeAllRanges(); selection.addRange(range);
}
function activeTextSelection(active) {
  return noteSelectionOffsets(active?.editor) ||
    (rememberedNoteSelection?.blockId === active?.block.id ? rememberedNoteSelection : null);
}
function focusNoteSelection(active, start, end = start) {
  active?.editor?.focus();
  if (active?.editor) restoreNoteSelection(active.editor, start, end);
}
document.addEventListener('selectionchange', () => {
  const active = activeNoteBlock();
  const offsets = noteSelectionOffsets(active?.editor);
  if (offsets) rememberedNoteSelection = { ...offsets, blockId: active.block.id };
});
function updateKnowledgeDocumentStatus() {
  const edit = knowledgeEditing;
  const state = document.querySelector('#knowledge-editor-save-state');
  const count = document.querySelector('#knowledge-editor-count');
  if (!edit) return;
  if (noteHistory) {
    const change = noteChange;
    noteChange = null;
    const historyState = noteHistory.record({ title: edit.title, blocks: edit.blocks }, change?.kind, change?.key);
    edit.dirty = historyState.dirty;
    noteUndo.disabled = !knowledgeEditMode || !historyState.canUndo || knowledgeBusy;
    noteRedo.disabled = !knowledgeEditMode || !historyState.canRedo || knowledgeBusy;
  }
  state.textContent = edit.dirty ? 'Unsaved changes' : edit.new ? 'Not saved yet' : 'Saved on phone';
  const words = edit.blocks.map(block => block.text || '').join(' ').trim().split(/\s+/).filter(Boolean).length;
  count.textContent = `${words} word${words === 1 ? '' : 's'} · ${edit.blocks.length} block${edit.blocks.length === 1 ? '' : 's'}`;
  const projectName = document.querySelector('#project-breadcrumb-name')?.textContent?.trim();
  document.querySelector('#knowledge-editor-project').textContent = projectName ? ` · ${projectName}` : ' · Project note';
}
function updateKnowledgeOutline() {
  const outline = document.querySelector('#knowledge-editor-outline');
  outline.replaceChildren();
  for (const { block, index, level, hidden, hasChildren } of buildNoteOutline(knowledgeEditing?.blocks || [], noteHeadingFoldMode).headings) {
    if (hidden) continue;
    const entry = document.createElement('div');
    entry.className = 'knowledge-outline-entry';
    entry.style.paddingInlineStart = `${(level - 1) * 13}px`;
    if (noteHeadingFoldMode && hasChildren) {
      const toggle = document.createElement('button');
      toggle.type = 'button'; toggle.className = 'knowledge-outline-toggle';
      toggle.textContent = block.isCollapsed ? '▸' : '▾';
      toggle.setAttribute('aria-label', `${block.isCollapsed ? 'Expand' : 'Collapse'} ${block.text?.trim() || `Heading ${index + 1}`}`);
      toggle.setAttribute('aria-expanded', String(!block.isCollapsed));
      toggle.addEventListener('click', () => toggleKnowledgeHeading(block));
      entry.append(toggle);
    }
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'knowledge-outline-label';
    button.textContent = block.text?.trim() || `Heading ${index + 1}`;
    button.title = `Heading ${level}`;
    button.addEventListener('click', () => knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(block.id)}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
    entry.append(button); outline.append(entry);
  }
  if (!outline.childElementCount) outline.textContent = 'Add headings to build an outline.';
}
function toggleKnowledgeHeading(block) {
  if (!noteHeadingFoldMode) return;
  block.isCollapsed = !block.isCollapsed;
  knowledgeEditing.dirty = true;
  renderKnowledgeEditor();
}
noteToolbar.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button || !knowledgeEditing || !knowledgeEditMode) return;
  if (button === noteUndo) { restoreNoteHistory('undo'); return; }
  if (button === noteRedo) { restoreNoteHistory('redo'); return; }
  if (button === noteFoldMode) {
    noteHeadingFoldMode = !noteHeadingFoldMode;
    renderKnowledgeEditor();
    noteFoldMode.focus();
    return;
  }
  if (button.id === 'knowledge-toolbar-heading-cycle') {
    const active = activeNoteBlock();
    const next = nextHeadingType(active?.block?.type);
    if (!next) { knowledgeEditorFeedback.textContent = 'Select a heading first; body text stays unchanged.'; return; }
    active.block.type = next;
    knowledgeEditing.dirty = true;
    renderKnowledgeEditor();
    activeNoteBlock()?.editor?.focus(); return;
  }
  if (button.id === 'knowledge-toolbar-image') {
    if (!knowledgeCapabilities?.knowledgeImageTransfer || knowledgeEditing.blocks.length >= 500) {
      knowledgeEditorFeedback.textContent = 'Image transfer is unavailable or the 500-block limit was reached.'; return;
    }
    knowledgeEditorBlocks.querySelector('input[type=file]')?.click(); return;
  }
  if (button.id === 'knowledge-toolbar-code') {
    if (knowledgeEditing.blocks.length >= 500) { knowledgeEditorFeedback.textContent = 'This note has reached the 500-block limit.'; return; }
    knowledgeEditing.blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: 'CODE', text: '', runs: [], align: 'LEFT', indent: 0, checked: false });
    knowledgeEditing.dirty = true; activeNoteBlockId = knowledgeEditing.blocks.at(-1).id; renderKnowledgeEditor();
    activeNoteBlock()?.editor?.focus(); return;
  }
  if (button.id === 'knowledge-toolbar-topic') {
    if (knowledgeEditing.blocks.length > 498) { knowledgeEditorFeedback.textContent = 'This note has reached the 500-block limit.'; return; }
    const at = activeNoteBlock()?.index ?? knowledgeEditing.blocks.length - 1;
    const topic = { id: `desktop-${crypto.randomUUID()}`, type: 'HEADING_2', text: '', runs: [], align: 'LEFT', indent: 0, checked: false };
    const paragraph = { id: `desktop-${crypto.randomUUID()}`, type: 'PARAGRAPH', text: '', runs: [], align: 'LEFT', indent: 0, checked: false };
    knowledgeEditing.blocks.splice(at + 1, 0, topic, paragraph);
    knowledgeEditing.dirty = true; activeNoteBlockId = topic.id; renderKnowledgeEditor();
    activeNoteBlock()?.editor?.focus(); return;
  }
  const active = activeNoteBlock();
  if (!active?.editor || ['IMAGE', 'TABLE', 'DIVIDER'].includes(active.block.type)) {
    knowledgeEditorFeedback.textContent = 'Click inside a text block first.'; return;
  }
  const { block } = active;
  if (button.dataset.noteAlign) { block.align = button.dataset.noteAlign; knowledgeEditing.dirty = true; renderKnowledgeEditor(); return; }
  if (button.id === 'knowledge-toolbar-check') {
    if (block.type !== 'CHECKLIST') { knowledgeEditorFeedback.textContent = 'Choose Checklist as this block’s style first.'; return; }
    block.checked = !block.checked; knowledgeEditing.dirty = true; renderKnowledgeEditor(); return;
  }
  const selection = activeTextSelection(active);
  const from = selection?.start ?? 0, to = selection?.end ?? 0;
  if (to <= from) { knowledgeEditorFeedback.textContent = 'Select text in this block first.'; active.editor.focus(); return; }
  if (button.dataset.noteMark) block.runs = toggleNoteMark(block.runs || [], block.text.length, from, to, button.dataset.noteMark);
  else if (button.id === 'knowledge-toolbar-link') {
    const url = window.prompt('Link URL (http:// or https://). Leave empty to remove the link.');
    if (url === null) return;
    const trimmed = url.trim();
    if (trimmed && (!/^https?:\/\/\S+$/i.test(trimmed) || trimmed.length > 2048)) { knowledgeEditorFeedback.textContent = 'Enter a valid http:// or https:// link.'; return; }
    block.runs = setNoteStyle(block.runs || [], block.text.length, from, to, 'linkUrl', trimmed || null);
  } else return;
  knowledgeEditing.dirty = true; renderKnowledgeEditor();
  focusNoteSelection(activeNoteBlock(), from, to);
});
noteToolbar.addEventListener('change', event => {
  if (!knowledgeEditing || !knowledgeEditMode) return;
  if (event.target.id === 'knowledge-toolbar-sections') {
    const action = event.target.value;
    event.target.value = '';
    if (!action) return;
    const headings = buildNoteOutline(knowledgeEditing.blocks).headings.filter(item => item.hasChildren);
    const target = action.endsWith('selected') ? headings.filter(item => item.block.id === activeNoteBlockId) : headings;
    if (!target.length) { knowledgeEditorFeedback.textContent = 'Select a heading with content first.'; return; }
    const collapsed = action.startsWith('collapse');
    target.forEach(item => { item.block.isCollapsed = collapsed; });
    knowledgeEditing.dirty = true;
    renderKnowledgeEditor(); return;
  }
  const active = activeNoteBlock();
  if (!active?.editor) { knowledgeEditorFeedback.textContent = 'Click inside a text block first.'; return; }
  const { block } = active;
  if (event.target.id === 'knowledge-toolbar-style') { block.type = event.target.value; knowledgeEditing.dirty = true; renderKnowledgeEditor(); return; }
  const selection = activeTextSelection(active);
  const from = selection?.start ?? 0, to = selection?.end ?? 0;
  if (to <= from) { knowledgeEditorFeedback.textContent = 'Select text in this block first.'; active.editor.focus(); event.target.value = ''; return; }
  if (event.target.id === 'knowledge-toolbar-font') block.runs = setNoteStyle(block.runs || [], block.text.length, from, to, 'fontFamily', event.target.value || null);
  if (event.target.id === 'knowledge-toolbar-size') block.runs = setNoteStyle(block.runs || [], block.text.length, from, to, 'fontSizeSp', event.target.value ? Number(event.target.value) : null);
  event.target.value = ''; knowledgeEditing.dirty = true; renderKnowledgeEditor();
  focusNoteSelection(activeNoteBlock(), from, to);
});
function renderKnowledgeEditor() {
  knowledgeEditorBlocks.replaceChildren();
  if (!knowledgeEditing) return;
  knowledgeEditor.classList.toggle('is-edit-mode', knowledgeEditMode);
  noteFoldMode.setAttribute('aria-pressed', String(noteHeadingFoldMode));
  noteFoldMode.setAttribute('aria-label', noteHeadingFoldMode ? 'Turn off heading folding' : 'Turn on heading folding');
  noteFoldStatus.textContent = noteHeadingFoldMode ? 'Heading controls on' : '';
  knowledgeEditorMode.setAttribute('aria-pressed', String(knowledgeEditMode));
  knowledgeEditorMode.textContent = knowledgeEditMode ? 'Done editing' : 'Edit';
  knowledgeEditorTitle.readOnly = !knowledgeEditMode;
  resizeKnowledgeTitle();
  knowledgeEditorSave.hidden = !knowledgeEditMode && !knowledgeEditing.dirty;
  updateKnowledgeDocumentStatus();
  updateKnowledgeOutline();
  const sectionState = buildNoteOutline(knowledgeEditing.blocks, noteHeadingFoldMode);
  const addBlock = document.createElement('button');
  addBlock.type = 'button'; addBlock.className = 'secondary knowledge-add-block';
  addBlock.textContent = '+ Text block';
  addBlock.disabled = knowledgeEditing.blocks.length >= 500;
  addBlock.addEventListener('click', () => {
    knowledgeEditing.blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: 'PARAGRAPH',
      text: '', runs: [], align: 'LEFT', indent: 0, checked: false, lineSpacing: 1 });
    knowledgeEditing.dirty = true;
    renderKnowledgeEditor();
    activeNoteBlockId = knowledgeEditing.blocks.at(-1).id;
    activeNoteBlock()?.editor?.focus();
  });
  knowledgeEditorBlocks.append(addBlock);
  const imageInput = document.createElement('input');
  imageInput.type = 'file'; imageInput.accept = 'image/*'; imageInput.hidden = true;
  const addImage = document.createElement('button');
  addImage.type = 'button'; addImage.className = 'secondary knowledge-add-block';
  addImage.textContent = '+ Image';
  addImage.disabled = !knowledgeCapabilities?.knowledgeImageTransfer || knowledgeEditing.blocks.length >= 500;
  addImage.title = addImage.disabled ? 'Update Virlin on your phone to add images from Windows' : '';
  addImage.addEventListener('click', () => imageInput.click());
  imageInput.addEventListener('change', async () => {
    const file = imageInput.files?.[0];
    if (!file || !knowledgeEditing) return;
    addImage.disabled = true;
    knowledgeEditorFeedback.textContent = 'Uploading image to your phone…';
    try {
      const imagePath = await uploadNoteImage(knowledgeEditing.projectId, file, progress => {
        knowledgeEditorFeedback.textContent = `Uploading image to your phone… ${progress}%`;
      });
      noteImageUrls.set(imagePath, URL.createObjectURL(file));
      knowledgeEditing.blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: 'IMAGE', text: '',
        runs: [], align: 'LEFT', indent: 0, checked: false, imagePath });
      knowledgeEditing.dirty = true;
      renderKnowledgeEditor();
      knowledgeEditorFeedback.textContent = 'Image uploaded. Save the note to attach it on your phone.';
    } catch (error) { knowledgeEditorFeedback.textContent = `Image not added: ${String(error)}`; }
    finally { addImage.disabled = false; }
  });
  knowledgeEditorBlocks.append(addImage, imageInput);
  for (const [index, block] of knowledgeEditing.blocks.entries()) {
    const row = document.createElement('section');
    row.className = 'knowledge-edit-block';
    row.dataset.noteBlockId = block.id;
    row.hidden = sectionState.hiddenIds.has(block.id);
    if (block.id === activeNoteBlockId) {
      row.classList.add('is-selected');
      if (knowledgeEditMode) row.classList.add('is-editing');
    }
    row.addEventListener('focusin', event => {
      activeNoteBlockId = block.id;
      if (knowledgeEditMode && event.target === editor) {
        knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block.is-editing').forEach(other => { if (other !== row) other.classList.remove('is-editing'); });
        row.classList.add('is-editing');
      }
      document.querySelector('#knowledge-toolbar-style').value = block.type;
    });
    const order = document.createElement('div');
    order.className = 'knowledge-edit-order';
    const handle = document.createElement('button');
    handle.type = 'button'; handle.className = 'knowledge-drag-handle';
    handle.setAttribute('aria-label', `Move block ${index + 1}. Drag to reorder, or press Alt and the up or down arrow.`);
    handle.title = 'Drag to reorder · Alt+↑ / Alt+↓';
    for (let dot = 0; dot < 6; dot++) handle.append(document.createElement('i'));
    const clearDropMarkers = () => knowledgeEditorBlocks.querySelectorAll('.drop-before,.drop-after,.is-dragging')
      .forEach(other => other.classList.remove('drop-before', 'drop-after', 'is-dragging'));
    const moveBlock = (targetId, position) => {
      const result = reorderNoteBlocks(knowledgeEditing.blocks, block.id, targetId, position);
      if (!result.moved) return;
      const before = new Map([...knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block:not([hidden])')]
        .map(item => [item.dataset.noteBlockId, item.getBoundingClientRect().top]));
      knowledgeEditing.blocks = result.blocks;
      knowledgeEditing.dirty = true;
      activeNoteBlockId = block.id;
      renderKnowledgeEditor();
      if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        for (const item of knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block:not([hidden])')) {
          const oldTop = before.get(item.dataset.noteBlockId);
          if (oldTop == null) continue;
          const distance = oldTop - item.getBoundingClientRect().top;
          if (Math.abs(distance) > 1) item.animate(
            [{ transform: `translateY(${distance}px)` }, { transform: 'translateY(0)' }],
            { duration: 190, easing: 'cubic-bezier(.2,.8,.2,1)' });
        }
      }
      knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(block.id)}"] .knowledge-drag-handle`)?.focus();
    };
    // Pointer dragging works in WebView2 even when native HTML drag on a button does not.
    // Keep the actual document still until drop; only a visual copy follows the pointer.
    handle.addEventListener('pointerdown', event => {
      if (!knowledgeEditMode || knowledgeBusy || event.button !== 0) return;
      event.preventDefault();
      handle.setPointerCapture(event.pointerId);
      const startY = event.clientY;
      const rect = row.getBoundingClientRect();
      let preview = null;
      let destination = null;
      const visibleRows = () => [...knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block:not([hidden])')];
      const cleanup = () => {
        preview?.remove();
        clearDropMarkers();
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', finish);
        handle.removeEventListener('pointercancel', cancel);
        if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
      };
      const move = pointer => {
        if (pointer.pointerId !== event.pointerId) return;
        if (!preview && Math.abs(pointer.clientY - startY) < 5) return;
        if (!preview) {
          preview = row.cloneNode(true);
          preview.classList.add('knowledge-drag-preview');
          preview.setAttribute('aria-hidden', 'true');
          preview.removeAttribute('data-note-block-id');
          preview.style.left = `${rect.left}px`;
          preview.style.top = `${rect.top}px`;
          preview.style.width = `${rect.width}px`;
          document.body.append(preview);
          row.classList.add('is-dragging');
        }
        preview.style.transform = `translateY(${pointer.clientY - startY}px)`;
        clearDropMarkers();
        row.classList.add('is-dragging');
        if (pointer.clientY < 70) window.scrollBy(0, -18);
        else if (pointer.clientY > window.innerHeight - 70) window.scrollBy(0, 18);
        destination = noteDropTarget(visibleRows().map(item => {
          const bounds = item.getBoundingClientRect();
          return { id: item.dataset.noteBlockId, top: bounds.top, bottom: bounds.bottom };
        }), block.id, pointer.clientY);
        if (destination) knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(destination.id)}"]`)
          ?.classList.add(destination.position === 'before' ? 'drop-before' : 'drop-after');
      };
      const finish = pointer => {
        if (pointer.pointerId !== event.pointerId) return;
        const drop = preview && destination;
        cleanup();
        if (drop) moveBlock(drop.id, drop.position);
      };
      const cancel = pointer => { if (pointer.pointerId === event.pointerId) cleanup(); };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', finish);
      handle.addEventListener('pointercancel', cancel);
    });
    handle.addEventListener('keydown', event => {
      if (!knowledgeEditMode || !event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
      event.preventDefault();
      const neighbor = knowledgeEditing.blocks[index + (event.key === 'ArrowUp' ? -1 : 1)];
      if (neighbor) moveBlock(neighbor.id, event.key === 'ArrowUp' ? 'before' : 'after');
    });
    order.append(handle); row.append(order);
    const heading = sectionState.headings.find(item => item.block.id === block.id);
    if (noteHeadingFoldMode && heading?.hasChildren) {
      const fold = document.createElement('button');
      fold.type = 'button'; fold.className = 'knowledge-heading-toggle';
      fold.textContent = block.isCollapsed ? '+' : '−';
      fold.title = block.isCollapsed ? 'Expand section' : 'Collapse section';
      fold.setAttribute('aria-label', `${fold.title}: ${block.text?.trim() || 'Untitled heading'}`);
      fold.setAttribute('aria-expanded', String(!block.isCollapsed));
      fold.addEventListener('click', event => { event.stopPropagation(); toggleKnowledgeHeading(block); });
      row.append(fold);
    }
    row.addEventListener('dragover', event => {
      if (!knowledgeEditMode || !draggedNoteBlockId || draggedNoteBlockId === block.id) return;
      event.preventDefault(); event.dataTransfer.dropEffect = 'move';
      const position = event.clientY < row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2 ? 'before' : 'after';
      clearDropMarkers();
      row.classList.add(position === 'before' ? 'drop-before' : 'drop-after');
    });
    row.addEventListener('drop', event => {
      if (!knowledgeEditMode || !draggedNoteBlockId) return;
      event.preventDefault(); event.stopPropagation();
      const sourceId = draggedNoteBlockId;
      draggedNoteBlockId = null;
      const position = row.classList.contains('drop-before') ? 'before' : 'after';
      clearDropMarkers();
      const result = reorderNoteBlocks(knowledgeEditing.blocks, sourceId, block.id, position);
      if (!result.moved) return;
      knowledgeEditing.blocks = result.blocks;
      knowledgeEditing.dirty = true;
      activeNoteBlockId = sourceId;
      renderKnowledgeEditor();
      knowledgeEditorBlocks.querySelector(`[data-note-block-id="${CSS.escape(sourceId)}"] .knowledge-drag-handle`)?.focus();
    });
    if (['TABLE', 'IMAGE', 'DIVIDER'].includes(block.type)) {
      const label = document.createElement('span');
      label.className = 'knowledge-edit-readonly';
      label.textContent = block.type === 'IMAGE' && noteImageUrls.has(block.imagePath) ?
        'Image · Saved with this note' : `${block.type.toLowerCase()} · Preserved unchanged on save`;
      row.append(label, renderKnowledgeBlock(block));
      if (block.type === 'IMAGE') {
        row.classList.add('knowledge-image-row');
        row.tabIndex = 0;
        row.setAttribute('aria-label', 'Select image to reveal edit controls');
        const selectImage = () => {
          knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block.is-selected').forEach(other => other.classList.remove('is-selected'));
          row.classList.add('is-selected'); activeNoteBlockId = block.id;
        };
        row.addEventListener('click', selectImage);
        row.addEventListener('keydown', event => { if (event.target === row && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); selectImage(); } });
        const pen = document.createElement('button');
        pen.type = 'button'; pen.className = 'knowledge-image-pen'; pen.textContent = '✎';
        pen.setAttribute('aria-label', 'Edit this image'); pen.title = 'Edit image';
        pen.addEventListener('click', async event => {
          event.stopPropagation();
          try {
            const url = noteImageUrls.get(block.imagePath) || await loadNoteImage(knowledgeEditing.projectId, knowledgeEditing.id, block.imagePath);
            await noteImageEditor.open(block.id, url);
          } catch (error) { knowledgeEditorFeedback.textContent = `Image editor unavailable: ${String(error)}`; }
        });
        row.append(pen);
      }
      knowledgeEditorBlocks.append(row);
      continue;
    }
    const editor = renderKnowledgeBlock(block, numberedListPosition(knowledgeEditing.blocks, index));
    editor.dataset.noteText = 'true';
    editor.dataset.empty = block.text ? 'false' : 'true';
    editor.contentEditable = String(knowledgeEditMode);
    editor.spellcheck = block.type !== 'CODE';
    editor.setAttribute('role', 'textbox');
    editor.setAttribute('aria-multiline', 'true');
    editor.setAttribute('aria-label', `${block.type.toLowerCase().replaceAll('_', ' ')} content`);
    editor.dataset.placeholder = /^HEADING_/.test(block.type) ? 'Topic heading…' : 'Write or paste here…';
    const prefix = editor.querySelector('.knowledge-doc-prefix');
    if (prefix) { prefix.remove(); row.append(prefix); }
    const codeCopy = editor.querySelector('[data-note-copy]');
    if (codeCopy) { codeCopy.remove(); row.append(codeCopy); }
    const codeExpand = editor.querySelector('[data-note-expand]');
    if (codeExpand) { codeExpand.remove(); row.append(codeExpand); }
    const promptLabel = block.type === 'PROMPT' && block.promptTitle ? editor.querySelector('strong') : null;
    if (promptLabel) { promptLabel.remove(); row.append(promptLabel); }
    const updateText = (kind = 'typing') => {
      const next = editor.textContent || '';
      if (next.length > 32768) { knowledgeEditorFeedback.textContent = 'This note exceeds the 32,768-character limit.'; return; }
      block.runs = shiftNoteRuns(block.runs || [], block.text || '', next);
      block.text = next;
      editor.dataset.empty = next ? 'false' : 'true';
      knowledgeEditing.dirty = true;
      noteChange = { kind, key: block.id };
      updateKnowledgeDocumentStatus();
      updateKnowledgeOutline();
    };
    editor.addEventListener('input', () => updateText());
    editor.addEventListener('click', event => {
      if (knowledgeEditMode && event.target.closest('a')) event.preventDefault();
    });
    editor.addEventListener('paste', event => {
      const html = event.clipboardData?.getData('text/html');
      if (!html) {
        const plain = event.clipboardData?.getData('text/plain');
        if (plain == null) return;
        const imported = parsePlainNoteText(plain);
        if (imported.some(item => item.type === 'BULLET' || item.type === 'NUMBERED')) {
          const offsets = noteSelectionOffsets(editor);
          if (!offsets) return;
          const next = insertRichNoteBlocks(knowledgeEditing.blocks, index, offsets.start, offsets.end, imported);
          event.preventDefault();
          if (next.length > 500 || next.reduce((sum, item) => sum + (item.text?.length || 0), 0) > 32768) {
            knowledgeEditorFeedback.textContent = 'Pasted content exceeds the note limit.'; return;
          }
          knowledgeEditing.blocks = next; knowledgeEditing.dirty = true;
          activeNoteBlockId = imported[0].id; renderKnowledgeEditor();
          activeNoteBlock()?.editor?.focus();
          return;
        }
        event.preventDefault();
        insertNoteTextAtSelection(plain);
        updateText('paste');
        return;
      }
      try {
        const imported = parseRichNoteHtml(html);
        if (!imported.length) {
          event.preventDefault();
          insertNoteTextAtSelection(event.clipboardData?.getData('text/plain') || '');
          updateText('paste');
          return;
        }
        const offsets = noteSelectionOffsets(editor);
        if (!offsets) return;
        const next = insertRichNoteBlocks(knowledgeEditing.blocks, index, offsets.start, offsets.end, imported);
        if (next.length > 500 || next.reduce((sum, item) => sum + (item.text?.length || 0), 0) > 32768) {
          event.preventDefault(); knowledgeEditorFeedback.textContent = 'Pasted content exceeds the note limit.'; return;
        }
        event.preventDefault();
        knowledgeEditing.blocks = next;
        knowledgeEditing.dirty = true;
        activeNoteBlockId = imported[0].id;
        renderKnowledgeEditor();
        knowledgeEditorFeedback.textContent = `Pasted ${imported.length} formatted block${imported.length === 1 ? '' : 's'}. ${/<img\b/i.test(html) ? 'Embedded images need Add Image; they were not pasted. ' : ''}Save to send the note to your phone.`;
        activeNoteBlock()?.editor?.focus();
      } catch (error) { event.preventDefault(); knowledgeEditorFeedback.textContent = `Formatted paste was not imported: ${String(error)}`; }
    });
    editor.addEventListener('keydown', event => {
      if (event.key === 'Enter') {
        event.preventDefault();
        insertNoteTextAtSelection('\n');
        updateText('typing');
        return;
      }
      if (!(event.ctrlKey || event.metaKey)) return;
      const mark = { b: 'BOLD', i: 'ITALIC', u: 'UNDERLINE' }[event.key.toLowerCase()];
      if (!mark) return;
      event.preventDefault();
      const offsets = noteSelectionOffsets(editor);
      const from = offsets?.start ?? 0, to = offsets?.end ?? 0;
      if (to > from) { block.runs = toggleNoteMark(block.runs || [], block.text.length, from, to, mark); knowledgeEditing.dirty = true; }
      else knowledgeEditorFeedback.textContent = 'Select text first to apply formatting.';
      if (to > from) { renderKnowledgeEditor(); focusNoteSelection(activeNoteBlock(), from, to); }
    });
    editor.addEventListener('focus', () => {
      knowledgeEditorBlocks.querySelectorAll('.knowledge-edit-block.is-selected').forEach(other => other.classList.remove('is-selected'));
      row.classList.add('is-selected');
      activeNoteBlockId = block.id;
    });
    editor.addEventListener('blur', () => {
      if (knowledgeEditMode && block.text !== editor.textContent) updateText();
    });
    row.append(editor);
    knowledgeEditorBlocks.append(row);
  }
}
knowledgeEditorSave.addEventListener('click', async () => {
  if (!knowledgeEditing || knowledgeBusy) return;
  const edit = knowledgeEditing;
  const payload = edit.new ? { projectId: edit.projectId, taskId: null, kind: 'note',
    title: edit.title.trim() || null, content: edit.blocks.map(block => block.text).join('\n'), noteBlocks: edit.blocks } :
    { projectId: edit.projectId, captureId: edit.id, expectedRevision: edit.revision,
      title: edit.title.trim() || null, blocks: edit.blocks };
  if (edit.blocks.reduce((count, block) => count + (block.text?.length || 0), 0) > 32768) {
    knowledgeEditorFeedback.textContent = 'This note exceeds the 32,768-character desktop limit.'; return;
  }
  if (edit.new && !edit.blocks.some(block => block.text?.trim() || block.type === 'IMAGE')) {
    knowledgeEditorFeedback.textContent = 'Write something before saving the note.'; return;
  }
  const fingerprint = JSON.stringify(payload);
  if (!edit.pending || edit.pending.fingerprint !== fingerprint)
    edit.pending = { fingerprint, key: crypto.randomUUID() };
  const requestBytes = new TextEncoder().encode(JSON.stringify({ v: 1,
    type: edit.new ? 'create_knowledge' : 'save_knowledge_note', ...payload, key: edit.pending.key })).length;
  if (requestBytes >= 512 * 1024) {
    knowledgeEditorFeedback.textContent = 'This formatted note is too large to send. Reduce its content or formatting and retry.';
    return;
  }
  knowledgeBusy = true; knowledgeEditorSave.disabled = true;
  knowledgeEditorFeedback.textContent = 'Saving on your phone…';
  try {
    const result = JSON.parse(await invoke(edit.new ? 'create_knowledge' : 'save_knowledge_note', { ...payload, key: edit.pending.key }));
    if (result.v !== 1 || result.type !== (edit.new ? 'knowledge_created' : 'knowledge_note_saved') ||
        !result.id || (!edit.new && result.id !== edit.id))
      throw new Error(result.code || 'Phone did not confirm the note edit');
    knowledgeEditing = null;
    knowledgeEditor.hidden = true;
    knowledgeCache.delete(edit.projectId);
    await loadKnowledge(edit.projectId);
    const savedItem = knowledgeCache.get(edit.projectId)?.items?.find(item => item.id === result.id);
    await openKnowledgeItem(edit.projectId, savedItem || { id: result.id, title: edit.title,
      kind: edit.blocks.some(block => block.type === 'CHECKLIST') ? 'checklist' : 'note' }, knowledgeTrigger);
    knowledgeViewerFeedback.textContent = 'Note saved on your phone.';
  } catch (error) {
    knowledgeEditorFeedback.textContent = `Save not confirmed: ${String(error)}. Check the phone; reload if it changed.`;
  } finally { knowledgeBusy = false; knowledgeEditorSave.disabled = false; }
});
function numberedListPosition(blocks, index) {
  if (blocks[index]?.type !== 'NUMBERED') return 1;
  let position = 1;
  for (let at = index - 1; at >= 0 && blocks[at]?.type === 'NUMBERED'; at--) position++;
  return position;
}
function renderKnowledgeBlock(block, listPosition = 1) {
  const type = String(block.type || 'PARAGRAPH');
  const tag = type === 'HEADING_1' ? 'h2' : type === 'HEADING_2' ? 'h3' :
    type === 'HEADING_3' ? 'h4' : type === 'QUOTE' ? 'blockquote' :
    type === 'CODE' ? 'pre' : 'div';
  const row = document.createElement(tag);
  row.className = `knowledge-doc-block knowledge-doc-${type.toLowerCase()}`;
  row.style.textAlign = { LEFT: 'left', CENTER: 'center', RIGHT: 'right' }[block.align] || 'left';
  if (type === 'DIVIDER') { row.append(document.createElement('hr')); return row; }
  if (type === 'IMAGE') {
    const url = noteImageUrls.get(block.imagePath);
    if (url) {
      const img = document.createElement('img'); img.src = url; img.alt = 'Image in note';
      img.className = 'knowledge-doc-image'; row.append(img);
    } else row.textContent = 'Loading image from your phone…';
    return row;
  }
  if (type === 'TABLE' && block.table) {
    const table = document.createElement('table');
    const rows = Math.min(30, Math.max(0, Number(block.table.rows) || 0));
    const cols = Math.min(15, Math.max(0, Number(block.table.cols) || 0));
    for (let r = 0; r < rows; r++) {
      const tr = document.createElement('tr');
      for (let c = 0; c < cols; c++) {
        const td = document.createElement('td');
        td.textContent = String(block.table.cells?.[r * cols + c] ?? '');
        tr.append(td);
      }
      table.append(tr);
    }
    row.append(table);
    return row;
  }
  const text = String(block.text ?? '');
  const runs = Array.isArray(block.runs) ? block.runs.filter(run =>
    Number.isInteger(run.start) && Number.isInteger(run.end) &&
    run.start >= 0 && run.end > run.start && run.end <= text.length).sort((a, b) => a.start - b.start) : [];
  let at = 0;
  for (const run of runs) {
    if (run.start < at) continue;
    row.append(document.createTextNode(text.slice(at, run.start)));
    let span = document.createElement('span');
    span.textContent = text.slice(run.start, run.end);
    const family = { Inter: 'sans-serif', Serif: 'serif', Mono: 'monospace' }[run.fontFamily];
    if (family) span.style.fontFamily = family;
    if (Number.isInteger(run.fontSizeSp) && run.fontSizeSp >= 8 && run.fontSizeSp <= 96)
      span.style.fontSize = `${run.fontSizeSp}px`;
    const cssColor = value => Number.isSafeInteger(value) && value >= 0 && value <= 0xffffffff ?
      `#${(value & 0xffffff).toString(16).padStart(6, '0')}${((value >>> 24) & 0xff).toString(16).padStart(2, '0')}` : null;
    if (cssColor(run.color)) span.style.color = cssColor(run.color);
    if (cssColor(run.highlight)) span.style.backgroundColor = cssColor(run.highlight);
    const marks = Array.isArray(run.marks) ? run.marks : [];
    for (const mark of marks) {
      const tagName = { BOLD: 'strong', ITALIC: 'em', UNDERLINE: 'u', STRIKE: 's', CODE: 'code' }[mark];
      if (!tagName) continue;
      const wrapper = document.createElement(tagName);
      wrapper.append(span);
      span = wrapper;
    }
    if (typeof run.linkUrl === 'string' && /^https?:\/\/\S+$/i.test(run.linkUrl)) {
      const link = document.createElement('a');
      link.href = run.linkUrl; link.target = '_blank'; link.rel = 'noopener noreferrer';
      link.append(span); span = link;
    }
    row.append(span);
    at = run.end;
  }
  row.append(document.createTextNode(text.slice(at)));
  if (type === 'BULLET' || type === 'NUMBERED' || type === 'CHECKLIST') {
    const prefix = document.createElement('span');
    prefix.className = 'knowledge-doc-prefix';
    prefix.textContent = type === 'BULLET' ? '•' : type === 'NUMBERED' ? `${listPosition}.` : block.checked ? '☑' : '☐';
    row.prepend(prefix);
  }
  if (type === 'PROMPT' && block.promptTitle) {
    const heading = document.createElement('strong');
    heading.textContent = `${block.promptTitle} · `;
    row.prepend(heading);
  }
  row.style.marginInlineStart = `${Math.min(6, Math.max(0, Number(block.indent) || 0)) * 1.5}rem`;
  if (type === 'CODE') row.append(makeKnowledgeCodeCopyButton(text), makeKnowledgeCodeExpandButton());
  return row;
}
function makeKnowledgeCodeExpandButton() {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'knowledge-code-expand';
  button.dataset.noteExpand = 'true';
  button.setAttribute('aria-label', 'Show full code block');
  button.setAttribute('aria-expanded', 'false');
  button.textContent = 'Show all';
  button.addEventListener('keydown', event => event.stopPropagation());
  button.addEventListener('click', event => {
    event.stopPropagation();
    const code = button.closest('.knowledge-edit-block')?.querySelector('.knowledge-doc-code') ||
      button.closest('.knowledge-doc-code');
    if (!code) return;
    const expanded = code.classList.toggle('is-code-expanded');
    button.textContent = expanded ? 'Collapse' : 'Show all';
    button.setAttribute('aria-label', expanded ? 'Collapse code block' : 'Show full code block');
    button.setAttribute('aria-expanded', String(expanded));
  });
  return button;
}
function makeKnowledgeCodeCopyButton(text) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'knowledge-code-copy';
  button.dataset.noteCopy = 'true';
  button.textContent = 'Copy';
  button.setAttribute('aria-label', 'Copy code block');
  button.addEventListener('keydown', event => event.stopPropagation());
  button.addEventListener('click', async event => {
    event.stopPropagation();
    try {
      await navigator.clipboard.writeText(text);
      button.textContent = 'Copied';
    } catch {
      button.textContent = 'Copy failed';
    }
    window.setTimeout(() => { if (button.isConnected) button.textContent = 'Copy'; }, 1800);
  });
  return button;
}
async function uploadNoteImage(projectId, file, onProgress) {
  if (!file.type.startsWith('image/') || file.size < 1 || file.size > 25 * 1024 * 1024)
    throw new Error('Choose an image smaller than 25 MB.');
  const uploadId = crypto.randomUUID();
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))]
    .map(byte => byte.toString(16).padStart(2, '0')).join('');
  let offset = 0;
  while (offset < file.size) {
    const chunk = new Uint8Array(await file.slice(offset, offset + 64 * 1024).arrayBuffer());
    let binary = '';
    for (const byte of chunk) binary += String.fromCharCode(byte);
    const response = JSON.parse(await invoke('upload_note_image', {
      projectId, uploadId, totalBytes: file.size, offset, sha256: hash, data: btoa(binary),
    }));
    offset += chunk.length;
    if (response.v !== 1 || response.uploadId !== uploadId || response.received !== offset ||
        response.type !== (offset === file.size ? 'note_image_uploaded' : 'note_image_progress'))
      throw new Error(response.code || 'Phone did not confirm the image chunk');
    onProgress(Math.round(offset / file.size * 100));
  }
  return uploadId;
}
async function loadNoteImage(projectId, captureId, imagePath) {
  if (noteImageUrls.has(imagePath)) return noteImageUrls.get(imagePath);
  const chunks = [];
  let offset = 0, total = null;
  do {
    const response = JSON.parse(await invoke('note_image_chunk', { projectId, captureId, imagePath, offset }));
    if (response.v !== 1 || response.type !== 'note_image_chunk' || response.imagePath !== imagePath ||
        response.offset !== offset || typeof response.data !== 'string' ||
        !Number.isSafeInteger(response.totalBytes) || response.totalBytes < 1 || response.totalBytes > 25 * 1024 * 1024 ||
        (total != null && response.totalBytes !== total)) throw new Error(response.code || 'Invalid phone image response');
    total = response.totalBytes;
    const binary = atob(response.data);
    if (!binary.length || offset + binary.length > total) throw new Error('Invalid image chunk');
    chunks.push(Uint8Array.from(binary, char => char.charCodeAt(0)));
    offset += binary.length;
    if (response.done !== (offset === total)) throw new Error('Image ended unexpectedly');
  } while (offset < total);
  const url = URL.createObjectURL(new Blob(chunks));
  noteImageUrls.set(imagePath, url);
  return url;
}
function renderChecklistViewer(projectId, detail) {
  disposeChecklistDialog();
  const host = knowledgeViewerContent;
  const collapsed = new Set();
  let filter = 'all';
  let pending = null;
  let busy = false;
  const readOnly = () => offlineMode || !knowledgeCapabilities?.knowledgeNoteEdit ||
    !Number.isInteger(detail.noteRevision);
  async function commit(nextBlocks) {
    if (readOnly() || busy || !nextBlocks) return false;
    const payload = { projectId, captureId: detail.id, expectedRevision: detail.noteRevision,
      title: detail.title?.trim() || null, blocks: nextBlocks };
    const fingerprint = JSON.stringify(payload);
    if (!pending || pending.fingerprint !== fingerprint) pending = { fingerprint, key: crypto.randomUUID() };
    if (new TextEncoder().encode(JSON.stringify({ v: 1, type: 'save_knowledge_note',
      ...payload, key: pending.key })).length >= 512 * 1024) {
      knowledgeViewerFeedback.textContent = 'This note is too large to send from the checklist. Use Edit full note to reduce it.';
      render();
      return false;
    }
    busy = true;
    knowledgeBusy = true;
    knowledgeViewerFeedback.textContent = 'Saving changes on your phone…';
    render();
    let saved = false;
    try {
      const result = JSON.parse(await invoke('save_knowledge_note', { ...payload, key: pending.key }));
      if (result.v !== 1 || result.type !== 'knowledge_note_saved' || result.id !== detail.id ||
          !Number.isInteger(result.revision)) throw new Error(result.code || 'Phone did not confirm the change');
      detail.noteBlocks = nextBlocks;
      detail.noteRevision = result.revision;
      detail.content = nextBlocks.map(block => block.text || '').join('\n');
      pending = null;
      knowledgeCache.delete(projectId);
      loadKnowledge(projectId);
      knowledgeViewerFeedback.textContent = 'Saved on your phone.';
      saved = true;
    } catch (error) {
      knowledgeViewerFeedback.textContent = `Change not confirmed: ${String(error)}. Check the phone before retrying.`;
    } finally { busy = false; knowledgeBusy = false; render(); }
    return saved;
  }
  function openBatchDialog() {
    if (readOnly() || busy || activeChecklistDialog) return;
    const dialog = document.createElement('dialog'); dialog.className = 'checklist-batch-dialog';
    dialog.setAttribute('aria-labelledby', 'checklist-batch-title');
    dialog.innerHTML = `<div class="checklist-batch-head"><div><span>NEW IN THIS CHECKLIST</span><h2 id="checklist-batch-title">Turn a list into tasks</h2><p>Write naturally. Review the exact tasks before adding.</p></div><button type="button" class="checklist-batch-close" aria-label="Close">×</button></div>
      <div class="checklist-batch-grid"><div class="checklist-batch-write"><label for="checklist-batch-input">Paste or type</label><textarea id="checklist-batch-input" placeholder="• First task\n• Second task" spellcheck="false"></textarea><div class="checklist-batch-hint">Bullets · numbers · plain lines · JSON array <button type="button" class="checklist-batch-example">Try JSON</button></div></div><div class="checklist-batch-review"><div class="checklist-batch-review-head"><strong>Ready to add</strong><span class="checklist-batch-count" aria-live="polite">0 tasks</span></div><div class="checklist-batch-preview" aria-live="polite"></div><p class="checklist-batch-error" role="alert" hidden></p></div></div>
      <div class="checklist-batch-foot"><span class="checklist-batch-format" aria-live="polite">Paste tasks to begin</span><div><button type="button" class="checklist-batch-cancel">Cancel</button><button type="button" class="checklist-batch-submit">Add tasks</button></div></div>`;
    document.body.append(dialog);
    activeChecklistDialog = dialog;
    const input = dialog.querySelector('textarea');
    const preview = dialog.querySelector('.checklist-batch-preview');
    const count = dialog.querySelector('.checklist-batch-count');
    const format = dialog.querySelector('.checklist-batch-format');
    const error = dialog.querySelector('.checklist-batch-error');
    const submit = dialog.querySelector('.checklist-batch-submit');
    const closeButton = dialog.querySelector('.checklist-batch-close');
    const cancelButton = dialog.querySelector('.checklist-batch-cancel');
    const close = () => { if (!busy) dialog.close(); };
    closeButton.addEventListener('click', close);
    cancelButton.addEventListener('click', close);
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (activeChecklistDialog === dialog) {
        activeChecklistDialog = null;
        knowledgeViewerContent.querySelector('.checklist-add')?.focus();
      }
    });
    function update() {
      const parsed = parseChecklistTasks(input.value);
      preview.replaceChildren();
      for (const task of parsed.tasks.slice(0, 12)) {
        const row = document.createElement('div'); row.className = 'checklist-batch-row';
        const checkbox = document.createElement('span'); checkbox.className = 'checklist-batch-check'; checkbox.setAttribute('aria-hidden', 'true');
        const text = document.createElement('span'); text.textContent = task;
        row.append(checkbox, text); preview.append(row);
      }
      if (!parsed.tasks.length) {
        const empty = document.createElement('p'); empty.className = 'checklist-batch-empty';
        empty.textContent = parsed.error || 'Tasks will appear here as you type.'; preview.append(empty);
      } else if (parsed.tasks.length > 12) {
        const more = document.createElement('p'); more.className = 'checklist-batch-empty';
        more.textContent = `+ ${parsed.tasks.length - 12} more tasks`; preview.append(more);
      }
      count.textContent = `${parsed.tasks.length} ${parsed.tasks.length === 1 ? 'task' : 'tasks'}`;
      format.textContent = ({ json: 'JSON array detected', list: 'Bulleted or numbered list detected', plain: 'Plain lines detected', empty: 'Paste tasks to begin' })[parsed.format];
      const limitError = parsed.tasks.length > 50 || detail.noteBlocks.length + parsed.tasks.length > 500
        ? 'Add at most 50 tasks at once, within the 500-block note limit.'
        : parsed.tasks.some(task => task.length > 1000) ? 'Each task must be 1,000 characters or fewer.' : '';
      const connectionError = readOnly() ? 'Reconnect a supported phone to add tasks.' : '';
      error.textContent = parsed.error || limitError || connectionError; error.hidden = !error.textContent;
      submit.disabled = busy || readOnly() || !parsed.tasks.length || !!error.textContent;
      submit.textContent = `Add ${parsed.tasks.length} ${parsed.tasks.length === 1 ? 'task' : 'tasks'}`;
      return parsed;
    }
    input.addEventListener('input', update);
    input.addEventListener('paste', event => {
      const plain = event.clipboardData?.getData('text/plain') || '';
      const html = event.clipboardData?.getData('text/html') || '';
      if (!html || plain.trim().startsWith('[') || /\r?\n/.test(plain)) return;
      const parsedHtml = new DOMParser().parseFromString(html, 'text/html');
      const items = [...parsedHtml.querySelectorAll('li')].map(item => item.textContent.trim()).filter(Boolean);
      if (!items.length) return;
      event.preventDefault();
      input.setRangeText(items.join('\n'), input.selectionStart, input.selectionEnd, 'end');
      update();
    });
    dialog.querySelector('.checklist-batch-example').addEventListener('click', () => {
      input.value = '["Review release changes", "Confirm note editing on mobile"]'; input.focus(); update();
    });
    submit.addEventListener('click', async () => {
      const parsed = update();
      if (submit.disabled) return;
      const next = appendChecklistTasks(detail.noteBlocks, parsed.tasks, () => `desktop-${crypto.randomUUID()}`);
      if (!next) { error.textContent = 'These tasks exceed the note’s limits. Shorten the list and try again.'; error.hidden = false; return; }
      submit.disabled = true;
      closeButton.disabled = true; cancelButton.disabled = true;
      const saved = await commit(next);
      if (saved) dialog.close();
      else {
        closeButton.disabled = false; cancelButton.disabled = false;
        update(); error.textContent = knowledgeViewerFeedback.textContent || 'The phone did not confirm these tasks.'; error.hidden = false;
      }
    });
    update();
    dialog.showModal();
    input.focus();
  }
  function render() {
    if (knowledgeVisibleItem !== detail) return;
    host.replaceChildren();
    const counts = checklistCounts(detail.noteBlocks);
    const intro = document.createElement('div'); intro.className = 'checklist-intro';
    const kicker = document.createElement('span'); kicker.className = 'checklist-kicker'; kicker.textContent = 'PROJECT KNOWLEDGE / TO-DO';
    const subtitle = document.createElement('p'); subtitle.textContent = offlineMode
      ? 'Read-only copy on this computer. Reconnect your phone to make changes.'
      : readOnly() ? 'This phone version does not support checklist changes from Windows.'
        : 'Check off tasks as you go. Changes are confirmed by your phone.';
    intro.append(kicker, subtitle); host.append(intro);
    const progress = document.createElement('div'); progress.className = 'checklist-progress';
    const progressText = document.createElement('div');
    const strong = document.createElement('strong'); strong.textContent = `${counts.done} of ${counts.total}`;
    progressText.append(strong, ' complete');
    const track = document.createElement('div'); track.className = 'checklist-progress-track';
    const fill = document.createElement('span'); fill.style.width = `${counts.total ? counts.done / counts.total * 100 : 0}%`;
    track.append(fill); progress.append(progressText, track); host.append(progress);
    const actions = document.createElement('div'); actions.className = 'checklist-actions';
    const filters = document.createElement('div'); filters.className = 'checklist-filters'; filters.setAttribute('aria-label', 'Filter to-do items');
    for (const [key, label, count] of [['all', 'All', counts.total], ['open', 'Open', counts.total - counts.done], ['done', 'Done', counts.done]]) {
      const button = document.createElement('button'); button.type = 'button'; button.className = key === filter ? 'active' : '';
      button.setAttribute('aria-pressed', String(key === filter)); button.textContent = `${label} ${count}`;
      button.addEventListener('click', () => { filter = key; render(); }); filters.append(button);
    }
    actions.append(filters);
    if (!readOnly()) {
      const add = document.createElement('button'); add.type = 'button'; add.className = 'checklist-add';
      add.textContent = '＋ Add task'; add.disabled = busy || detail.noteBlocks.length >= 500;
      add.addEventListener('click', openBatchDialog);
      actions.append(add);
    }
    host.append(actions);
    const sections = checklistSections(detail.noteBlocks);
    for (const [sectionIndex, section] of sections.entries()) {
      const matches = section.blocks.filter(block => block.type === 'CHECKLIST' &&
        (filter === 'all' || (filter === 'done' ? block.checked : !block.checked)));
      if (filter !== 'all' && !matches.length) continue;
      const wrapper = document.createElement('section'); wrapper.className = 'checklist-section';
      const header = document.createElement('div'); header.className = 'checklist-section-head';
      const icon = document.createElement('span'); icon.className = 'checklist-section-icon';
      icon.textContent = String(sectionIndex + 1).padStart(2, '0');
      const name = document.createElement('strong'); name.textContent = section.title;
      const count = document.createElement('small'); count.textContent = `${section.blocks.filter(block => block.type === 'CHECKLIST').length} tasks`;
      const toggle = document.createElement('button'); toggle.type = 'button';
      toggle.setAttribute('aria-label', `${collapsed.has(section.id) ? 'Expand' : 'Collapse'} ${section.title}`);
      toggle.setAttribute('aria-expanded', String(!collapsed.has(section.id)));
      toggle.textContent = collapsed.has(section.id) ? '›' : '⌄';
      toggle.addEventListener('click', () => { collapsed.has(section.id) ? collapsed.delete(section.id) : collapsed.add(section.id); render(); });
      header.append(icon, name, count, toggle); wrapper.append(header);
      if (!collapsed.has(section.id)) {
        const items = document.createElement('div'); items.className = 'checklist-items';
        for (const block of section.blocks) {
          if (block.type !== 'CHECKLIST') {
            if (filter === 'all') {
              const context = document.createElement('div'); context.className = 'checklist-context';
              context.append(renderKnowledgeBlock(block)); items.append(context);
            }
            continue;
          }
          if (filter === 'done' && !block.checked || filter === 'open' && block.checked) continue;
          const row = document.createElement('div'); row.className = 'checklist-item';
          if (block.checked) row.classList.add('is-done');
          const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = !!block.checked;
          checkbox.disabled = readOnly() || busy;
          checkbox.setAttribute('aria-label', `${block.checked ? 'Mark incomplete' : 'Mark complete'}: ${block.text}`);
          checkbox.addEventListener('change', () => commit(toggleChecklistBlock(detail.noteBlocks, block.id, checkbox.checked)));
          const text = renderKnowledgeBlock(block); text.querySelector('.knowledge-doc-prefix')?.remove();
          row.append(checkbox, text); items.append(row);
        }
        wrapper.append(items);
      }
      host.append(wrapper);
    }
    if (!counts.total) {
      const empty = document.createElement('p'); empty.className = 'checklist-empty'; empty.textContent = 'No tasks yet.'; host.append(empty);
    } else if (filter !== 'all' && !host.querySelector('.checklist-section')) {
      const empty = document.createElement('p'); empty.className = 'checklist-empty';
      empty.textContent = filter === 'done' ? 'No completed tasks yet.' : 'All tasks are complete.'; host.append(empty);
    }
  }
  render();
  for (const block of detail.noteBlocks.filter(block => block.type === 'IMAGE' && block.imagePath)) {
    loadNoteImage(projectId, detail.id, block.imagePath).then(() => {
      if (knowledgeVisibleItem === detail) render();
    }).catch(() => {
      if (knowledgeVisibleItem === detail) knowledgeViewerFeedback.textContent = 'An image could not be loaded from your phone.';
    });
  }
}
function renderKnowledgeLinkPage(detail, { creating = false, host = knowledgeViewerContent } = {}) {
  const canEdit = !offlineMode && (creating ? knowledgeCapabilities?.knowledgeCreate?.includes('link') :
    !!detail.linkEditToken && !!knowledgeCapabilities?.knowledgeLinkEdit);
  const draft = { title: detail.title || '', url: detail.sourceUrl || '', note: detail.content || '', dirty: false };
  knowledgeLinkDraft = draft;
  const page = document.createElement('div');
  page.className = 'knowledge-link-page';
  page.innerHTML = `<div class="knowledge-link-heading"><div><span>PROJECT LINK</span><h2 class="knowledge-link-title"></h2></div><button class="primary knowledge-link-save" type="button">Save to phone</button></div>
    <div class="knowledge-link-preview-heading"><div><strong class="knowledge-link-preview-label">Video preview</strong><span class="knowledge-link-domain"></span></div><span class="knowledge-link-ratio">Full frame · 16:9</span></div>
    <div class="knowledge-link-video"><div class="knowledge-link-video-placeholder"><span>▶ YouTube</span><button type="button" aria-label="Play YouTube video">▶</button><strong class="knowledge-link-video-title"></strong></div></div>
    <div class="knowledge-link-website" hidden><strong>Website link</strong><span>Open this page in your browser.</span></div>
    <div class="knowledge-link-panel"><div class="knowledge-link-tabs" role="tablist" aria-label="Link details"><button type="button" data-link-tab="title" role="tab" aria-selected="false">▤ Title</button><button type="button" data-link-tab="url" role="tab" aria-selected="true">🔗 Link</button><button type="button" data-link-tab="note" role="tab" aria-selected="false">▧ Note</button></div>
    <div class="knowledge-link-field"><div class="knowledge-link-field-heading"><div><small class="knowledge-link-field-label"></small><div class="knowledge-link-field-value"></div></div><button type="button" class="knowledge-link-pen" aria-label="Edit link field">✎</button></div><input class="knowledge-link-input" hidden><textarea class="knowledge-link-textarea" rows="4" hidden></textarea><p class="knowledge-link-error" role="alert" hidden></p><div class="knowledge-link-field-actions" hidden><button type="button" class="secondary knowledge-link-cancel">Cancel</button><button type="button" class="primary knowledge-link-apply">Apply change</button></div></div>
    <div class="knowledge-link-footer"><span>Read-only until you choose the pen.</span><div><button type="button" class="secondary knowledge-link-copy">Copy link</button><a class="secondary knowledge-link-open" target="_blank" rel="noopener noreferrer">Open website ↗</a></div></div></div>`;
  host.replaceChildren(page);
  const tabs = [...page.querySelectorAll('[data-link-tab]')];
  const field = page.querySelector('.knowledge-link-field');
  const value = page.querySelector('.knowledge-link-field-value');
  const input = page.querySelector('.knowledge-link-input');
  const textarea = page.querySelector('.knowledge-link-textarea');
  const pen = page.querySelector('.knowledge-link-pen');
  const actions = page.querySelector('.knowledge-link-field-actions');
  const error = page.querySelector('.knowledge-link-error');
  let selected = 'url', editing = false;
  function renderPreview() {
    const link = classifyLink(draft.url);
    const video = page.querySelector('.knowledge-link-video');
    const website = page.querySelector('.knowledge-link-website');
    const open = page.querySelector('.knowledge-link-open');
    page.querySelector('.knowledge-link-title').textContent = draft.title || 'Untitled link';
    page.querySelector('.knowledge-link-video-title').textContent = draft.title || 'YouTube video';
    page.querySelector('.knowledge-link-domain').textContent = link?.host || 'Invalid address';
    page.querySelector('.knowledge-link-preview-label').textContent = link?.kind === 'youtube' ? 'Video preview' : 'Website link';
    video.hidden = link?.kind !== 'youtube';
    website.hidden = link?.kind === 'youtube';
    open.href = link?.url || '#';
    open.setAttribute('aria-disabled', String(!link));
    const frame = video.querySelector('iframe');
    if (frame) frame.remove();
    video.querySelector('.knowledge-link-video-placeholder').hidden = false;
  }
  function renderField() {
    draft.editing = editing;
    tabs.forEach(tab => tab.setAttribute('aria-selected', String(tab.dataset.linkTab === selected)));
    const label = { title: 'DOCUMENT TITLE', url: 'VIDEO OR WEBSITE ADDRESS', note: 'YOUR NOTE' }[selected];
    page.querySelector('.knowledge-link-field-label').textContent = label;
    value.textContent = draft[selected] || (selected === 'note' ? 'No note added.' : 'Untitled link');
    pen.hidden = !canEdit || editing;
    pen.setAttribute('aria-label', `Edit ${selected === 'url' ? 'link address' : selected}`);
    value.hidden = editing;
    input.hidden = !editing || selected === 'note';
    textarea.hidden = !editing || selected !== 'note';
    actions.hidden = !editing;
    if (editing) {
      const editor = selected === 'note' ? textarea : input;
      editor.value = draft[selected];
      input.type = selected === 'url' ? 'url' : 'text';
      input.maxLength = selected === 'url' ? 2048 : 200;
      editor.setAttribute('aria-label', `Edit ${selected === 'url' ? 'link address' : selected}`);
      editor.focus();
    }
    error.hidden = true;
  }
  tabs.forEach(tab => tab.addEventListener('click', () => {
    if (editing) {
      page.querySelector('.knowledge-link-apply').click();
      if (editing) return;
    }
    selected = tab.dataset.linkTab; renderField();
  }));
  pen.addEventListener('click', () => { editing = true; renderField(); });
  page.querySelector('.knowledge-link-cancel').addEventListener('click', () => { editing = false; renderField(); pen.focus(); });
  page.querySelector('.knowledge-link-apply').addEventListener('click', () => {
    const next = (selected === 'note' ? textarea : input).value.trim();
    if (selected === 'url' && !classifyLink(next)) { error.textContent = 'Enter a valid http or https link.'; error.hidden = false; return; }
    if (selected === 'title' && !next) { error.textContent = 'Enter a title.'; error.hidden = false; return; }
    if (next !== draft[selected]) { draft[selected] = next; draft.dirty = true; knowledgeViewerFeedback.textContent = 'Unsaved changes · Save to phone to confirm.'; }
    editing = false; renderPreview(); renderField(); pen.focus();
  });
  page.querySelector('.knowledge-link-copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(draft.url); knowledgeViewerFeedback.textContent = 'Link copied.'; }
    catch { knowledgeViewerFeedback.textContent = 'Clipboard access was denied.'; }
  });
  page.querySelector('.knowledge-link-open').addEventListener('click', event => {
    if (!classifyLink(draft.url)) event.preventDefault();
  });
  page.querySelector('.knowledge-link-video-placeholder button').addEventListener('click', () => {
    const link = classifyLink(draft.url);
    if (link?.kind !== 'youtube') return;
    const iframe = document.createElement('iframe');
    iframe.src = link.embedUrl;
    iframe.title = `YouTube video: ${draft.title || 'Project link'}`;
    iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
    iframe.allowFullscreen = true;
    iframe.referrerPolicy = 'strict-origin-when-cross-origin';
    page.querySelector('.knowledge-link-video').append(iframe);
    page.querySelector('.knowledge-link-video-placeholder').hidden = true;
  });
  const save = page.querySelector('.knowledge-link-save');
  save.hidden = !canEdit;
  save.addEventListener('click', () => {
    if (editing) {
      page.querySelector('.knowledge-link-apply').click();
      if (editing) return;
    }
    if (knowledgeBusy || !draft.dirty) return;
    if (!classifyLink(draft.url)) {
      selected = 'url'; editing = true; renderField();
      error.textContent = 'Enter a valid http or https link before saving.'; error.hidden = false;
      return;
    }
    knowledgeEditingLink = creating ? null : { projectId: detail.projectId, id: detail.id, token: detail.linkEditToken };
    if (!creating) knowledgePending = null;
    knowledgeProjectId = detail.projectId;
    if (!creating) knowledgeForm.reset();
    knowledgeKind.value = 'link'; knowledgeKind.disabled = true;
    if (!creating) updateKnowledgeKind();
    knowledgeTitle.value = draft.title;
    knowledgeUrl.value = draft.url;
    knowledgeContent.value = draft.note;
    knowledgeViewerFeedback.textContent = 'Saving changes on your phone…';
    knowledgeForm.requestSubmit();
  });
  renderPreview(); renderField();
}
async function openKnowledgeItem(projectId, item, trigger) {
  knowledgeViewerContent.classList.remove('prompt-response-document');
  delete knowledgeViewerContent.dataset.promptSource;
  disposeChecklistDialog();
  knowledgeLinkDraft = null;
  voiceLoadGeneration++;
  if (voicePlaybackUrl) { URL.revokeObjectURL(voicePlaybackUrl); voicePlaybackUrl = null; }
  knowledgeTrigger = trigger;
  knowledgeViewerTitle.textContent = item.title;
  knowledgeViewerDocumentTitle.textContent = item.title || 'Untitled note';
  knowledgeViewer.classList.toggle('is-document-note', item.kind === 'note');
  knowledgeViewer.classList.toggle('is-checklist', item.kind === 'checklist');
  knowledgeViewer.classList.toggle('is-link', item.kind === 'link');
  knowledgeViewer.setAttribute('aria-labelledby', ['note', 'checklist', 'prompt'].includes(item.kind)
    ? 'knowledge-viewer-document-title' : 'knowledge-viewer-title');
  knowledgeViewerKind.textContent = item.kind.toUpperCase();
  knowledgeViewerSummary.textContent = 'Loading the complete item from your phone…';
  knowledgeViewerContent.textContent = '';
  knowledgeViewer.querySelectorAll('.knowledge-prompt-view-tabs').forEach(tabs => tabs.remove());
  knowledgeViewerExtra.replaceChildren();
  knowledgeViewerFeedback.textContent = '';
  knowledgeCopy.hidden = true;
  knowledgeCopyFormatted.hidden = true;
  knowledgeEdit.hidden = true;
  projectList.hidden = true;
  knowledgeComposer.hidden = true;
  knowledgeEditor.hidden = true;
  knowledgeViewer.hidden = false;
  window.scrollTo(0, 0);
  try {
    const detail = JSON.parse(await invoke('knowledge_item', { projectId, captureId: item.id }));
    if (detail.v !== 1 || detail.type !== 'knowledge_item' || detail.projectId !== projectId || detail.id !== item.id)
      throw new Error(detail.code || 'Phone did not return this item');
    knowledgeVisibleItem = detail;
    knowledgeViewerTitle.textContent = detail.title || item.title;
    knowledgeViewerDocumentTitle.textContent = detail.title || item.title || 'Untitled note';
    knowledgeViewer.classList.toggle('is-document-note', detail.kind === 'note');
    knowledgeViewer.classList.toggle('is-checklist', item.kind === 'checklist');
    knowledgeViewer.classList.toggle('is-link', detail.kind === 'link');
    knowledgeViewer.setAttribute('aria-labelledby', ['note', 'checklist'].includes(item.kind)
      ? 'knowledge-viewer-document-title' : 'knowledge-viewer-title');
    knowledgeViewerContent.replaceChildren();
    if (detail.kind === 'link') {
      renderKnowledgeLinkPage(detail);
    } else if (item.kind === 'checklist' && Array.isArray(detail.noteBlocks)) {
      renderChecklistViewer(projectId, detail);
    } else if (Array.isArray(detail.noteBlocks) && detail.noteBlocks.length) {
      for (const [index, block] of detail.noteBlocks.entries())
        knowledgeViewerContent.append(renderKnowledgeBlock(block, numberedListPosition(detail.noteBlocks, index)));
      for (const block of detail.noteBlocks.filter(block => block.type === 'IMAGE' && block.imagePath)) {
        loadNoteImage(projectId, detail.id, block.imagePath).then(() => {
          if (knowledgeVisibleItem !== detail) return;
          const index = detail.noteBlocks.indexOf(block);
          knowledgeViewerContent.children[index]?.replaceWith(renderKnowledgeBlock(block));
          if (knowledgeEditing?.id === detail.id) renderKnowledgeEditor();
        }).catch(() => {
          if (knowledgeVisibleItem === detail) knowledgeViewerFeedback.textContent = 'An image could not be loaded from your phone.';
        });
      }
    } else if (detail.kind === 'prompt') {
      const sections = {
        prompt: detail.mode === 'CODE' ? '' : detail.content || '',
        code: detail.codeText || (detail.mode === 'CODE' ? detail.content || '' : ''),
        response: detail.responseText || ''
      };
      const tabs = document.createElement('div');
      tabs.className = 'knowledge-prompt-tabs knowledge-prompt-view-tabs';
      tabs.setAttribute('role', 'tablist');
      tabs.setAttribute('aria-label', 'Prompt sections');
      for (const [name, label] of [['prompt', 'Prompt'], ['code', 'Code'], ['response', 'Response']]) {
        const button = document.createElement('button');
        button.type = 'button'; button.setAttribute('role', 'tab');
        button.textContent = label;
        button.addEventListener('click', () => {
          for (const sibling of tabs.children) sibling.setAttribute('aria-selected', String(sibling === button));
          knowledgeViewerContent.replaceChildren();
          knowledgeViewerContent.dataset.promptSource = sections[name];
          if (name === 'code') {
            renderPromptResponse(knowledgeViewerContent, sections.code, { codeOnly: true, language: detail.language || '',
              feedback: message => { knowledgeViewerFeedback.textContent = message; } });
          } else renderPromptResponse(knowledgeViewerContent, sections[name],
            { feedback: message => { knowledgeViewerFeedback.textContent = message; } });
        });
        tabs.append(button);
      }
      knowledgeViewerContent.before(tabs);
      tabs.children[sections.prompt ? 0 : sections.code ? 1 : 2].click();
    } else knowledgeViewerContent.textContent = detail.content || '';
    if (detail.kind === 'link' && detail.sourceUrl && !knowledgeViewer.classList.contains('is-link')) {
      const section = document.createElement('section');
      section.className = 'knowledge-detail-section';
      const heading = document.createElement('h3');
      heading.textContent = 'Link URL';
      const url = document.createElement('p');
      url.textContent = detail.sourceUrl;
      url.style.overflowWrap = 'anywhere';
      const copy = document.createElement('button');
      copy.type = 'button'; copy.className = 'secondary'; copy.textContent = 'Copy URL';
      copy.addEventListener('click', async () => {
        try { await navigator.clipboard.writeText(detail.sourceUrl); knowledgeViewerFeedback.textContent = 'Link URL copied.'; }
        catch { knowledgeViewerFeedback.textContent = 'Clipboard access was denied.'; }
      });
      section.append(heading, url, copy);
      knowledgeViewerExtra.append(section);
    }
    if (detail.kind === 'voice' && knowledgeCapabilities?.knowledgeAudioPlayback) {
      for (const clip of detail.voiceClips || []) {
        const section = document.createElement('section');
        section.className = 'knowledge-detail-section';
        const heading = document.createElement('h3');
        heading.textContent = clip.name || 'Voice clip';
        const info = document.createElement('p');
        info.textContent = `${Math.round((clip.durationMs || 0) / 1000)} seconds · ${Math.round((clip.sizeBytes || 0) / 1024)} KB`;
        const button = document.createElement('button');
        button.type = 'button'; button.className = 'secondary'; button.textContent = 'Load clip for playback';
        button.addEventListener('click', async () => {
          const generation = voiceLoadGeneration;
          button.disabled = true; button.textContent = offlineMode ? 'Loading from offline copy…' : 'Loading from phone…';
          try {
            if (!Number.isSafeInteger(clip.sizeBytes) || clip.sizeBytes < 1 || clip.sizeBytes > 20 * 1024 * 1024)
              throw new Error('Clip exceeds the 20 MB playback limit');
            const bytes = new Uint8Array(clip.sizeBytes);
            let offset = 0;
            while (offset < clip.sizeBytes) {
              const chunk = JSON.parse(await invoke('voice_chunk', { projectId, captureId: detail.id, clipId: clip.id, offset }));
              if (generation !== voiceLoadGeneration) return;
              if (chunk.v !== 1 || chunk.type !== 'voice_chunk' || chunk.clipId !== clip.id ||
                  chunk.offset !== offset || chunk.totalBytes !== clip.sizeBytes || typeof chunk.data !== 'string')
                throw new Error(chunk.code || 'Phone did not confirm the audio chunk');
              const decoded = atob(chunk.data);
              if (!decoded.length || offset + decoded.length > bytes.length) throw new Error('Invalid audio chunk');
              for (let i = 0; i < decoded.length; i++) bytes[offset + i] = decoded.charCodeAt(i);
              offset += decoded.length;
              if (chunk.done !== (offset === clip.sizeBytes)) throw new Error('Audio ended unexpectedly');
            }
            knowledgeViewerExtra.querySelectorAll('audio').forEach(player => player.remove());
            knowledgeViewerExtra.querySelectorAll('button').forEach(other => { other.hidden = false; other.disabled = false; other.textContent = 'Load clip for playback'; });
            if (voicePlaybackUrl) URL.revokeObjectURL(voicePlaybackUrl);
            voicePlaybackUrl = URL.createObjectURL(new Blob([bytes], { type: clip.mimeType || 'audio/mp4' }));
            const player = document.createElement('audio');
            player.controls = true; player.src = voicePlaybackUrl; player.preload = 'none';
            section.append(player);
            button.hidden = true;
            knowledgeViewerFeedback.textContent = offlineMode ? 'Clip loaded from this computer for playback.' : 'Clip loaded from your phone for playback.';
          } catch (error) {
            if (generation !== voiceLoadGeneration) return;
            button.disabled = false; button.textContent = 'Retry loading clip';
            knowledgeViewerFeedback.textContent = `Audio not loaded: ${String(error)}.`;
          }
        });
        section.append(heading, info, button);
        knowledgeViewerExtra.append(section);
      }
    }
    knowledgeViewerSummary.textContent = offlineMode ?
      (detail.kind === 'voice' ? 'Voice clip saved in the read-only offline copy.' :
        detail.kind === 'file' ? 'File metadata is available offline; the original file remains on your phone.' :
          'Saved in the read-only offline copy on this computer.') :
      detail.kind === 'link' ? 'Link saved on your phone' : detail.kind === 'voice' ?
      (knowledgeCapabilities?.knowledgeAudioPlayback ?
        'Voice clips stay on your phone. Load a clip over the approved connection to play it here.' :
        'Voice clips remain on your phone; this phone version does not support desktop playback.') :
      detail.canCopyText ? 'Complete text from your phone · Read-only' :
      'This file stays on your phone. Desktop transfer is not available yet.';
    knowledgeCopy.hidden = !detail.canCopyText;
    knowledgeCopyFormatted.hidden = detail.kind !== 'prompt' &&
      (item.kind === 'checklist' || detail.kind !== 'note' || !Array.isArray(detail.noteBlocks));
    const canEditNote = detail.kind === 'note' && Array.isArray(detail.noteBlocks) &&
      Number.isInteger(detail.noteRevision) && knowledgeCapabilities?.knowledgeNoteEdit;
    const canEditPrompt = detail.kind === 'prompt' && detail.promptCanEdit &&
      detail.promptEditToken && knowledgeCapabilities?.knowledgePromptEdit;
    const canEditLink = detail.kind === 'link' && detail.linkEditToken &&
      knowledgeCapabilities?.knowledgeLinkEdit;
    knowledgeEdit.hidden = offlineMode || !(canEditNote || canEditPrompt || canEditLink);
    knowledgeEdit.textContent = item.kind === 'checklist' ? 'Edit full note' : detail.kind === 'prompt' ? 'Edit prompt' : detail.kind === 'link' ? 'Edit link' : 'Edit note';
  } catch (error) {
    knowledgeViewerSummary.textContent = 'This phone version cannot open the complete item on Windows.';
    knowledgeViewerFeedback.textContent = `${String(error)}. Update the Virlin phone app to use this page.`;
  }
}
knowledgeForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!knowledgeProjectId || knowledgeBusy) return;
  if (knowledgeKind.value === 'checklist' && !knowledgeContent.value.trim()) {
    knowledgeFeedback.textContent = 'Add at least one task before saving.';
    knowledgeCreateSurface.querySelector('.checklist-add')?.focus();
    return;
  }
  if (knowledgeKind.value === 'prompt' && !knowledgeContent.value.trim() && !knowledgeCode.value.trim()) {
    knowledgeFeedback.textContent = 'Add a prompt or code before saving.';
    selectPromptTab('prompt');
    return;
  }
  if (knowledgeKind.value === 'prompt' && knowledgeCode.value.trim() && !knowledgeCapabilities?.knowledgePromptSections) {
    knowledgeFeedback.textContent = 'Update Virlin on your phone to save separate Prompt and Code sections.';
    selectPromptTab('code');
    return;
  }
  const payload = { projectId: knowledgeProjectId, kind: knowledgeKind.value,
    taskId: knowledgeEditingPrompt || knowledgeEditingLink ? null : knowledgeTaskId,
    title: knowledgeTitle.value.trim() || null, content: knowledgeContent.value,
    url: knowledgeKind.value === 'link' ? knowledgeUrl.value.trim() : null,
    description: knowledgeKind.value === 'prompt' ? knowledgeDescription.value.trim() || null : null,
    tags: knowledgeKind.value === 'prompt' ? knowledgeTags.value.split(',').map(tag => tag.trim()).filter(Boolean) : [],
    mode: knowledgeKind.value === 'prompt' ? knowledgeMode.value : 'PROMPT',
    language: knowledgeKind.value === 'prompt' && knowledgeCode.value.trim() ? knowledgeLanguage.value.trim() || null : null,
    responseText: knowledgeKind.value === 'prompt' ? knowledgeResponse.value || null : null };
  if (knowledgeKind.value === 'prompt' && knowledgeCapabilities?.knowledgePromptSections)
    payload.codeText = knowledgeCode.value;
  const fingerprint = JSON.stringify(payload);
  if (!knowledgePending || knowledgePending.fingerprint !== fingerprint)
    knowledgePending = { fingerprint, key: crypto.randomUUID() };
  const projectId = knowledgeProjectId;
  knowledgeBusy = true;
  knowledgeSubmit.disabled = true;
  knowledgeFeedback.textContent = 'Saving on your phone…';
  try {
    const editing = knowledgeEditingPrompt || knowledgeEditingLink;
    const command = knowledgeEditingPrompt ? 'save_knowledge_prompt' : knowledgeEditingLink ? 'save_knowledge_link' : 'create_knowledge';
    const expectedType = knowledgeEditingPrompt ? 'knowledge_prompt_saved' : knowledgeEditingLink ? 'knowledge_link_saved' : 'knowledge_created';
    const response = JSON.parse(await invoke(command,
      { ...payload, ...(editing ? { captureId: editing.id, expectedToken: editing.token } : {}), key: knowledgePending.key }));
    if (response.v !== 1 || response.type !== expectedType || !response.id)
      throw new Error(response.code || 'Phone did not confirm the save');
    knowledgeBusy = false;
    closeKnowledgePage();
    knowledgeCache.delete(projectId);
    await loadKnowledge(projectId);
    if (editing) await openKnowledgeItem(projectId, { id: editing.id, title: payload.title, kind: payload.kind }, knowledgeTrigger);
    workspaceStatus.textContent = 'Knowledge saved on your phone.';
  } catch (error) {
    knowledgeFeedback.textContent = `Save not confirmed: ${String(error)}. Check your phone before retrying.`;
    if (knowledgeEditingLink && !knowledgeViewer.hidden)
      knowledgeViewerFeedback.textContent = knowledgeFeedback.textContent;
  } finally { knowledgeBusy = false; knowledgeSubmit.disabled = false; }
});

function renderKnowledge(project, tree) {
  if (knowledgeTaskId && !tree.tasks.some(task => task.id === knowledgeTaskId && task.projectId === project.id)) {
    knowledgeTaskId = null;
    knowledgeTaskReturn = null;
  }
  const result = knowledgeCache.get(project.id);
  const section = document.createElement('section');
  section.className = 'knowledge-library';
  const heading = document.createElement('div');
  heading.className = 'knowledge-heading';
  const title = document.createElement('div');
  const h2 = document.createElement('h2');
  const scopedTask = knowledgeTaskId ? tree.tasks.find(task => task.id === knowledgeTaskId && task.projectId === project.id) : null;
  h2.textContent = scopedTask ? `${scopedTask.title} knowledge` : 'Project knowledge';
  const sub = document.createElement('p');
  const visibleCount = result?.items?.filter(item => !knowledgeTaskId || item.taskId === knowledgeTaskId).length;
  sub.textContent = visibleCount !== undefined ? `${visibleCount} saved item${visibleCount === 1 ? '' : 's'} · ${scopedTask ? 'Saved on this task' : 'A library of your work'}` : 'A library of your work';
  title.append(h2, sub);
  if (scopedTask && knowledgeTaskReturn) {
    const back = document.createElement('button');
    back.type = 'button';
    back.className = 'knowledge-back-task';
    back.textContent = '← Back to task';
    back.addEventListener('click', async () => {
      if (!await allowLeavingKnowledgeEditor()) return;
      closeKnowledgePage(false);
      selectedTaskLocation = knowledgeTaskReturn;
      knowledgeTaskId = null;
      knowledgeTaskReturn = null;
      projectDetailTab = 'Overview';
      renderProjects(tree);
    });
    title.prepend(back);
  }
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'knowledge-add';
  add.textContent = '+ Add knowledge';
  add.disabled = offlineMode || !knowledgeCapabilities || knowledgeCapabilities.unsupported;
  add.title = offlineMode ? 'Reconnect your phone to add knowledge' : add.disabled ? 'Update the Virlin app on your phone to add knowledge from Windows' : '';
  add.addEventListener('click', () => openKnowledgeTypePicker(project.id, add));
  heading.append(title, add);
  section.append(heading);
  if (knowledgeCapabilities?.unsupported) {
    const compatibility = document.createElement('p');
    compatibility.className = 'knowledge-message';
    compatibility.textContent = 'This phone version cannot accept knowledge edits from Windows. Update Virlin on your phone, then reconnect.';
    section.append(compatibility);
  }
  if (!result) {
    const loading = document.createElement('p');
    loading.className = 'knowledge-message';
    loading.textContent = 'Loading saved knowledge from your phone…';
    section.append(loading);
    projectList.append(section);
    return;
  }
  if (result.error) {
    const error = document.createElement('div');
    error.className = 'knowledge-message';
    error.textContent = `Could not load knowledge: ${result.error}. Your phone may need the updated Virlin app.`;
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.textContent = 'Retry';
    retry.addEventListener('click', () => { knowledgeCache.delete(project.id); renderProjects(tree); loadKnowledge(project.id); });
    error.append(' ', retry);
    section.append(error);
    projectList.append(section);
    return;
  }
  const controls = document.createElement('div');
  controls.className = 'knowledge-controls';
  const search = document.createElement('input');
  search.type = 'search';
  search.placeholder = 'Search knowledge';
  search.setAttribute('aria-label', 'Search knowledge');
  search.value = knowledgeQuery;
  search.addEventListener('input', () => { knowledgeQuery = search.value.toLocaleLowerCase(); renderKnowledgeCards(); });
  controls.append(search);
  const types = [['All', 'All'], ['Notes', 'note'], ['To-dos', 'checklist'], ['Prompts', 'prompt'], ['Media', 'media'], ['Images', 'image'], ['Audio', 'audio'], ['Docs', 'document'], ['Steps', 'steps'], ['Links', 'link']];
  const chips = document.createElement('div');
  chips.className = 'knowledge-chips';
  for (const [label, value] of types) {
    const count = result.items.filter(item => (!knowledgeTaskId || item.taskId === knowledgeTaskId) &&
      (value === 'All' || (value === 'media' ? ['image', 'audio', 'video'].includes(item.kind) : item.kind === value))).length;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `${label} ${count}`;
    button.classList.toggle('active', knowledgeFilter === value);
    button.addEventListener('click', () => { knowledgeFilter = value; renderProjects(tree); });
    chips.append(button);
  }
  controls.append(chips);
  const tools = document.createElement('div');
  tools.className = 'knowledge-tools';
  const scope = document.createElement('select');
  scope.setAttribute('aria-label', 'Filter by workstream');
  const options = [['', 'All workstreams'], ['__project__', 'Project-wide'],
    ...tree.workstreams.filter(item => item.projectId === project.id).map(item => [item.id, item.title])];
  for (const [value, label] of options) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    scope.append(option);
  }
  scope.value = knowledgeWorkstream;
  scope.addEventListener('change', () => { knowledgeWorkstream = scope.value; renderKnowledgeCards(); });
  const sort = document.createElement('button');
  sort.type = 'button';
  sort.textContent = knowledgeRecentFirst ? 'Recent ↓' : 'Oldest ↑';
  sort.addEventListener('click', () => { knowledgeRecentFirst = !knowledgeRecentFirst; sort.textContent = knowledgeRecentFirst ? 'Recent ↓' : 'Oldest ↑'; renderKnowledgeCards(); });
  tools.append(scope, sort);
  controls.append(tools);
  section.append(controls);
  const grid = document.createElement('div');
  grid.className = 'knowledge-grid';
  section.append(grid);
  projectList.append(section);

  function renderKnowledgeCards() {
    grid.replaceChildren();
    const filtered = result.items.filter(item => {
      const typeMatches = knowledgeFilter === 'All' || (knowledgeFilter === 'media'
        ? ['image', 'audio', 'video'].includes(item.kind) : item.kind === knowledgeFilter);
      const taskMatches = !knowledgeTaskId || item.taskId === knowledgeTaskId;
      const scopeMatches = !knowledgeWorkstream || (knowledgeWorkstream === '__project__'
        ? !item.workstreamId : item.workstreamId === knowledgeWorkstream);
      const textMatches = !knowledgeQuery || [item.title, item.snippet, item.fileName, item.workstreamName, item.taskName]
        .some(value => String(value || '').toLocaleLowerCase().includes(knowledgeQuery));
      return taskMatches && typeMatches && scopeMatches && textMatches;
    }).sort((a, b) => knowledgeRecentFirst ? b.updatedAt - a.updatedAt : a.updatedAt - b.updatedAt);
    if (!filtered.length) {
      const empty = document.createElement('p');
      empty.className = 'knowledge-message';
      empty.textContent = visibleCount ? 'No knowledge matches these filters.' : scopedTask ? 'No knowledge saved on this task yet.' : 'No knowledge saved in this project yet.';
      grid.append(empty);
    }
    for (const item of filtered) {
      const card = document.createElement('article');
      card.className = `knowledge-card knowledge-${item.kind}`;
      const label = document.createElement('span');
      label.className = 'knowledge-type';
      label.textContent = item.fileKind === 'PDF' ? 'PDF' : item.kind.charAt(0).toUpperCase() + item.kind.slice(1);
      const name = document.createElement('h3');
      name.textContent = item.title;
      card.append(label, name);
      if (item.snippet && !['image', 'audio', 'video'].includes(item.kind)) {
        const snippet = document.createElement('p');
        snippet.className = 'knowledge-snippet';
        snippet.textContent = item.snippet;
        card.append(snippet);
      }
      if (item.kind === 'image') {
        const preview = document.createElement('p');
        preview.className = 'knowledge-media-placeholder';
        preview.textContent = 'Image preview needs secure file transfer';
        card.append(preview);
      }
      if (item.kind === 'checklist' && item.totalCount) {
        const progress = document.createElement('p');
        progress.className = 'knowledge-checklist';
        progress.textContent = `${item.completedCount} of ${item.totalCount} complete`;
        card.append(progress);
      }
      const meta = document.createElement('footer');
      const location = document.createElement('span');
      location.textContent = [item.workstreamName || 'Project-wide', item.taskName].filter(Boolean).join(' › ');
      const date = document.createElement('time');
      date.dateTime = new Date(item.updatedAt).toISOString();
      date.textContent = new Date(item.updatedAt).toLocaleDateString();
      meta.append(location, date);
      card.append(meta);
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      card.setAttribute('aria-label', `Open ${item.title}`);
      card.addEventListener('click', () => openKnowledgeItem(project.id, item, card));
      card.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openKnowledgeItem(project.id, item, card); }
      });
      grid.append(card);
    }
  }
  renderKnowledgeCards();
}

function renderProjectDetail(project, tree) {
  const allTasks = tree.tasks.filter(task => task.projectId === project.id);
  const streams = tree.workstreams.filter(item => item.projectId === project.id)
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const standalone = taskRoots(tree, 'projectId', project.id).filter(task => !task.workStreamId);
  const percent = Number.isFinite(project.progressPercent)
    ? Math.max(0, Math.min(100, project.progressPercent)) : null;

  if (projectDetailTab === 'Map') {
    const mapHistory = historyForMap(tree.deviceId, project.id);
    async function travelMapHistory(direction) {
      if (mapHistoryBusy || offlineMode) return;
      const entry = mapHistory.candidate(direction);
      if (!entry) return;
      mapHistoryBusy = true;
      try {
        if (entry.kind === 'appearance') {
          const value = direction === 'undo' ? entry.before : entry.after;
          const result = JSON.parse(await invoke('set_map_appearance', {
            projectId: project.id, nodeId: entry.nodeId, operation: entry.operation,
            color: entry.operation === 'color' ? value : null,
            width: entry.operation === 'width' ? value : null, key: crypto.randomUUID(),
            expectedColor: entry.operation === 'color' ?
              (direction === 'undo' ? entry.after : entry.before) : null,
            expectedWidth: entry.operation === 'width' ?
              (direction === 'undo' ? entry.after : entry.before) : null,
            hasExpected: true,
          }));
          if (result.type !== 'map_appearance') throw new Error(result.code || 'Edit was refused');
          activeMapView?.updateAppearance(result);
        } else {
          const state = JSON.parse(await invoke('map_appearance', { projectId: project.id }));
          if (state.type !== 'map_appearance') throw new Error(state.code || 'Phone unavailable');
          const targetParentId = direction === 'undo' ? entry.inverseParentId : entry.targetParentId;
          const insertBeforeId = direction === 'undo' ? entry.inverseBeforeId : entry.beforeId;
          const result = JSON.parse(await invoke('map_place', {
            projectId: project.id, sourceId: entry.sourceId, targetParentId,
            insertBeforeId, operation: 'MOVE', expectedRevision: state.hierarchyRevision,
            key: crypto.randomUUID(),
          }));
          if (result.type !== 'map_placed') throw new Error(result.detail || result.code || 'Move was refused');
          await loadProjects();
        }
        mapHistory.confirm(direction);
        activeMapView?.updateHistory(mapHistory.canUndo, mapHistory.canRedo);
      } catch (error) { workspaceStatus.textContent = `Undo/Redo was not applied: ${String(error)}`; }
      finally { mapHistoryBusy = false; }
    }
    const mapView = renderProjectMap(project, tree, {
      initialState: savedMapUi?.projectId === project.id ? savedMapUi : null,
      canUndo: mapHistory.canUndo, canRedo: mapHistory.canRedo,
      onUndo: () => travelMapHistory('undo'), onRedo: () => travelMapHistory('redo'),
      onBack: () => { projectDetailTab = 'Overview'; renderProjects(tree); },
      onCreate: beginCreate,
      onBulk: beginBulk,
      readOnly: offlineMode,
      onAppearanceEdit: async (node, operation, value, before) => {
        if (offlineMode) throw new Error('Reconnect your phone to edit.');
        const result = JSON.parse(await invoke('set_map_appearance', {
          projectId: project.id, nodeId: node.id, operation,
          color: operation === 'color' ? value : null,
          width: operation === 'width' ? value : null,
          expectedColor: operation === 'color' ? before : null,
          expectedWidth: operation === 'width' ? before : null,
          hasExpected: true, key: crypto.randomUUID(),
        }));
        if (result.v !== 1 || result.type !== 'map_appearance')
          throw new Error(result.code || 'Phone did not confirm the map edit');
        if (value !== before) {
          mapHistory.record({ kind: 'appearance', nodeId: node.id, operation, before, after: value });
          mapView.updateHistory(mapHistory.canUndo, mapHistory.canRedo);
        }
        return result;
      },
      onPlace: async (source, target, operation, beforeId) => {
        if (offlineMode) throw new Error('Reconnect your phone to edit.');
        const revision = mapView.hierarchyRevision();
        if (typeof revision !== 'string' || !/^-?\d+$/.test(revision))
          throw new Error('Map is still loading. Try again.');
        const result = JSON.parse(await invoke('map_place', {
          projectId: project.id, sourceId: source.id, targetParentId: target.id,
          insertBeforeId: beforeId, operation, expectedRevision: revision,
          key: crypto.randomUUID(),
        }));
        if (result.v !== 1 || result.type !== 'map_placed')
          throw new Error(result.detail || result.code || 'Phone did not confirm the move');
        if (operation === 'MOVE') mapHistory.record({ kind: 'move', sourceId: source.id,
          targetParentId: target.id, beforeId,
          inverseParentId: result.previousParentTaskId || result.previousWorkStreamId || project.id,
          inverseBeforeId: result.previousInsertBeforeId || null });
        else mapHistory.clear();
        await loadProjects();
        return result;
      },
      onDuplicate: async node => {
        const result = JSON.parse(await invoke('task_action', {
          action: 'duplicate', taskId: node.id, title: null, preference: null,
          key: crypto.randomUUID(),
        }));
        if (result.v !== 1 || result.type !== 'task_updated' || result.action !== 'duplicate')
          throw new Error(result.code || 'Phone did not confirm the duplicate');
        mapHistory.clear();
        await loadProjects();
      },
      onDelete: async (node, confirmTitle) => {
        if (offlineMode) throw new Error('Reconnect your phone to edit.');
        const result = JSON.parse(await invoke('map_delete', {
          projectId: project.id, nodeId: node.id,
          kind: node.kind === 'stream' ? 'workstream' : node.kind,
          confirmTitle, key: crypto.randomUUID(),
        }));
        if (result.v !== 1 || result.type !== 'map_deleted')
          throw new Error(result.code || 'Phone did not confirm deletion');
        mapHistory.clear();
        if (node.kind === 'project') { selectedProjectId = null; projectDetailTab = 'Overview'; }
        await loadProjects();
      },
      onRename: async (node, title) => {
        if (offlineMode) throw new Error('Reconnect your phone to edit.');
        const result = JSON.parse(await invoke('map_rename', {
          projectId: project.id, nodeId: node.id,
          kind: node.kind === 'stream' ? 'workstream' : node.kind,
          title, key: crypto.randomUUID(),
        }));
        if (result.v !== 1 || result.type !== 'map_renamed')
          throw new Error(result.code || 'Phone did not confirm the title');
        await loadProjects();
      },
      onOpen: node => {
        if (node.kind === 'knowledge') { openProjectKnowledge(project.id); return; }
        if (node.kind === 'project') { projectDetailTab = 'Overview'; renderProjects(tree); return; }
        if (node.kind === 'stream') { projectDetailTab = 'Overview'; openTaskLocation('workstream', node.id, node.title); }
        else if (node.kind === 'task') {
          projectDetailTab = 'Overview';
          const item = tree.tasks.find(task => task.id === node.id);
          if (!item) return;
          const path = [];
          const seen = new Set();
          let current = item;
          while (current && !seen.has(current.id)) {
            seen.add(current.id);
            path.unshift(current.id);
            current = tree.tasks.find(task => task.id === current.parentTaskId);
          }
          if (item.workStreamId) {
            const stream = tree.workstreams.find(entry => entry.id === item.workStreamId);
            if (stream) openTaskLocation('workstream', stream.id, stream.title, path);
          } else openTaskLocation('project', project.id, project.title, path);
        }
      },
    });
    projectList.append(mapView);
    activeMapView = mapView;
    if (!offlineMode) invoke('map_appearance', { projectId: project.id }).then(raw => {
      if (mapView.isConnected) mapView.updateAppearance(JSON.parse(raw));
    }).catch(() => {});
    return;
  }

  if (selectedTaskLocation) {
    const scope = selectedTaskLocation;
    if (scope.type === 'workstream' && !streams.some(item => item.id === scope.id)) selectedTaskLocation = null;
    else {
      scope.render = () => renderProjects(tree);
      projectList.append(renderTaskHierarchy({ tree, project, scope, state: scope,
        onBack: () => {
          if (scope.path.length) scope.path.pop();
          else selectedTaskLocation = null;
          renderProjects(tree);
        },
        onCreate: beginCreate,
        onBulk: beginBulk,
        onKnowledge: taskId => openProjectKnowledge(project.id, taskId, scope),
        onReorder: reorderPhoneTask,
        onTaskAction: updatePhoneTask,
        onFocusPreview: previewPhoneFocus,
        onFocusStart: startPhoneFocus,
      }));
      return;
    }
  }

  const summary = document.createElement('div');
  summary.className = 'project-progress-strip';
  const progress = document.createElement('strong');
  progress.textContent = percent === null ? '—' : `${percent}%`;
  const track = document.createElement('div');
  track.className = 'project-progress-track';
  track.setAttribute('role', 'progressbar');
  track.setAttribute('aria-label', 'Project progress');
  track.setAttribute('aria-valuemin', '0');
  track.setAttribute('aria-valuemax', '100');
  if (percent !== null) track.setAttribute('aria-valuenow', String(percent));
  const fill = document.createElement('span');
  fill.style.width = `${percent ?? 0}%`;
  track.append(fill);
  const description = document.createElement('span');
  description.textContent = `${allTasks.length} task${allTasks.length === 1 ? '' : 's'} · ${streams.length} workstream${streams.length === 1 ? '' : 's'}`;
  summary.append(progress, track, description);
  projectList.append(summary);

  const tabs = document.createElement('div');
  tabs.className = 'project-detail-tabs';
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', 'Project sections');
  for (const title of ['Overview', 'Knowledge', 'Map']) {
    const tab = document.createElement('button');
    tab.type = 'button';
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', String(projectDetailTab === title));
    tab.textContent = title === 'Map' ? 'Mind map' : title;
    tab.addEventListener('click', () => {
      if (projectDetailTab === title) return;
      if (title === 'Knowledge') { openProjectKnowledge(project.id); return; }
      projectDetailTab = title;
      renderProjects(tree);
    });
    tabs.append(tab);
  }
  projectList.append(tabs);

  if (projectDetailTab === 'Knowledge') {
    renderKnowledge(project, tree);
    return;
  }
  const workstreams = detailSection('Workstreams', streams.length, [
    projectAction('Mind map', () => { projectDetailTab = 'Map'; renderProjects(tree); }),
    projectAction('+ Workstream', () => beginCreate('workstream', 'project', project.id)),
    projectAction('Bulk', () => beginBulk('workstreams', 'project', project.id)),
  ]);
  const grid = document.createElement('div');
  grid.className = 'project-workstream-grid';
  for (const stream of streams) {
    const streamTasks = tree.tasks.filter(task => task.workStreamId === stream.id);
    const row = document.createElement('button');
    row.className = 'project-directory-row';
    row.type = 'button';
    row.setAttribute('aria-label', `Open tasks in ${stream.title}`);
    row.addEventListener('click', () => openTaskLocation('workstream', stream.id, stream.title));
    const icon = document.createElement('span');
    icon.className = 'project-directory-icon workstream-state-icon';
    const visual = workstreamVisual(stream, streamTasks);
    icon.dataset.visual = visual.kind;
    icon.innerHTML = workstreamIconMarkup(visual.kind);
    icon.setAttribute('aria-hidden', 'true');
    const names = document.createElement('span');
    names.className = 'project-directory-names';
    const name = document.createElement('strong');
    name.textContent = stream.title;
    const meta = document.createElement('small');
    meta.textContent = `${streamTasks.length} task${streamTasks.length === 1 ? '' : 's'}`;
    names.append(name, meta);
    const state = document.createElement('span');
    state.className = 'project-directory-state';
    state.dataset.state = visual.kind;
    state.textContent = visual.count ? `${visual.label} · ${visual.count}` : visual.label;
    row.setAttribute('aria-label', `Open tasks in ${stream.title}, ${visual.label.toLowerCase()}`);
    const arrow = document.createElement('span');
    arrow.className = 'project-directory-arrow';
    arrow.textContent = '›';
    arrow.setAttribute('aria-hidden', 'true');
    row.append(icon, names, state, arrow);
    grid.append(row);
  }
  if (!streams.length) {
    const empty = document.createElement('p');
    empty.className = 'project-detail-empty';
    empty.textContent = 'No workstreams yet. Add one to organize this project.';
    grid.append(empty);
  }
  workstreams.append(grid);
  projectList.append(workstreams);

  const pages = detailSection('Project pages', null);
  const destinations = document.createElement('div');
  destinations.className = 'project-directory-pages';
  for (const entry of [
    { title: 'Standalone tasks', description: `${standalone.length} project-level task${standalone.length === 1 ? '' : 's'}`, icon: '☷', open: () => openTaskLocation('project', project.id, project.title) },
    { title: 'Project knowledge', description: 'Files, notes, media and more', icon: '▤', open: () => openProjectKnowledge(project.id) },
  ]) {
    const row = document.createElement('button');
    row.className = 'project-directory-row project-directory-page';
    row.type = 'button';
    row.setAttribute('aria-label', `Open ${entry.title}`);
    row.addEventListener('click', entry.open);
    const icon = document.createElement('span');
    icon.className = 'project-directory-icon project-directory-page-icon';
    icon.textContent = entry.icon;
    icon.setAttribute('aria-hidden', 'true');
    const names = document.createElement('span');
    names.className = 'project-directory-names';
    const name = document.createElement('strong');
    name.textContent = entry.title;
    const meta = document.createElement('small');
    meta.textContent = entry.description;
    names.append(name, meta);
    const arrow = document.createElement('span');
    arrow.className = 'project-directory-arrow';
    arrow.textContent = '›';
    arrow.setAttribute('aria-hidden', 'true');
    row.append(icon, names, arrow);
    destinations.append(row);
  }
  pages.append(destinations);
  projectList.append(pages);
}

function renderProjects(tree) {
  if (activeMapView?.isConnected) savedMapUi = activeMapView.snapshotUi();
  activeMapView = null;
  projectList.replaceChildren();
  const heading = document.querySelector('#workspace-heading');
  const project = tree.projects.find(item => item.id === selectedProjectId);
  let mapConnection = document.querySelector('#map-connection-status');
  if (!mapConnection) {
    mapConnection = document.createElement('span');
    mapConnection.id = 'map-connection-status';
    document.querySelector('.directory-header-actions').append(mapConnection);
  }
  mapConnection.hidden = !project || projectDetailTab !== 'Map';
  mapConnection.textContent = offlineMode ? '● Read-only offline copy' : '● Live from phone';
  renderOfflineHome();
  const palette = Number.isInteger(project?.paletteMain) ? (project.paletteMain >>> 0) : null;
  workspace.style.setProperty('--project-code-accent', palette === null ? '#087d5c' : `#${(palette & 0xffffff).toString(16).padStart(6, '0')}`);
  if (!project || projectDetailTab !== 'Knowledge') closeKnowledgePage(false);
  if (project && selectedTaskLocation) {
    const scope = selectedTaskLocation;
    const validScope = scope.type === 'project' ? scope.id === project.id
      : scope.type === 'workstream' && tree.workstreams.some(item => item.id === scope.id && item.projectId === project.id);
    if (!validScope) selectedTaskLocation = null;
    else {
      const validPath = [];
      for (const id of scope.path) {
        const parentId = validPath.at(-1) || null;
        const task = tree.tasks.find(item => item.id === id && item.projectId === project.id &&
          (item.workStreamId || null) === (scope.type === 'workstream' ? scope.id : null) &&
          (item.parentTaskId || null) === parentId);
        if (!task) break;
        validPath.push(id);
      }
      scope.path = validPath;
    }
  }
  directoryControls.hidden = Boolean(project);
  const addContext = creationContext(project?.id || null, projectDetailTab, selectedTaskLocation);
  contextAdd.hidden = Boolean(project) ? !addContext || offlineMode : offlineMode;
  if (!project) {
    contextAdd.textContent = offlineCopies.some(item => item.deviceId === tree.deviceId)
      ? 'Update offline copy' : 'Create offline copy';
    contextAdd.setAttribute('aria-label', contextAdd.textContent);
  } else if (addContext) {
    contextAdd.textContent = addContext.label;
    contextAdd.setAttribute('aria-label', addContext.showProjectKinds
      ? 'Add to project' : `Create ${addContext.label.replace('＋ ', '').toLowerCase()}`);
  }
  bulkProjects.hidden = Boolean(project);
  bulkProjects.closest('.directory-utility').hidden = Boolean(project);
  projectTotal.hidden = Boolean(project);
  projectBreadcrumb.hidden = !project;
  projectSubtitle.hidden = !project;
  if (!project) {
    selectedProjectId = null;
    heading.textContent = 'Secure';
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
        ? offlineMode ? 'No projects in this offline copy.' : 'No projects on your phone yet.'
        : 'No projects match this search and filter.';
      projectList.append(empty);
    }
    renderOfflineHome();
    return;
  }
  heading.textContent = project.title;
  projectBreadcrumbName.textContent = project.title;
  projectSubtitle.textContent = projectDetailTab === 'Map' ? 'Your project, connected.'
    : project.description || 'Your work, organized for desktop.';
  renderProjectDetail(project, tree);
  renderOfflineHome();
}

async function loadProjects() {
  refreshProjects.disabled = true;
  workspaceStatus.textContent = offlineMode ? 'Opening the read-only copy on this computer…' : 'Reading projects from your phone…';
  try {
    const tree = JSON.parse(await invoke('project_tree'));
    if (tree.type === 'error') throw new Error(tree.code || 'Phone rejected project read');
    if (tree.v !== 1 || tree.type !== 'tree' || !Array.isArray(tree.projects) ||
        !Array.isArray(tree.workstreams) || !Array.isArray(tree.tasks)) throw new Error('Unsupported phone response');
    projectTree = tree;
    knowledgeCache.clear();
    renderProjects(tree);
    if (selectedProjectId && projectDetailTab === 'Knowledge') loadKnowledge(selectedProjectId);
    workspaceStatus.textContent = `${tree.projects.length} project${tree.projects.length === 1 ? '' : 's'} · ${offlineMode ? 'read-only copy on this computer' : 'stored on your phone'}`;
    if (!offlineMode && pendingOfflineRefresh && tree.deviceId &&
        offlineCopies.some(item => item.deviceId === tree.deviceId)) {
      pendingOfflineRefresh = false;
      captureOfflineCopy(true);
    }
  } catch (error) {
    workspaceStatus.textContent = `Could not load projects: ${String(error)}`;
  } finally { refreshProjects.disabled = false; }
}
refreshProjects.addEventListener('click', loadProjects);

let mapRefreshPending = false;
setInterval(async () => {
  const view = activeMapView;
  if (mapRefreshPending || offlineMode || projectDetailTab !== 'Map' || !view?.isConnected ||
      !selectedProjectId || !projectTree) return;
  mapRefreshPending = true;
  try {
    const fresh = JSON.parse(await invoke('project_tree'));
    if (!view.isConnected || view !== activeMapView) return;
    if (fresh.v !== 1 || fresh.type !== 'tree' || !Array.isArray(fresh.tasks))
      throw new Error(fresh.code || 'Phone did not return the project');
    const own = data => JSON.stringify({
      project: data.projects.find(item => item.id === selectedProjectId),
      streams: data.workstreams.filter(item => item.projectId === selectedProjectId),
      tasks: data.tasks.filter(item => item.projectId === selectedProjectId),
    });
    if (own(fresh) !== own(projectTree)) {
      historyForMap(fresh.deviceId, selectedProjectId).clear();
      projectTree = fresh;
      renderProjects(fresh);
    } else if (view.isConnected) {
      const appearance = JSON.parse(await invoke('map_appearance', { projectId: selectedProjectId }));
      if (view.isConnected) view.updateAppearance(appearance);
    }
    const indicator = document.querySelector('#map-connection-status');
    if (indicator) indicator.textContent = '● Live from phone';
  } catch (error) {
    const indicator = document.querySelector('#map-connection-status');
    if (indicator) indicator.textContent = '● Connection interrupted · Reconnect to edit';
    workspaceStatus.textContent = `Map connection interrupted: ${String(error)}`;
  } finally { mapRefreshPending = false; }
}, 3000);

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
    reconnectMessage.hidden = true;
    const offer = await invoke('start_pairing', { address: addresses.value });
    drawQr(offer.rows);
    expiry.textContent = `Expires at ${new Date(offer.expires_at * 1000).toLocaleTimeString()} · ${offer.address}`;
    qrWrap.hidden = false;
    qrPlaceholder.hidden = true;
    expiry.hidden = false;
    cancel.hidden = false;
    pairingActive = true;
    setStatus('Waiting for approval on your phone…');
  } catch (error) { setStatus(String(error), true); start.disabled = false; }
});
cancel.addEventListener('click', async () => {
  if (knowledgeEditing?.dirty || knowledgeBusy || composerBusy) {
    workspaceStatus.textContent = 'Finish or save the current change before disconnecting. This version cannot save it offline.';
    return;
  }
  try { await invoke('cancel_pairing'); } catch (error) { setStatus(String(error), true); return; }
  qrWrap.hidden = true;
  qrPlaceholder.hidden = false;
  expiry.hidden = true;
  cancel.hidden = true;
  pairingActive = false;
  wasConnected = false;
  projectTree = null;
  knowledgeCapabilities = null;
  closeKnowledgePage(false);
  setStatus('Disconnected. You can create a fresh invitation.');
  reconnectMessage.hidden = false;
  reconnectMessage.textContent = 'Disconnected. Show a new pairing QR to reconnect to your phone.';
  if (offlineDeviceId) await openOfflineCopy();
  loadAddresses();
});
let wasConnected = false;
setInterval(async () => {
  if (cancel.hidden) return;
  try {
    const current = await invoke('pairing_status');
    const connected = current.startsWith('Connected');
    if (!connected && !current.startsWith('Waiting for phone approval')) {
      await invoke('cancel_pairing');
      qrWrap.hidden = true;
      qrPlaceholder.hidden = false;
      expiry.hidden = true;
      cancel.hidden = true;
      pairingActive = false;
      reconnectMessage.hidden = false;
      reconnectMessage.textContent = wasConnected
        ? 'The phone connection ended. Show a new pairing QR to reconnect. Unsaved edits were not sent to your phone.'
        : 'Pairing ended. Show a new pairing QR to try again.';
      setStatus('Disconnected. Ready to reconnect.');
      wasConnected = false;
      if (offlineDeviceId) {
        if (knowledgeEditing?.dirty) {
          offlineMode = true;
          setStatus('Offline copy · Unsaved note still in memory');
          knowledgeEditorFeedback.textContent = 'Connection lost. This unsaved note remains in memory; reconnect this phone before leaving or saving.';
        } else await openOfflineCopy();
      }
      await loadAddresses();
      return;
    }
    setStatus(current);
    if (connected && !wasConnected) {
      offlineMode = false;
      pendingOfflineRefresh = true;
      document.body.classList.remove('offline-readonly');
      loadProjects();
    }
    wasConnected = connected;
  } catch (error) { setStatus(String(error), true); }
}, 1000);
loadAddresses();
refreshOfflineCopies().then(() => {
  if (offlineDeviceId && !wasConnected && !status.textContent.startsWith('Connected')) openOfflineCopy();
}).catch(error => { console.error('Offline copy unavailable', error); });
setInterval(() => { if (method === 'usb-network') loadAddresses(); }, 5000);
