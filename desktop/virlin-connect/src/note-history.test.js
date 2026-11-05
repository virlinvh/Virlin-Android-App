import test from 'node:test';
import assert from 'node:assert/strict';
import { createNoteHistory } from './note-history.js';

test('undo and redo restore title, blocks, formatting, and order', () => {
  const initial = { title: 'Original', blocks: [{ id: 'a', text: 'One', runs: [] }, { id: 'b', text: 'Two' }] };
  const history = createNoteHistory(initial);
  const edited = { title: 'Updated', blocks: [{ id: 'b', text: 'Two' }, { id: 'a', text: 'One', runs: [{ start: 0, end: 3, marks: ['BOLD'] }] }] };
  history.record(edited);
  assert.deepEqual(history.undo(), initial);
  assert.deepEqual(history.redo(), edited);
  assert.equal(history.state().dirty, true);
});

test('typing groups briefly, while a new action clears redo', () => {
  const history = createNoteHistory({ title: '', blocks: [] });
  history.record({ title: 'A', blocks: [] }, 'typing', 'title', 100);
  history.record({ title: 'AB', blocks: [] }, 'typing', 'title', 200);
  assert.deepEqual(history.undo(), { title: '', blocks: [] });
  assert.equal(history.state().dirty, false);
  history.record({ title: 'C', blocks: [] });
  assert.equal(history.state().canRedo, false);
});

test('changes in different blocks form separate undo steps', () => {
  const history = createNoteHistory({ title: '', blocks: [{ id: 'a', text: '' }, { id: 'b', text: '' }] });
  history.record({ title: '', blocks: [{ id: 'a', text: 'A' }, { id: 'b', text: '' }] }, 'typing', 'a', 100);
  history.record({ title: '', blocks: [{ id: 'a', text: 'A' }, { id: 'b', text: 'B' }] }, 'typing', 'b', 200);
  assert.equal(history.undo().blocks[1].text, '');
  assert.equal(history.undo().blocks[0].text, '');
});
