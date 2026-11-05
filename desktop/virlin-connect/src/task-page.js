import './task-page.css';

function element(tag, className, value) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (value !== undefined) node.textContent = value;
  return node;
}

function action(label, run, className = '') {
  const button = element('button', className, label);
  button.type = 'button';
  button.addEventListener('click', run);
  return button;
}

function sorted(items) { return [...items].sort((a, b) => (a.order ?? 0) - (b.order ?? 0) || String(a.id).localeCompare(String(b.id))); }
function terminal(task) { return task.status === 'DONE' || task.status === 'CANCELLED'; }
function displayState(task) {
  if (task.status === 'DONE') return 'DONE';
  if (task.status === 'CANCELLED') return 'CANCELLED';
  if (task.executionState && task.executionState !== 'READY') return task.executionState.replaceAll('_', ' ');
  return task.status === 'IN_PROGRESS' ? 'IN PROGRESS' : 'WORK';
}

export function renderTaskHierarchy({ tree, project, scope, state, onBack, onCreate, onBulk, onKnowledge, onReorder, onTaskAction, onFocusPreview, onFocusStart }) {
  const page = element('section', 'task-workspace');
  const tasks = tree.tasks.filter(task => task.projectId === project.id &&
    (scope.type === 'project' ? !task.workStreamId : task.workStreamId === scope.id));
  const byId = new Map(tasks.map(task => [task.id, task]));
  while (state.path.length && !byId.has(state.path.at(-1))) state.path.pop();
  const parent = byId.get(state.path.at(-1)) || null;
  const siblings = sorted(tasks.filter(task => parent ? task.parentTaskId === parent.id : !task.parentTaskId));
  const active = siblings.filter(task => task.status !== 'CANCELLED');
  const completed = active.filter(task => task.status === 'DONE').length;
  const percent = active.length ? Math.round(completed / active.length * 100) : 0;
  const childCount = id => tasks.filter(task => task.parentTaskId === id && task.status !== 'CANCELLED').length;

  const top = element('div', 'task-workspace-top');
  top.append(action('← Back', onBack, 'task-workspace-back'));
  const context = element('span', 'task-workspace-context', scope.title);
  top.append(context);
  page.append(top);

  const breadcrumbs = element('nav', 'task-workspace-breadcrumbs');
  breadcrumbs.setAttribute('aria-label', 'Task hierarchy');
  breadcrumbs.append(action(scope.title, () => { state.path = []; state.filter = 'All'; state.render(); }));
  state.path.forEach((id, index) => {
    breadcrumbs.append(element('span', '', '›'));
    breadcrumbs.append(action(byId.get(id)?.title || 'Task', () => {
      state.path = state.path.slice(0, index + 1);
      state.filter = 'All';
      state.render();
    }));
  });
  page.append(breadcrumbs);

  const title = element('h2', 'task-workspace-title', parent?.title || (scope.type === 'project' ? 'Standalone tasks' : 'Tasks'));
  page.append(title);
  const summary = element('div', 'task-workspace-summary');
  summary.append(element('span', '', `${percent}% · ${completed}/${active.length} complete`));
  const manage = action('Manage', () => {
    if (parent) showControls(parent);
    else showNotice(scope.type === 'project' ? 'Project task controls remain on the phone.' : 'Workstream controls remain on the phone.');
  });
  summary.append(manage);
  if (parent) summary.append(action('Knowledge', () => onKnowledge(parent.id), 'task-workspace-knowledge'));
  page.append(summary);

  const filters = element('div', 'task-workspace-filters');
  for (const name of ['All', 'Active', 'Done']) {
    const button = action(name, () => { state.filter = name; state.render(); });
    button.setAttribute('aria-pressed', String(state.filter === name));
    filters.append(button);
  }
  page.append(filters);

  const listHeader = element('div', 'task-workspace-list-header');
  listHeader.append(element('strong', '', parent ? 'Subtasks' : scope.type === 'project' ? 'Standalone tasks' : 'Tasks'));
  const organize = action(state.organizing ? 'Done organizing' : '↕ Organize', () => {
    state.organizing = !state.organizing;
    state.render();
  }, 'task-workspace-organize');
  organize.disabled = scope.type !== 'workstream' || siblings.length < 2;
  organize.title = scope.type !== 'workstream' ? 'Standalone task ordering is not connected yet'
    : siblings.length < 2 ? 'Add another task to organize this list' : '';
  listHeader.append(organize);
  page.append(listHeader);

  const list = element('div', 'task-workspace-list');
  const visible = siblings.filter(task => state.filter === 'All' ||
    (state.filter === 'Done' ? task.status === 'DONE' : task.status !== 'DONE' && task.status !== 'CANCELLED'));
  for (const task of visible) {
    const row = element('div', 'task-workspace-row');
    const control = action(task.status === 'DONE' ? '✓' : '☷', () => showControls(task), 'task-workspace-dial');
    control.setAttribute('aria-label', `Work controls for ${task.title}`);
    if (task.status === 'DONE') control.classList.add('is-done');
    const open = action('', () => { state.path.push(task.id); state.filter = 'All'; state.render(); }, 'task-workspace-row-open');
    const names = element('span', 'task-workspace-row-names');
    names.append(element('strong', '', task.title));
    const parts = [];
    const children = childCount(task.id);
    if (children) parts.push(`${children} subtask${children === 1 ? '' : 's'}`);
    if (task.effectiveExecution) parts.push(task.effectiveExecution.toLowerCase());
    if (parts.length) names.append(element('small', '', parts.join(' · ')));
    open.append(names);
    const status = element('span', 'task-workspace-status', displayState(task));
    if (task.status === 'DONE') status.classList.add('is-done');
    if (task.executionState === 'CHECK') status.classList.add('needs-review');
    open.append(status, element('span', 'task-workspace-chevron', '›'));
    row.append(control, open);
    if (state.organizing && scope.type === 'workstream') {
      const position = siblings.findIndex(item => item.id === task.id);
      const buttons = element('div', 'task-workspace-order-controls');
      const up = action('↑', () => onReorder(task.id,
        position > 1 ? siblings[position - 2].id : null, siblings.map(item => item.id)));
      up.setAttribute('aria-label', `Move ${task.title} up`);
      up.disabled = position === 0;
      const down = action('↓', () => onReorder(task.id,
        siblings[position + 1]?.id || null, siblings.map(item => item.id)));
      down.setAttribute('aria-label', `Move ${task.title} down`);
      down.disabled = position === siblings.length - 1;
      buttons.append(up, down);
      row.append(buttons);
    }
    list.append(row);
  }
  if (!visible.length) list.append(element('p', 'task-workspace-empty', siblings.length ? 'No matching tasks' : parent ? 'No subtasks yet' : 'No tasks here yet'));
  page.append(list);

  const add = action('+', () => {
    if (parent) onCreate('task', 'task', parent.id);
    else onCreate('task', scope.type === 'project' ? 'project' : 'workstream', scope.id);
  }, 'task-workspace-fab');
  add.setAttribute('aria-label', parent ? 'Add subtask' : 'Add task');
  page.append(add);

  const dialogLayer = element('div', 'task-workspace-dialog-layer');
  dialogLayer.hidden = true;
  const dialog = element('div', 'task-workspace-dialog');
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialogLayer.append(dialog);
  dialogLayer.addEventListener('click', event => { if (event.target === dialogLayer) dialogLayer.hidden = true; });
  page.append(dialogLayer);
  function openDialog(titleText) {
    dialog.replaceChildren();
    const header = element('div', 'task-workspace-dialog-header');
    header.append(element('h3', '', titleText), action('×', () => { dialogLayer.hidden = true; }, 'task-workspace-dialog-close'));
    dialog.append(header);
    dialogLayer.hidden = false;
  }
  function showNotice(message) {
    openDialog('Available on your phone');
    dialog.append(element('p', 'task-workspace-dialog-muted', message));
  }
  function confirmTaskAction(task, operation, question) {
    openDialog(question);
    dialog.append(element('p', 'task-workspace-dialog-muted', task.title));
    if (operation === 'complete' || operation === 'cancel') {
      const visited = new Set([task.id]);
      let frontier = [task.id];
      while (frontier.length) {
        frontier = tasks.filter(item => item.parentTaskId && frontier.includes(item.parentTaskId) && !visited.has(item.id))
          .map(item => item.id);
        frontier.forEach(id => visited.add(id));
      }
      const descendants = visited.size - 1;
      const effect = operation === 'cancel' ? 'cancelled and removed from active progress' : 'marked complete';
      dialog.append(element('p', 'task-workspace-dialog-muted', descendants
        ? `This task and ${descendants} nested subtask${descendants === 1 ? '' : 's'} will be ${effect} on your phone.`
        : `This task will be ${effect} on your phone.`));
    }
    const buttons = element('div', 'task-workspace-dialog-actions');
    buttons.append(action('Keep', () => { dialogLayer.hidden = true; }));
    buttons.append(action(operation === 'cancel' ? 'Cancel task' : operation === 'duplicate' ? 'Duplicate' : 'Complete', async () => {
      dialogLayer.hidden = true;
      await onTaskAction(operation, task.id);
    }, 'task-workspace-confirm'));
    dialog.append(buttons);
  }
  function renameTask(task) {
    openDialog('Edit name');
    const label = element('label', '', 'Task name');
    label.htmlFor = 'task-workspace-rename';
    const input = document.createElement('input');
    input.id = 'task-workspace-rename';
    input.value = task.title;
    input.maxLength = 200;
    dialog.append(label, input);
    const buttons = element('div', 'task-workspace-dialog-actions');
    buttons.append(action('Cancel', () => { dialogLayer.hidden = true; }));
    buttons.append(action('Save', async () => {
      if (!input.value.trim()) return;
      dialogLayer.hidden = true;
      await onTaskAction('rename', task.id, input.value.trim());
    }, 'task-workspace-confirm'));
    dialog.append(buttons);
    input.focus();
    input.select();
  }
  function chooseExecution(task) {
    openDialog('Who does this?');
    dialog.append(element('p', 'task-workspace-dialog-muted', task.title));
    for (const [value, label] of [['INHERIT', 'Use the nearest ancestor'], ['HUMAN', 'Do it yourself'], ['EXTERNAL', 'Performed outside']]) {
      const choice = action(`${task.executionPreference === value ? '● ' : '○ '}${label}`, async () => {
        dialogLayer.hidden = true;
        await onTaskAction('set_execution', task.id, null, value);
      }, 'task-workspace-choice');
      dialog.append(choice);
    }
  }
  async function previewFocus(task) {
    openDialog('Checking focus…');
    try {
      const preview = await onFocusPreview(task.id);
      openDialog('Focus now?');
      dialog.append(element('p', 'task-workspace-dialog-muted',
        preview.workItemTitle === task.title ? task.title : `${task.title} → ${preview.workItemTitle}`));
      if (preview.displacedTitle) dialog.append(element('p', 'task-workspace-dialog-muted',
        `Current focus “${preview.displacedTitle}” will stop when this begins.`));
      const buttons = element('div', 'task-workspace-dialog-actions');
      buttons.append(action('Keep current', () => { dialogLayer.hidden = true; }));
      buttons.append(action('Start focus', async () => {
        dialogLayer.hidden = true;
        try { await onFocusStart(task.id, preview); }
        catch (error) { showNotice(`Focus was not changed: ${String(error)}. Check your phone and retry.`); }
      }, 'task-workspace-confirm'));
      dialog.append(buttons);
    } catch (error) { showNotice(`Cannot focus this task: ${String(error)}`); }
  }
  function showControls(task) {
    openDialog('Work controls');
    dialog.append(element('strong', 'task-workspace-dialog-task', task.title));
    dialog.append(element('p', 'task-workspace-dialog-muted', `${scope.title} › ${task.title} · ${displayState(task)}`));
    const who = action('', () => chooseExecution(task), 'task-workspace-control-row task-workspace-control-row-button');
    who.append(element('strong', '', 'Who does this?'), element('small', '',
      `${task.effectiveExecution === 'EXTERNAL' ? 'External' : 'Human'}${task.executionPreference === 'INHERIT' ? ' · inherited' : ''}`));
    dialog.append(who);
    const timer = element('div', 'task-workspace-control-row');
    timer.append(element('strong', '', 'Timer display'), element('small', '', 'Set on your phone'));
    dialog.append(timer);
    const focus = action('Focus now', () => previewFocus(task), 'task-workspace-focus');
    if (!task.workStreamId || terminal(task) || childCount(task.id) > 0) {
      focus.disabled = true;
      focus.title = !task.workStreamId ? 'Standalone tasks are not focusable in the Android model'
        : terminal(task) ? 'Completed or cancelled tasks cannot be focused' : 'Select a leaf task to start focus';
    }
    dialog.append(focus);
    if (!terminal(task)) dialog.append(action('Mark as complete', () => confirmTaskAction(task, 'complete', 'Mark as complete?'), 'task-workspace-control-row-button'));
    const group = element('div', 'task-workspace-manage');
    group.append(element('strong', '', 'Manage'));
    group.append(action('Edit name', () => renameTask(task)));
    group.append(action('Add subtask', () => { dialogLayer.hidden = true; onCreate('task', 'task', task.id); }));
    const move = action('Move to…', () => {});
    move.disabled = true;
    move.title = 'Moving tasks from Windows is not connected yet';
    group.append(move);
    group.append(action('Duplicate', () => confirmTaskAction(task, 'duplicate', 'Duplicate task?')));
    if (!terminal(task)) group.append(action('Cancel task', () => confirmTaskAction(task, 'cancel', 'Cancel this task?')));
    dialog.append(group);
  }
  const bulk = action(parent ? 'Bulk subtasks' : 'Bulk tasks', () => {
    if (parent) onBulk('subtasks', 'task', parent.id);
    else onBulk('tasks', scope.type === 'project' ? 'project' : 'workstream', scope.id);
  }, 'task-workspace-bulk');
  page.append(bulk);
  return page;
}
