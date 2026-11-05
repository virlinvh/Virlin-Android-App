import test from 'node:test';
import assert from 'node:assert/strict';
import { clampWidth, readMapLayout, moveBranch } from './map-layout.js';

test('widths are bounded and invalid values are safe', () => {
  assert.equal(clampWidth(-8), 130);
  assert.equal(clampWidth(9999), 520);
  assert.equal(clampWidth(NaN), 200);
});
test('stored layouts reject malformed or unsafe content', () => {
  assert.deepEqual(readMapLayout('{broken'), {});
  const result = readMapLayout(JSON.stringify({ bad: {}, 'task:a': { x: 2e8, y: -2e8, width: -8, color: 'script' } }));
  assert.deepEqual(result, { 'task:a': { x: 100000, y: -100000, width: 130, color: 'green' } });
});
test('dragging a branch shifts every descendant without changing hierarchy', () => {
  const child = { key: 'task:child', children: [] };
  const branch = { key: 'stream:branch', children: [child] };
  const layout = { 'task:child': { x: 5, y: 6, width: 220 } };
  moveBranch(layout, branch, 10, -20);
  assert.deepEqual(layout['task:child'], { x: 15, y: -14, width: 220 });
  assert.equal(layout['stream:branch'].x, 10);
  assert.equal(branch.children[0], child);
});
