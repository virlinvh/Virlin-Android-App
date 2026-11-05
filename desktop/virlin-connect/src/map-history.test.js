import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { MapHistory } from './map-history.js';

test('keeps 25 confirmed edits across selected boxes and redoes in order', () => {
  const history = new MapHistory();
  for (let i = 0; i < 30; i++) history.record({ nodeId: String(i) });
  for (let i = 0; i < 25; i++) history.confirm('undo');
  assert.equal(history.canUndo, false);
  assert.equal(history.candidate('redo').nodeId, '5');
  for (let i = 0; i < 25; i++) history.confirm('redo');
  assert.equal(history.canRedo, false);
  history.confirm('undo');
  history.record({ nodeId: 'new' });
  assert.equal(history.canRedo, false);
});
