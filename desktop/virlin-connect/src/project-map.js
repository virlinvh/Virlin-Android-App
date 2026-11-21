import './project-map.css';
import { clampWidth, readMapLayout, moveBranch } from './map-layout.js';

const MAX_NODES = 600;
const MAP_PALETTES = [
  [0xff24a46b, 0xff618ee1, 0xffd97867, 0xff9a70cd, 0xffe0a73a, 0xff48a9ad],
  [0xff70a985, 0xff698bbd, 0xffb78fa9, 0xffd9a57e, 0xff8d9e76, 0xff8b80b6],
  [0xff238b78, 0xff6689a8, 0xffd47882, 0xffa885b8, 0xffbb9957, 0xff658f77],
  [0xff78a85d, 0xff658fce, 0xffdf8774, 0xff846fbd, 0xffd9b45c, 0xff52a7a5],
  [0xffe76f51, 0xfff4a261, 0xffe9c46a, 0xff2a9d8f, 0xff264653, 0xfff28482],
  [0xff0077b6, 0xff00b4d8, 0xff023e8a, 0xff48cae4, 0xff0096c7, 0xff5e60ce],
  [0xffff6b9a, 0xffffb84c, 0xff5dbb7a, 0xff5dadec, 0xffb388eb, 0xffff8c61],
  [0xff2d6a4f, 0xff52b788, 0xff40916c, 0xff74a57f, 0xff1b4332, 0xff95b46a],
  [0xff7b2cbf, 0xffc77dff, 0xfff72585, 0xff9d4edd, 0xff5a189a, 0xffe07bb5],
  [0xfff4a6a6, 0xfff7c59f, 0xffe9d985, 0xffa8d5ba, 0xff9ad1d4, 0xffb8a9e3],
  [0xffe63946, 0xff1d3557, 0xff457b9d, 0xfff1a208, 0xff2a9d8f, 0xff8338ec],
  [0xffbc4749, 0xffdda15e, 0xff606c38, 0xffbc6c25, 0xff283618, 0xffa98467],
  [0xff212529, 0xff495057, 0xff6c757d, 0xff343a40, 0xff868e96, 0xffadb5bd],
];

function button(label, glyph, action, className = '') {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = className;
  element.textContent = glyph;
  element.title = label;
  element.setAttribute('aria-label', label);
  if (action) element.addEventListener('click', action);
  return element;
}

export function buildMapTree(project, tree) {
  const root = { key: `project:${project.id}`, id: project.id, kind: 'project', title: project.title, children: [] };
  const streams = tree.workstreams.filter(item => item.projectId === project.id)
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const tasks = tree.tasks.filter(item => item.projectId === project.id);
  const byParent = new Map();
  for (const task of tasks) {
    const parent = task.parentTaskId ? `task:${task.parentTaskId}`
      : task.workStreamId ? `stream:${task.workStreamId}` : root.key;
    if (!byParent.has(parent)) byParent.set(parent, []);
    byParent.get(parent).push(task);
  }
  for (const siblings of byParent.values()) siblings.sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  const used = new Set();
  let count = 1;
  function attach(parent, ancestors = new Set()) {
    if (count >= MAX_NODES) return;
    for (const task of byParent.get(parent.key) || []) {
      const key = `task:${task.id}`;
      if (used.has(key) || ancestors.has(key) || count >= MAX_NODES) continue;
      used.add(key);
      const node = { key, id: task.id, kind: 'task', title: task.title, status: task.status, children: [] };
      parent.children.push(node);
      count += 1;
      attach(node, new Set([...ancestors, key]));
    }
  }
  for (const stream of streams) {
    if (count >= MAX_NODES) break;
    const node = { key: `stream:${stream.id}`, id: stream.id, kind: 'stream', title: stream.title,
      status: stream.effectiveState || stream.state, children: [] };
    root.children.push(node);
    count += 1;
    attach(node);
  }
  attach(root);
  root.truncated = count >= MAX_NODES;
  return root;
}

export function renderProjectMap(project, tree, { onBack, onCreate, onBulk, onOpen, onRename,
  onAppearanceEdit, onPlace, onDuplicate, onDelete, onUndo, onRedo,
  canUndo = false, canRedo = false, initialState = null, readOnly = false }) {
  const root = buildMapTree(project, tree);
  const view = document.createElement('section');
  view.className = 'virlin-map';
  view.setAttribute('aria-label', `${project.title} mind map`);
  const tabs = document.createElement('div');
  tabs.className = 'project-detail-tabs virlin-map-tabs';
  tabs.setAttribute('role', 'tablist');
  for (const [label, active, action] of [['Overview', false, onBack], ['Knowledge', false, () => onOpen({ kind: 'knowledge' })], ['Mind map', true, null]]) {
    const tab = button(label, label, action);
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', String(active));
    tabs.append(tab);
  }
  const stage = document.createElement('div');
  stage.className = 'virlin-map-stage';
  stage.tabIndex = 0;
  stage.setAttribute('aria-label', 'Mind map. Drag a selected box onto a destination to move it on your phone. Arm Resize to drag its right edge. Drag empty space to pan.');
  const world = document.createElement('div');
  world.className = 'virlin-map-world';
  const lines = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  lines.classList.add('virlin-map-lines');
  const boxes = document.createElement('div');
  world.append(lines, boxes);
  stage.append(world);
  const status = document.createElement('div');
  status.className = 'virlin-map-feedback';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  view.append(tabs, stage, status);

  const iconPaths = {
    shapes: '<path d="m12 3 4 7H8z"/><rect x="3" y="14" width="7" height="7" rx="1"/><circle cx="17" cy="17.5" r="3.5"/>',
    expand: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M3 3l6 6m12-6-6 6M3 21l6-6m12 6-6-6"/>',
    restore: '<path d="M3 9h6V3m12 6h-6V3M3 15h6v6m12-6h-6v6"/>',
    stack: '<path d="m12 3 9 5-9 5-9-5zM3 12l9 5 9-5M3 16l9 5 9-5"/>',
    edit: '<path d="m15 4 5 5M4 20l4-1L21 6l-4-4L4 15z"/>',
    branch: '<path d="M6 3v12a4 4 0 0 0 4 4h4M6 9h8m4-4v8m-4-4h8"/><circle cx="6" cy="3" r="2"/><circle cx="16" cy="19" r="2"/>',
    move: '<path d="M12 3v18M3 12h18M8 7l4-4 4 4M8 17l4 4 4-4M7 8l-4 4 4 4m10-8 4 4-4 4"/>',
    page: '<path d="M14 3H5v18h14V8zM14 3v6h5M8 13h8m-8 4h8"/>',
    open: '<path d="M14 3h7v7m0-7L10 14M10 5H3v16h16v-7"/>',
    palette: '<circle cx="8" cy="8" r=".7"/><circle cx="13" cy="6" r=".7"/><circle cx="17" cy="10" r=".7"/><path d="M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4 2 2 0 0 1 0-4h4a3 3 0 0 0 3-3c0-4-4-7-9-7Z"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4"/>',
    fold: '<path d="M6 4v7h8M3 16v4m11-9v9M3 11h11M17 6h6"/><circle cx="6" cy="3" r="1"/><circle cx="3" cy="21" r="1"/><circle cx="14" cy="21" r="1"/>',
    unfold: '<path d="M6 4v7h8M3 16v4m11-9v9M3 11h11M17 6h6m-3-3v6"/><circle cx="6" cy="3" r="1"/><circle cx="3" cy="21" r="1"/><circle cx="14" cy="21" r="1"/>',
    tasks: '<path d="M3 6l2 2 4-4m3 2h9M3 13l2 2 4-4m3 2h9M3 20l2 2 4-4m3 2h9"/>',
    plus: '<path d="M12 4v16M4 12h16"/>',
    minus: '<path d="M4 12h16"/>',
    fit: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    paste: '<rect x="4" y="5" width="16" height="16" rx="2"/><path d="M9 5V3h6v2m-6 7h6m-6 4h4"/>',
    duplicate: '<rect x="4" y="4" width="12" height="12" rx="2"/><path d="M8 19h11V8"/>',
    resize: '<path d="M3 12h18m-14-4-4 4 4 4m10-8 4 4-4 4"/>',
    delete: '<path d="M3 6h18M8 6V4h8v2m3 0-1 15H6L5 6m5 4v7m4-7v7"/>',
    undo: '<path d="M9 14 4 9l5-5M4 9h9a7 7 0 0 1 0 14"/>',
    redo: '<path d="m15 14 5-5-5-5m5 5h-9a7 7 0 0 0 0 14"/>',
  };
  function tool(label, icon, action, className = '') {
    const result = button(label, '', action, className);
    result.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconPaths[icon] || iconPaths.page}</svg>`;
    return result;
  }
  function findNode(node, key) {
    if (node.key === key) return node;
    for (const child of node.children) { const found = findNode(child, key); if (found) return found; }
    return null;
  }
  let selected = findNode(root, initialState?.selectedKey) || root;
  let scale = initialState?.scale || 1, tx = initialState?.tx || 0, ty = initialState?.ty || 0;
  let expanded = false, focused = findNode(root, initialState?.focusedKey), tasksOnly = Boolean(initialState?.tasksOnly);
  let clipboard = null;
  let phoneAppearance = { nodeColors: {}, nodeWidths: {}, branchColors: {}, paletteIndex: 0 };
  let rowSpacing = 115;
  let gesture = null, suppressClick = false, frame = 0;
  const folded = new Set(initialState?.folded || []);
  const key = `virlin-map-layout:v1:${tree.deviceId || 'unknown'}:${project.id}`;
  let layout = {};
  try { layout = readMapLayout(localStorage.getItem(key)); } catch { /* Storage can be disabled. */ }
  const positions = new Map();
  function notify(message) { status.textContent = message; }
  function saveLayout() {
    try { localStorage.setItem(key, JSON.stringify(layout)); notify('Desktop layout saved on this computer.'); }
    catch { notify('Layout is available for this session; local storage is unavailable.'); }
  }
  function transform() { world.style.transform = `translate(${tx}px,${ty}px) scale(${scale})`; }
  function zoom(factor, x = stage.clientWidth / 2, y = stage.clientHeight / 2) {
    const next = Math.min(2.5, Math.max(.12, scale * factor));
    tx = x - (x - tx) * next / scale; ty = y - (y - ty) * next / scale;
    scale = next; transform();
  }
  function fit() {
    if (!positions.size) return;
    const entries = [...positions.values()];
    const minX = Math.min(...entries.map(p => p.x)), minY = Math.min(...entries.map(p => p.y));
    const maxX = Math.max(...entries.map(p => p.x + p.w)), maxY = Math.max(...entries.map(p => p.y + p.h));
    scale = Math.min(1, Math.max(.12, Math.min((stage.clientWidth - 130) / (maxX - minX), (stage.clientHeight - 130) / (maxY - minY))));
    tx = (stage.clientWidth - (maxX - minX) * scale) / 2 - minX * scale;
    ty = (stage.clientHeight - (maxY - minY) * scale) / 2 - minY * scale;
    transform();
  }
  const launcher = tool('Selected box tools', 'shapes', () => {
    actions.hidden = !actions.hidden; launcher.setAttribute('aria-expanded', String(!actions.hidden));
  }, 'virlin-map-launcher');
  launcher.setAttribute('aria-expanded', 'false');
  const actions = document.createElement('div');
  actions.className = 'virlin-map-node-actions'; actions.hidden = true;
  const expand = tool('Expand mind map', 'expand', () => setExpanded(!expanded), 'virlin-map-expand');
  function setExpanded(value) {
    expanded = value;
    view.classList.toggle('is-expanded', expanded);
    expand.innerHTML = tool('', expanded ? 'restore' : 'expand').innerHTML;
    expand.setAttribute('aria-label', expanded ? 'Return to project page' : 'Expand mind map');
    expand.title = expand.getAttribute('aria-label');
    expand.setAttribute('aria-pressed', String(expanded));
    actions.hidden = true; tools.hidden = true;
    launcher.setAttribute('aria-expanded', 'false'); stack.setAttribute('aria-expanded', 'false');
    // Keep world coordinates, scale and selection; expanding never reloads phone data.
    requestAnimationFrame(transform);
  }
  const controls = document.createElement('div'); controls.className = 'virlin-map-controls';
  const tools = document.createElement('div'); tools.className = 'virlin-map-tools'; tools.hidden = true;
  const stack = tool('Map controls', 'stack', () => {
    tools.hidden = !tools.hidden; stack.setAttribute('aria-expanded', String(!tools.hidden));
  }, 'virlin-map-stack');
  stack.setAttribute('aria-expanded', 'false');
  controls.append(tools, stack); stage.append(launcher, actions, expand, controls);
  const edit = tool('Edit selected box', 'edit', editSelected);
  const add = tool('Create branch', 'branch', showAdd);
  const bulk = tool('Bulk add branches', 'plus', () => {
    if (readOnly) return;
    onBulk(selected.kind === 'project' ? 'workstreams' : selected.kind === 'stream' ? 'tasks' : 'subtasks',
      selected.kind === 'stream' ? 'workstream' : selected.kind, selected.id);
  });
  const move = tool('Move selected branch', 'move', () => showDestinations(selected, 'MOVE'));
  const copy = tool('Copy selected branch', 'copy', () => {
    clipboard = selected; paste.disabled = false; notify('Select a destination, then Paste.');
  });
  const paste = tool('Paste copied branch', 'paste', () => {
    if (clipboard) applyPlacement(clipboard, selected, 'COPY');
  });
  const duplicate = tool('Duplicate selected task', 'duplicate', async () => {
    if (readOnly || selected.kind !== 'task' || !onDuplicate) return;
    duplicate.disabled = true;
    try { await onDuplicate(selected); }
    catch (error) { notify(`Could not duplicate: ${String(error)}`); duplicate.disabled = false; }
  });
  const resizeTool = tool('Resize horizontally', 'resize', () => {
    stage.classList.toggle('resize-armed');
    resizeTool.setAttribute('aria-pressed', String(stage.classList.contains('resize-armed')));
  });
  const remove = tool('Delete selected box', 'delete', () => showDelete());
  const undo = tool('Undo', 'undo', () => onUndo?.());
  const redo = tool('Redo', 'redo', () => onRedo?.());
  const page = tool('Open box page', 'page', () => onOpen(selected));
  const open = tool('Open selected item', 'open', () => onOpen(selected));
  const colorStrip = document.createElement('div');
  colorStrip.className = 'virlin-map-color-strip'; colorStrip.hidden = true;
  colorStrip.setAttribute('aria-label', 'Box colors');
  const swatchColors = [0xff52a887, 0xff95bc7c, 0xff93c9c0, 0xff438fad, 0xff82a9de,
    0xff7582d1, 0xffac85d3, 0xffd28cc0, 0xffe58da6, 0xffd88474, 0xffedad82, 0xffe6c26c];
  const color = tool('Box color', 'palette', () => {
    if (readOnly) return;
    colorStrip.hidden = !colorStrip.hidden;
    color.setAttribute('aria-pressed', String(!colorStrip.hidden));
    updateSwatches();
  });
  function updateSwatches() {
    [...colorStrip.children].forEach((swatch, index) =>
      swatch.setAttribute('aria-pressed', String(phoneAppearance.nodeColors[selected.id] === swatchColors[index])));
    color.classList.toggle('has-box-color', phoneAppearance.nodeColors[selected.id] != null);
  }
  swatchColors.forEach((value, index) => {
    const swatch = button(`Box color ${index + 1}`, '', async () => {
      if (readOnly || !onAppearanceEdit) return;
      const next = phoneAppearance.nodeColors[selected.id] === value ? null : value;
      swatch.disabled = true;
      const before = phoneAppearance.nodeColors[selected.id] ?? null;
      try { view.updateAppearance(await onAppearanceEdit(selected, 'color', next, before)); }
      catch (error) { notify(`Color was not saved: ${String(error)}`); }
      finally { swatch.disabled = false; }
    });
    swatch.className = 'virlin-map-swatch';
    swatch.style.backgroundColor = `#${value.toString(16).slice(2)}`;
    colorStrip.append(swatch);
  });
  stage.append(colorStrip);
  actions.append(edit, add, move, copy, duplicate, paste, page, open, bulk,
    resizeTool, remove, undo, redo, color);
  view.updateHistory = (undoAvailable, redoAvailable) => {
    undo.disabled = readOnly || !undoAvailable;
    redo.disabled = readOnly || !redoAvailable;
  };
  view.updateHistory(canUndo, canRedo);
  function descendants(node, out = new Set()) {
    out.add(node.id); node.children.forEach(child => descendants(child, out)); return out;
  }
  function allNodes(node, depth = 0, out = []) {
    out.push({ node, depth }); node.children.forEach(child => allNodes(child, depth + 1, out)); return out;
  }
  async function applyPlacement(source, target, operation, beforeId = null) {
    if (readOnly || !onPlace) return;
    try { await onPlace(source, target, operation, beforeId); }
    catch (error) { notify(`Could not ${operation === 'MOVE' ? 'move' : 'copy'}: ${String(error)}`); }
  }
  function showDestinations(source, operation) {
    if (readOnly || !onPlace) return;
    const overlay = document.createElement('div'); overlay.className = 'virlin-map-edit-overlay';
    const panel = document.createElement('div'); panel.className = 'virlin-map-edit virlin-map-destinations';
    const heading = document.createElement('h3'); heading.textContent = `Move ${source.title}`;
    panel.append(heading);
    const blocked = descendants(source);
    if (source.kind === 'stream') {
      panel.append(button('Move workstream to end', 'End of project',
        () => { overlay.remove(); applyPlacement(source, root, operation); }));
      for (const sibling of root.children.filter(item => item.kind === 'stream' && item.id !== source.id)) {
        panel.append(button(`Move before ${sibling.title}`, `Before ${sibling.title}`,
          () => { overlay.remove(); applyPlacement(source, root, operation, sibling.id); }));
      }
    } else {
      for (const { node, depth } of allNodes(root)) {
        if (blocked.has(node.id)) continue;
        panel.append(button(`Place inside ${node.title}`, `${'  '.repeat(depth)}${node.title}`,
          () => { overlay.remove(); applyPlacement(source, node, operation); }));
        for (const child of node.children.filter(item => item.kind === 'task' && !blocked.has(item.id))) {
          panel.append(button(`Place before ${child.title} in ${node.title}`,
            `${'  '.repeat(depth + 1)}Before ${child.title}`,
            () => { overlay.remove(); applyPlacement(source, node, operation, child.id); }));
        }
      }
    }
    panel.append(button('Close destinations', 'Cancel', () => overlay.remove()));
    overlay.append(panel); stage.append(overlay);
    overlay.addEventListener('click', event => { if (event.target === overlay) overlay.remove(); });
  }
  function showDelete() {
    if (readOnly || !onDelete) return;
    const target = selected;
    const overlay = document.createElement('div'); overlay.className = 'virlin-map-edit-overlay';
    const form = document.createElement('form'); form.className = 'virlin-map-edit';
    const title = document.createElement('h3'); title.textContent = `Permanently delete ${target.kind === 'stream' ? 'workstream' : target.kind}?`;
    const name = document.createElement('p'); name.textContent = target.title;
    const input = document.createElement('input'); input.placeholder = target.title;
    input.setAttribute('aria-label', `Type ${target.title} to confirm`);
    const actionsRow = document.createElement('div');
    const cancel = button('Cancel deletion', 'Cancel', () => overlay.remove());
    const confirm = button('Delete permanently', 'Delete permanently');
    confirm.type = 'submit'; confirm.disabled = true;
    input.addEventListener('input', () => { confirm.disabled = input.value !== target.title; });
    actionsRow.append(cancel, confirm); form.append(title, name, input, actionsRow);
    overlay.append(form); stage.append(overlay); input.focus();
    form.addEventListener('submit', async event => {
      event.preventDefault(); if (input.value !== target.title) return;
      confirm.disabled = cancel.disabled = true;
      try { await onDelete(target, input.value); overlay.remove(); }
      catch (error) { notify(`Delete was not confirmed: ${String(error)}`);
        confirm.disabled = cancel.disabled = false; }
    });
  }
  function select(node) {
    selected = node;
    for (const box of boxes.children) box.classList.toggle('selected', box.dataset.key === node.key);
    edit.disabled = readOnly || !onRename;
    edit.title = 'Edit selected box';
    add.disabled = bulk.disabled = readOnly;
    move.disabled = readOnly || node.kind === 'project' || !onPlace;
    copy.disabled = duplicate.disabled = readOnly || node.kind !== 'task';
    paste.disabled = readOnly || !clipboard || node.id === clipboard.id;
    color.disabled = readOnly || !onAppearanceEdit;
    resizeTool.disabled = readOnly || !onAppearanceEdit;
    remove.disabled = readOnly || !onDelete;
    updateSwatches();
    launcher.setAttribute('aria-label', `Tools for ${node.title}`);
  }
  function showAdd() {
    if (readOnly) return;
    onCreate(selected.kind === 'project' ? 'workstream' : 'task', selected.kind === 'stream' ? 'workstream' : selected.kind, selected.id);
  }
  function editSelected() {
    if (readOnly || !onRename) return;
    const overlay = document.createElement('div'); overlay.className = 'virlin-map-edit-overlay';
    const form = document.createElement('form'); form.className = 'virlin-map-edit';
    const heading = document.createElement('h3'); heading.textContent = 'Edit box';
    const label = document.createElement('label'); label.textContent = 'Title';
    const input = document.createElement('input'); input.value = selected.title; input.maxLength = 200; input.required = true;
    label.append(input);
    const message = document.createElement('p'); message.setAttribute('role', 'status');
    const cancel = button('Cancel editing', 'Cancel', () => overlay.remove());
    const save = button('Save title to phone', 'Save to phone'); save.type = 'submit';
    form.append(heading, label, message, cancel, save); overlay.append(form); stage.append(overlay); input.focus();
    form.addEventListener('keydown', event => { if (event.key === 'Escape') { event.stopPropagation(); overlay.remove(); } });
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (!input.value.trim()) return;
      save.disabled = cancel.disabled = true;
      try { await onRename(selected, input.value.trim()); overlay.remove(); }
      catch (error) { message.textContent = `Save not confirmed: ${String(error)}`; save.disabled = cancel.disabled = false; }
    });
  }
  function collect(node, depth, parent, entries, counter) {
    const children = folded.has(node.key) ? [] : node.children;
    const childEntries = children.map(child => collect(child, depth + 1, node, entries, counter));
    const row = childEntries.length ? (childEntries[0].row + childEntries.at(-1).row) / 2 : counter.row++;
    const custom = layout[node.key] || {};
    const entry = { node, parent, depth, row, x: depth * 300 + 30 + (custom.x || 0),
      y: row * rowSpacing + 30 + (custom.y || 0), w: clampWidth(phoneAppearance.nodeWidths[node.id] ||
        custom.width || (node.kind === 'project' ? 230 : node.kind === 'task' ? 175 : 200)), h: 72 };
    entries.push(entry); return entry;
  }
  function paint() {
    boxes.replaceChildren(); lines.replaceChildren(); positions.clear();
    const palette = phoneAppearance.customPalette?.length ? phoneAppearance.customPalette :
      MAP_PALETTES[(phoneAppearance.paletteIndex || 0) % MAP_PALETTES.length];
    const boxColors = new Map();
    function assignColors(node, inherited, branch) {
      const branchColor = phoneAppearance.branchColors[node.id] ?? inherited ?? palette[branch % palette.length];
      boxColors.set(node.id, phoneAppearance.nodeColors[node.id] ?? branchColor);
      node.children.forEach((child, index) => assignColors(child, branchColor,
        node === root ? index : branch));
    }
    assignColors(root, null, 0);
    const entries = [];
    collect(focused || root, 0, null, entries, { row: 0 });
    for (const entry of entries) {
      const { node } = entry;
      const box = document.createElement('button'); box.type = 'button';
      box.className = `virlin-map-node virlin-map-${node.kind}`; box.dataset.key = node.key;
      const assigned = boxColors.get(node.id);
      if (Number.isInteger(assigned)) box.style.borderLeftColor =
        `#${(assigned & 0xffffff).toString(16).padStart(6, '0')}`;
      box.style.left = `${entry.x}px`; box.style.top = `${entry.y}px`; box.style.width = `${entry.w}px`;
      const caption = document.createElement('strong'); caption.textContent = node.title;
      const meta = document.createElement('small');
      meta.textContent = node.kind === 'project' ? `Project · ${node.children.length} branches` :
        node.kind === 'stream' ? `${node.children.length} tasks · ${(node.status || 'Ready').replaceAll('_', ' ')}` :
        (node.status || 'Task').replaceAll('_', ' ');
      box.append(caption, meta);
      const resize = document.createElement('span'); resize.className = 'virlin-map-resize';
      resize.setAttribute('aria-hidden', 'true'); box.append(resize);
      box.addEventListener('click', () => { if (!suppressClick) select(node); });
      boxes.append(box);
      entry.h = box.offsetHeight || 72; positions.set(node.key, entry);
      if (node.children.length) {
        const fold = button(folded.has(node.key) ? `Expand ${node.title}` : `Collapse ${node.title}`,
          folded.has(node.key) ? '+' : '−', () => { folded.has(node.key) ? folded.delete(node.key) : folded.add(node.key); paint(); },
          'virlin-map-fold');
        fold.style.left = `${entry.x + entry.w + 10}px`;
        fold.style.top = `${entry.y + entry.h / 2 - 13}px`;
        boxes.append(fold);
      }
    }
    const neededSpacing = Math.max(115, ...entries.map(entry => entry.h + 28));
    if (neededSpacing !== rowSpacing) { rowSpacing = neededSpacing; paint(); return; }
    for (const entry of entries) {
      const parent = entry.parent && positions.get(entry.parent.key);
      if (!parent) continue;
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      const x = parent.x + parent.w, y = parent.y + parent.h / 2, endY = entry.y + entry.h / 2;
      path.setAttribute('d', `M ${x} ${y} C ${x + 65} ${y}, ${entry.x - 65} ${endY}, ${entry.x} ${endY}`);
      lines.append(path);
    }
    select(selected); transform();
  }
  const controlItems = [
    ['Focus selected branch', 'lock', () => { focused = focused ? null : selected; paint(); fit(); }],
    ['Fold deepest level', 'fold', () => {
      const branches = [...positions.values()].filter(p => p.node.children.length && !folded.has(p.node.key));
      const deepest = Math.max(-1, ...branches.map(p => p.depth));
      branches.filter(p => p.depth === deepest).forEach(p => folded.add(p.node.key)); paint();
    }],
    ['Open next level', 'unfold', () => {
      [...positions.keys()].filter(key => folded.has(key)).forEach(key => folded.delete(key)); paint();
    }],
    ['Show tasks and their branches', 'tasks', () => {
      tasksOnly = !tasksOnly;
      view.classList.toggle('tasks-only', tasksOnly);
      tools.querySelector('[aria-label="Show tasks and their branches"]').setAttribute('aria-pressed', String(tasksOnly));
      notify(tasksOnly ? 'Task branches highlighted.' : 'All branches shown.');
    }],
    ['Zoom in', 'plus', () => zoom(1.2)],
    ['Zoom out', 'minus', () => zoom(1 / 1.2)],
    ['Fit to screen', 'fit', fit],
    ['Enter full canvas', 'expand', () => setExpanded(true)],
  ];
  for (const [label, icon, action] of controlItems) tools.append(tool(label, icon, action));
  stage.addEventListener('pointerdown', event => {
    if (event.button !== 0 || event.target.closest('.virlin-map-edit-overlay')) return;
    const box = event.target.closest('.virlin-map-node');
    if (!box && event.target.closest('button')) return;
    if (box) {
      const entry = positions.get(box.dataset.key); select(entry.node);
      if (readOnly) return;
      gesture = { kind: event.target.closest('.virlin-map-resize') && stage.classList.contains('resize-armed') ? 'resize' : 'node', node: entry.node, width: entry.w,
        x: event.clientX, y: event.clientY, lastX: event.clientX, lastY: event.clientY,
        moved: false, snapshot: JSON.stringify(layout), appearanceSnapshot: JSON.stringify(phoneAppearance) };
    } else gesture = { kind: 'pan', x: event.clientX, y: event.clientY, lastX: event.clientX, lastY: event.clientY, moved: false };
    stage.setPointerCapture(event.pointerId); event.preventDefault();
  });
  stage.addEventListener('pointermove', event => {
    if (!gesture) return;
    const g = gesture;
    if (!g.moved && Math.hypot(event.clientX - g.x, event.clientY - g.y) < 4) return;
    g.moved = true; suppressClick = true;
    const dx = (event.clientX - g.lastX) / scale, dy = (event.clientY - g.lastY) / scale;
    g.lastX = event.clientX; g.lastY = event.clientY;
    if (g.kind === 'pan') { tx += dx * scale; ty += dy * scale; transform(); }
    else {
      if (g.kind === 'resize') phoneAppearance.nodeWidths[g.node.id] =
        clampWidth(g.width + (event.clientX - g.x) / scale);
      else moveBranch(layout, g.node, dx, dy);
      stage.classList.add('is-manipulating');
      cancelAnimationFrame(frame); frame = requestAnimationFrame(paint);
    }
  });
  function finish(cancel = false, event = null) {
    if (!gesture) return;
    cancelAnimationFrame(frame);
    const completed = gesture;
    let dropTarget = null;
    if (completed.kind === 'node' && completed.moved && !cancel && event) {
      const rect = stage.getBoundingClientRect();
      const x = (event.clientX - rect.left - tx) / scale;
      const y = (event.clientY - rect.top - ty) / scale;
      const blocked = descendants(completed.node);
      dropTarget = [...positions.values()].filter(entry => !blocked.has(entry.node.id) &&
        x >= entry.x && x <= entry.x + entry.w && y >= entry.y && y <= entry.y + entry.h &&
        (completed.node.kind !== 'stream' || entry.node.kind === 'project'))
        .sort((a, b) => b.depth - a.depth)[0]?.node || null;
    }
    if (cancel && gesture.snapshot) layout = JSON.parse(gesture.snapshot);
    if (cancel && gesture.appearanceSnapshot) phoneAppearance = JSON.parse(gesture.appearanceSnapshot);
    if (gesture.kind === 'resize' && gesture.moved && !cancel) {
      const node = gesture.node, width = phoneAppearance.nodeWidths[node.id];
      const before = gesture.appearanceSnapshot;
      const oldWidth = JSON.parse(before).nodeWidths[node.id] ?? null;
      if (onAppearanceEdit) onAppearanceEdit(node, 'width', width, oldWidth)
        .then(value => view.updateAppearance(value))
        .catch(error => { phoneAppearance = JSON.parse(before); paint(); notify(`Width was not saved: ${String(error)}`); });
      else { phoneAppearance = JSON.parse(before); notify('Reconnect your phone to resize.'); }
    } else if (gesture.kind === 'node') {
      layout = JSON.parse(gesture.snapshot);
      if (dropTarget) applyPlacement(completed.node, dropTarget, 'MOVE');
      else if (completed.moved && !cancel) notify('Drop onto another box, or use Move to choose a destination.');
    }
    if (gesture.kind !== 'pan') paint();
    gesture = null; stage.classList.remove('is-manipulating');
    setTimeout(() => { suppressClick = false; }, 0);
  }
  stage.addEventListener('pointerup', event => finish(false, event));
  stage.addEventListener('pointercancel', () => finish(true));
  stage.addEventListener('lostpointercapture', () => finish(true));
  stage.addEventListener('click', event => {
    if (suppressClick) { event.preventDefault(); event.stopPropagation(); }
  }, true);
  stage.addEventListener('wheel', event => {
    event.preventDefault();
    if (event.shiftKey) { tx -= event.deltaY; transform(); }
    else {
      const rect = stage.getBoundingClientRect();
      zoom(event.deltaY < 0 ? 1.08 : 1 / 1.08, event.clientX - rect.left, event.clientY - rect.top);
    }
  }, { passive: false });
  stage.addEventListener('keydown', event => {
    if (event.target.matches('input,textarea')) return;
    if (event.key === 'Escape') { if (gesture) finish(true); else if (expanded) setExpanded(false); else { actions.hidden = tools.hidden = true; launcher.setAttribute('aria-expanded', 'false'); stack.setAttribute('aria-expanded', 'false'); } }
    else if (event.key === '+') zoom(1.2);
    else if (event.key === '-') zoom(1 / 1.2);
    else if (event.key === '0') fit();
    else if (event.altKey && ['ArrowLeft', 'ArrowRight'].includes(event.key)) {
      const previous = layout[selected.key] || {};
      const width = clampWidth((positions.get(selected.key)?.w || 200) +
        (event.key === 'ArrowRight' ? 20 : -20));
      if (!readOnly && onAppearanceEdit) onAppearanceEdit(selected, 'width', width,
        phoneAppearance.nodeWidths[selected.id] ?? null)
        .then(value => view.updateAppearance(value))
        .catch(error => notify(`Width was not saved: ${String(error)}`));
    } else return;
    event.preventDefault();
  });
  view.updateAppearance = value => {
    if (value?.type !== 'map_appearance' || value.projectId !== project.id) return;
    phoneAppearance = value;
    paint();
  };
  view.hierarchyRevision = () => phoneAppearance.hierarchyRevision;
  view.snapshotUi = () => ({ projectId: project.id, selectedKey: selected.key,
    focusedKey: focused?.key || null, folded: [...folded], scale, tx, ty, tasksOnly,
    expanded });
  paint();
  if (initialState?.expanded) setExpanded(true);
  requestAnimationFrame(() => { if (view.isConnected) initialState ? transform() : fit(); });
  return view;
}

