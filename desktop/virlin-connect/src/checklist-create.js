import { parseChecklistTasks } from './checklist-model.js';
import './knowledge-create.css';

/** A new checklist remains a draft until the shared phone-save form is confirmed. */
export function mountChecklistCreation(host, content, feedback) {
  let tasks = [];
  const page = document.createElement('div'); page.className = 'checklist-create-page';
  page.innerHTML = '<div class="checklist-progress"><strong class="checklist-create-count">0 tasks</strong><span>Build your list, then save it to your phone.</span><div class="checklist-progress-track"><div style="width:0%"></div></div></div><div class="checklist-actions"><span>Tasks</span><button type="button" class="checklist-add">+ Add task</button></div><div class="checklist-create-items"></div>';
  host.replaceChildren(page);
  const items = page.querySelector('.checklist-create-items');
  function render() {
    content.value = tasks.join('\n');
    content.dispatchEvent(new Event('input', { bubbles: true }));
    page.querySelector('.checklist-create-count').textContent = `${tasks.length} task${tasks.length === 1 ? '' : 's'}`;
    items.replaceChildren();
    if (!tasks.length) { const empty = document.createElement('p'); empty.className = 'checklist-empty'; empty.textContent = 'A clear list starts here. Add one task or paste a whole list.'; items.append(empty); }
    tasks.forEach((task, index) => {
      const row = document.createElement('div'); row.className = 'checklist-create-item';
      const marker = document.createElement('span'); marker.textContent = '☐'; marker.setAttribute('aria-hidden', 'true');
      const title = document.createElement('span'); title.textContent = task;
      const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '×'; remove.setAttribute('aria-label', `Remove ${task}`);
      remove.addEventListener('click', () => { tasks.splice(index, 1); render(); });
      row.append(marker, title, remove); items.append(row);
    });
  }
  page.querySelector('.checklist-add').addEventListener('click', () => {
    const dialog = document.createElement('dialog'); dialog.className = 'checklist-batch-dialog';
    dialog.setAttribute('aria-label', 'Add tasks');
    dialog.innerHTML = '<div class="checklist-batch-head"><div><span>NEW IN THIS CHECKLIST</span><h2>Turn a list into tasks</h2><p>Plain lines, bullets, numbers, or a JSON array.</p></div></div><div class="checklist-batch-grid"><label class="checklist-batch-write">Paste or type<textarea aria-label="Tasks to add" placeholder="• First task&#10;• Second task"></textarea></label><div class="checklist-batch-review"><strong>Ready to add</strong><ol></ol><p role="alert"></p></div></div><div class="checklist-batch-foot"><span class="checklist-batch-count">0 tasks</span><div><button type="button" class="checklist-batch-cancel">Cancel</button><button type="button" class="checklist-batch-submit" disabled>Add tasks</button></div></div>';
    const input = dialog.querySelector('textarea'), submit = dialog.querySelector('.checklist-batch-submit');
    let parsed = [];
    input.addEventListener('input', () => {
      const result = parseChecklistTasks(input.value); parsed = result.tasks;
      const error = result.error || (tasks.length + parsed.length > 50 ? 'Use at most 50 tasks in a new list.' : parsed.some(t => /[\r\n]/.test(t)) ? 'Each JSON entry must be a single task line.' : parsed.some(t => !t || t.length > 1000) ? 'Each task needs 1–1,000 characters.' : [...tasks, ...parsed].join('\n').length > 32768 ? 'This list is too large.' : '');
      dialog.querySelector('[role=alert]').textContent = error;
      dialog.querySelector('.checklist-batch-count').textContent = `${parsed.length} tasks`;
      const list = dialog.querySelector('ol'); list.replaceChildren();
      for (const task of parsed.slice(0, 50)) { const li = document.createElement('li'); li.textContent = task; list.append(li); }
      submit.disabled = !!error || !parsed.length;
    });
    const close = () => { dialog.close(); dialog.remove(); };
    dialog.querySelector('.checklist-batch-cancel').addEventListener('click', close);
    dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
    submit.addEventListener('click', () => { if (submit.disabled) return; tasks.push(...parsed); render(); feedback.textContent = 'Draft updated. Save to phone when ready.'; close(); });
    page.append(dialog); dialog.showModal(); input.focus();
  });
  render();
}
