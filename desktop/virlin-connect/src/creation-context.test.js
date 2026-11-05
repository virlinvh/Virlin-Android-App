import assert from 'node:assert/strict';
import test from 'node:test';
import { creationContext } from './creation-context.js';

test('project creation is exclusive to the project directory', () => {
  assert.deepEqual(creationContext(null, 'Overview', null), { label: '＋ Project', kind: 'project' });
  assert.equal(creationContext('project-1', 'Knowledge', null), null);
  assert.equal(creationContext('project-1', 'Map', null), null);
});

test('project and workstream pages create in the correct owner', () => {
  assert.deepEqual(creationContext('project-1', 'Overview', null), {
    label: '＋ Add to project', kind: 'workstream', ownerType: 'project', ownerId: 'project-1', showProjectKinds: true,
  });
  assert.deepEqual(creationContext('project-1', 'Overview', { type: 'workstream', id: 'stream-1', path: [] }), {
    label: '＋ Task', kind: 'task', ownerType: 'workstream', ownerId: 'stream-1',
  });
  assert.deepEqual(creationContext('project-1', 'Overview', { type: 'project', id: 'project-1', path: [] }), {
    label: '＋ Task', kind: 'task', ownerType: 'project', ownerId: 'project-1',
  });
});

test('subtask creation follows the deepest visible task, at any depth', () => {
  for (const type of ['workstream', 'project']) {
    const path = Array.from({ length: 20 }, (_, index) => `task-${index}`);
    assert.deepEqual(creationContext('project-1', 'Overview', { type, id: 'scope-1', path }), {
      label: '＋ Subtask', kind: 'task', ownerType: 'task', ownerId: 'task-19',
    });
  }
});
