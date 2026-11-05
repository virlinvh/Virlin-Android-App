import test from 'node:test';
import assert from 'node:assert/strict';
import { shiftNoteRuns, toggleNoteMark, setNoteStyle } from './note-edit.js';

test('typing inside bold text retains the mark without losing the trailing range', () => {
  const runs = [{ start: 0, end: 4, marks: ['BOLD'] }];
  assert.deepEqual(shiftNoteRuns(runs, 'test', 'teXst'), [{ start: 0, end: 5, marks: ['BOLD'] }]);
});

test('replacement shifts later formatting and preserves unrelated attributes', () => {
  const runs = [{ start: 4, end: 8, marks: ['ITALIC'], linkUrl: 'https://example.com' }];
  assert.deepEqual(shiftNoteRuns(runs, 'one two!', 'one long two!'),
    [{ start: 9, end: 13, marks: ['ITALIC'], linkUrl: 'https://example.com' }]);
});

test('mark toggling adds and removes only the selected characters', () => {
  const added = toggleNoteMark([], 5, 1, 4, 'BOLD');
  assert.deepEqual(added, [{ start: 1, end: 4, marks: ['BOLD'] }]);
  assert.deepEqual(toggleNoteMark(added, 5, 1, 4, 'BOLD'), []);
});

test('font and link styling keep adjacent marks intact', () => {
  const bold = [{ start: 0, end: 5, marks: ['BOLD'] }];
  const sized = setNoteStyle(bold, 5, 1, 4, 'fontSizeSp', 24);
  assert.deepEqual(sized, [
    { start: 0, end: 1, marks: ['BOLD'] },
    { start: 1, end: 4, marks: ['BOLD'], fontSizeSp: 24 },
    { start: 4, end: 5, marks: ['BOLD'] },
  ]);
  assert.deepEqual(setNoteStyle(sized, 5, 1, 4, 'linkUrl', 'https://example.com')[1],
    { start: 1, end: 4, marks: ['BOLD'], fontSizeSp: 24, linkUrl: 'https://example.com' });
  assert.deepEqual(setNoteStyle(sized, 5, 1, 4, 'fontSizeSp', null), bold);
});
