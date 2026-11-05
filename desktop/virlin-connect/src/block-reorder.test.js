import test from 'node:test';
import assert from 'node:assert/strict';
import { reorderNoteBlocks, noteDropTarget } from './block-reorder.js';

const blocks = [
  { id: 'heading', type: 'HEADING_2', runs: [{ start: 0, end: 3 }] },
  { id: 'image', type: 'IMAGE', imagePath: 'saved-image' },
  { id: 'code', type: 'CODE', text: 'let x = 1' },
  { id: 'text', type: 'PARAGRAPH', text: 'Body' },
];
const ids = result => result.blocks.map(block => block.id);

test('moves any block across multiple positions while preserving its data', () => {
  const result = reorderNoteBlocks(blocks, 'image', 'text', 'after');
  assert.equal(result.moved, true);
  assert.deepEqual(ids(result), ['heading', 'code', 'text', 'image']);
  assert.strictEqual(result.blocks[3], blocks[1]);
  assert.deepEqual(blocks.map(block => block.id), ['heading', 'image', 'code', 'text']);
});

test('moves last to first and first to last', () => {
  assert.deepEqual(ids(reorderNoteBlocks(blocks, 'text', 'heading', 'before')), ['text', 'heading', 'image', 'code']);
  assert.deepEqual(ids(reorderNoteBlocks(blocks, 'heading', 'text', 'after')), ['image', 'code', 'text', 'heading']);
});

test('adjacent drops and invalid targets do not falsely mark a note changed', () => {
  assert.equal(reorderNoteBlocks(blocks, 'image', 'code', 'before').moved, false);
  assert.equal(reorderNoteBlocks(blocks, 'image', 'heading', 'after').moved, false);
  assert.equal(reorderNoteBlocks(blocks, 'image', 'image', 'after').moved, false);
  assert.equal(reorderNoteBlocks(blocks, 'image', 'missing', 'before').moved, false);
});

test('pointer drop target handles long blocks, gaps, and both directions', () => {
  const rows = [
    { id: 'a', top: 0, bottom: 40 },
    { id: 'long', top: 50, bottom: 600 },
    { id: 'c', top: 610, bottom: 650 },
  ];
  assert.equal(noteDropTarget(rows, 'long', 300), null);
  assert.deepEqual(noteDropTarget(rows, 'long', 15), { id: 'a', position: 'before' });
  assert.deepEqual(noteDropTarget(rows, 'long', 45), { id: 'a', position: 'after' });
  assert.deepEqual(noteDropTarget(rows, 'long', 605), { id: 'c', position: 'before' });
  assert.deepEqual(noteDropTarget(rows, 'long', 700), { id: 'c', position: 'after' });
});
