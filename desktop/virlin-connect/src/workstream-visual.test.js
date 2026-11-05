import test from 'node:test';
import assert from 'node:assert/strict';
import { workstreamVisual, workstreamIconMarkup } from './workstream-visual.js';

test('maps every phone workstream state to its desktop visual', () => {
  const expected = {
    FOCUS: 'focus', PROCESSING: 'running', CHECK: 'review', READY: 'ready',
    SNOOZED: 'waiting', PAUSED: 'paused', BLOCKED: 'blocked', DONE: 'done',
  };
  for (const [state, kind] of Object.entries(expected)) {
    assert.equal(workstreamVisual({ id: 's', effectiveState: state }).kind, kind);
    assert.match(workstreamIconMarkup(kind), /^<svg /);
  }
});

test('active tasks take priority and completed tasks do not', () => {
  const stream = { id: 's', effectiveState: 'READY' };
  assert.equal(workstreamVisual(stream, [{ workStreamId: 's', executionState: 'CHECK' }]).kind, 'review');
  assert.equal(workstreamVisual(stream, [{ workStreamId: 's', executionState: 'CHECK', status: 'DONE' }]).kind, 'ready');
  assert.equal(workstreamVisual(stream, [{ workStreamId: 'other', executionState: 'FOCUS' }]).kind, 'ready');
  assert.equal(workstreamVisual(stream, [
    { workStreamId: 's', executionState: 'PROCESSING' },
    { workStreamId: 's', executionState: 'FOCUS' },
  ]).kind, 'multi-run');
});
