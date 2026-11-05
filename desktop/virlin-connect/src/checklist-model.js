const headingTypes = new Set(['HEADING_1', 'HEADING_2', 'HEADING_3']);

export function checklistSections(blocks) {
  const sections = [];
  let current = { id: 'tasks', title: 'Tasks', blocks: [] };
  for (const block of blocks || []) {
    if (headingTypes.has(block.type)) {
      if (current.blocks.length || current.id !== 'tasks') sections.push(current);
      current = { id: block.id, title: block.text || 'Untitled section', blocks: [] };
    } else current.blocks.push(block);
  }
  sections.push(current);
  return sections;
}

export function checklistCounts(blocks) {
  const tasks = (blocks || []).filter(block => block.type === 'CHECKLIST');
  return { total: tasks.length, done: tasks.filter(block => block.checked).length };
}

export function toggleChecklistBlock(blocks, id, checked) {
  if (!blocks.some(block => block.type === 'CHECKLIST' && block.id === id)) return null;
  return blocks.map(block => block.id === id ? { ...block, checked } : block);
}

export function appendChecklistBlock(blocks, text, id) {
  const title = text.trim();
  if (!title || title.length > 1000 || blocks.length >= 500 || blocks.some(block => block.id === id)) return null;
  const totalText = blocks.reduce((size, block) => size + (block.text?.length || 0), 0) + title.length;
  if (totalText > 32768) return null;
  return [...blocks, { id, type: 'CHECKLIST', text: title, checked: false,
    runs: [], align: 'LEFT', indent: 0, isCollapsed: false }];
}

/** One input line or JSON-array entry maps to one task. Never evaluate pasted data. */
export function parseChecklistTasks(input) {
  const source = input.trim().replace(/^```(?:json)?\s*\r?\n([\s\S]*?)\r?\n```$/i, '$1').trim();
  if (!source) return { tasks: [], format: 'empty', error: '' };
  if (source.startsWith('[') && !/^\[(?: |x|X)\]\s/.test(source)) {
    let values;
    try { values = JSON.parse(source); }
    catch { return { tasks: [], format: 'json', error: 'Check the quotes and commas in the JSON array.' }; }
    if (!Array.isArray(values) || values.some(value => typeof value !== 'string' || !value.trim()))
      return { tasks: [], format: 'json', error: 'Use a JSON array of non-empty text entries.' };
    return { tasks: values.map(value => value.trim()), format: 'json', error: '' };
  }
  const lines = source.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const marker = /^(?:[-*•◦▪]|\d+[.)])\s+/;
  const checkbox = /^\[(?: |x|X)\]\s+/;
  const tasks = lines.map(line => line.replace(marker, '').replace(checkbox, '').trim());
  return { tasks, format: lines.some(line => marker.test(line) || checkbox.test(line)) ? 'list' : 'plain', error: '' };
}

/** A batch is all-or-nothing and respects the phone's per-save and note limits. */
export function appendChecklistTasks(blocks, tasks, makeId) {
  if (!tasks.length || tasks.length > 50 || blocks.length + tasks.length > 500) return null;
  if (tasks.some(task => !task.trim() || task.trim().length > 1000)) return null;
  if (blocks.reduce((sum, block) => sum + (block.text?.length || 0), 0) +
      tasks.reduce((sum, task) => sum + task.trim().length, 0) > 32768) return null;
  const next = [...blocks];
  for (const task of tasks) {
    const added = appendChecklistBlock(next, task, makeId());
    if (!added) return null;
    next.push(added.at(-1));
  }
  return next;
}
