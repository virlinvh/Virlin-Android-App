import test from 'node:test';
import assert from 'node:assert/strict';
import { buildNoteOutline, headingLevel, nextHeadingType } from './note-outline.js';

const block = (id, type, isCollapsed = false) => ({ id, type, text: id, isCollapsed });

test('heading levels are type-based, not inferred from bold body text', () => {
  assert.equal(headingLevel('PARAGRAPH'), 0);
  assert.equal(headingLevel('HEADING_2'), 2);
  assert.equal(nextHeadingType('PARAGRAPH'), null);
  assert.equal(nextHeadingType('HEADING_1'), 'HEADING_2');
  assert.equal(nextHeadingType('HEADING_2'), 'HEADING_3');
  assert.equal(nextHeadingType('HEADING_3'), 'HEADING_1');
});

test('folding a heading hides descendants but preserves later sibling sections', () => {
  const blocks = [block('intro', 'PARAGRAPH'), block('a', 'HEADING_1', true),
    block('a-body', 'PARAGRAPH'), block('a-1', 'HEADING_2'), block('a-1-body', 'CODE'),
    block('b', 'HEADING_1'), block('b-body', 'PARAGRAPH')];
  const result = buildNoteOutline(blocks);
  assert.deepEqual([...result.hiddenIds], ['a-body', 'a-1', 'a-1-body']);
  assert.deepEqual(result.headings.map(item => [item.block.id, item.level, item.hidden]),
    [['a', 1, false], ['a-1', 2, true], ['b', 1, false]]);
  assert.equal(result.headings[0].hasChildren, true);
});

test('a nested collapsed heading ends at the next peer', () => {
  const result = buildNoteOutline([block('root', 'HEADING_1'), block('one', 'HEADING_2', true),
    block('body', 'PARAGRAPH'), block('two', 'HEADING_2'), block('other', 'PARAGRAPH')]);
  assert.deepEqual([...result.hiddenIds], ['body']);
});

test('fold mode off shows every heading and body without changing stored collapse state', () => {
  const blocks = [block('h1', 'HEADING_1', true), block('body', 'PARAGRAPH'),
    block('h2', 'HEADING_2', true), block('nested', 'CODE'),
    block('peer', 'HEADING_1'), block('end', 'PARAGRAPH')];
  assert.deepEqual([...buildNoteOutline(blocks).hiddenIds], ['body', 'h2', 'nested']);
  assert.deepEqual([...buildNoteOutline(blocks, false).hiddenIds], []);
  assert.equal(blocks[0].isCollapsed, true);
  assert.equal(blocks[2].isCollapsed, true);
});

test('H3 collapse stays within its H2 while H1 collapse hides the entire branch', () => {
  const blocks = [block('root', 'HEADING_1'), block('section', 'HEADING_2'),
    block('detail', 'HEADING_3', true), block('detail-body', 'PARAGRAPH'),
    block('peer-detail', 'HEADING_3'), block('peer-body', 'PARAGRAPH'),
    block('next-section', 'HEADING_2'), block('next-body', 'PARAGRAPH'),
    block('next-root', 'HEADING_1'), block('last', 'PARAGRAPH')];
  assert.deepEqual([...buildNoteOutline(blocks).hiddenIds], ['detail-body']);
  blocks[0].isCollapsed = true;
  assert.deepEqual([...buildNoteOutline(blocks).hiddenIds],
    ['section', 'detail', 'detail-body', 'peer-detail', 'peer-body', 'next-section', 'next-body']);
  assert.deepEqual([...buildNoteOutline(blocks, false).hiddenIds], []);
});
