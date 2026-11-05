import test from 'node:test';
import assert from 'node:assert/strict';
import { checklistSections, checklistCounts, toggleChecklistBlock, appendChecklistBlock,
  parseChecklistTasks, appendChecklistTasks } from './checklist-model.js';

const blocks = [
  { id: 'h1', type: 'HEADING_2', text: 'Before build' },
  { id: 'a', type: 'CHECKLIST', text: 'Review', checked: false },
  { id: 'b', type: 'CHECKLIST', text: 'Verify', checked: true },
  { id: 'h2', type: 'HEADING_2', text: 'Install' },
  { id: 'c', type: 'CHECKLIST', text: 'Install APK', checked: false },
];
test('real headings group checklist items without inventing sections', () => {
  assert.deepEqual(checklistSections(blocks).map(section => [section.title, section.blocks.map(item => item.id)]),
    [['Before build', ['a', 'b']], ['Install', ['c']]]);
  assert.equal(checklistSections([blocks[1]])[0].title, 'Tasks');
});
test('completion counts only checklist blocks', () => {
  assert.deepEqual(checklistCounts(blocks), { total: 3, done: 1 });
});
test('toggling preserves other blocks and refuses unknown ids', () => {
  const next = toggleChecklistBlock(blocks, 'a', true);
  assert.equal(next[1].checked, true);
  assert.equal(next[2], blocks[2]);
  assert.equal(blocks[1].checked, false);
  assert.equal(toggleChecklistBlock(blocks, 'missing', true), null);
});
test('adding a task retains headings and validates limits', () => {
  const next = appendChecklistBlock(blocks, '  New task  ', 'd');
  assert.equal(next.at(-1).text, 'New task');
  assert.equal(next.at(-1).type, 'CHECKLIST');
  assert.equal(next[0], blocks[0]);
  assert.equal(appendChecklistBlock(blocks, ' ', 'd'), null);
  assert.equal(appendChecklistBlock(blocks, 'new', 'a'), null);
});
test('bullets, numbered lines, plain lines, and JSON arrays produce the same tasks', () => {
  const expected = ['Review release changes', 'Test on phone'];
  assert.deepEqual(parseChecklistTasks('• Review release changes\n• Test on phone').tasks, expected);
  assert.deepEqual(parseChecklistTasks('1. Review release changes\n2) Test on phone').tasks, expected);
  assert.deepEqual(parseChecklistTasks('- [ ] Review release changes\n- [x] Test on phone').tasks, expected);
  assert.deepEqual(parseChecklistTasks('[ ] Review release changes\n[x] Test on phone').tasks, expected);
  assert.deepEqual(parseChecklistTasks('Review release changes\nTest on phone').tasks, expected);
  assert.deepEqual(parseChecklistTasks('["Review release changes", "Test on phone"]').tasks, expected);
  assert.deepEqual(parseChecklistTasks('[\n "Review release changes",\n "Test on phone"\n]').tasks, expected);
  assert.deepEqual(parseChecklistTasks('```json\n["Review release changes", "Test on phone"]\n```').tasks, expected);
});
test('malformed or non-text JSON cannot create tasks', () => {
  assert.ok(parseChecklistTasks('["one",]').error);
  assert.ok(parseChecklistTasks('["one", 2]').error);
  assert.ok(parseChecklistTasks('[]').tasks.length === 0);
});
test('batch append is atomic and respects phone limits', () => {
  let id = 0;
  const next = appendChecklistTasks(blocks, ['First', 'Second'], () => `new-${++id}`);
  assert.deepEqual(next.slice(-2).map(block => block.text), ['First', 'Second']);
  assert.equal(blocks.length, 5);
  assert.equal(appendChecklistTasks(blocks, Array(51).fill('Task'), () => 'x'), null);
  assert.equal(appendChecklistTasks(blocks, ['Task', ''], () => 'x'), null);
  assert.equal(appendChecklistTasks(blocks, ['x'.repeat(1001)], () => 'x'), null);
});
