import test from 'node:test';
import assert from 'node:assert/strict';
import { parseRichNoteHtml, parsePlainNoteText, insertRichNoteBlocks } from './rich-paste.js';

const paragraph = (text, runs = []) => ({ id: crypto.randomUUID(), type: 'PARAGRAPH', text, runs, align: 'LEFT' });
const heading = text => ({ id: crypto.randomUUID(), type: 'HEADING_2', text, runs: [], align: 'LEFT' });

test('formatted paste replaces selected text without losing surrounding runs', () => {
  const original = paragraph('Before SELECTED after', [
    { start: 0, end: 6, marks: ['BOLD'] },
    { start: 16, end: 21, marks: ['ITALIC'] },
  ]);
  const pasted = paragraph('pasted', [{ start: 0, end: 6, marks: ['UNDERLINE'] }]);
  const result = insertRichNoteBlocks([original], 0, 7, 15, [pasted]);
  assert.deepEqual(result.map(block => block.text), ['Before ', 'pasted', ' after']);
  assert.deepEqual(result[0].runs, [{ start: 0, end: 6, marks: ['BOLD'] }]);
  assert.deepEqual(result[2].runs, [{ start: 1, end: 6, marks: ['ITALIC'] }]);
  assert.deepEqual(result[1].runs, pasted.runs);
});

test('pasting at the end of a topic keeps the heading and inserts content below it', () => {
  const topic = heading('Topic');
  const content = paragraph('Formatted text', [{ start: 0, end: 9, marks: ['BOLD'] }]);
  const result = insertRichNoteBlocks([topic], 0, 5, 5, [content]);
  assert.equal(result[0].type, 'HEADING_2');
  assert.equal(result[0].text, 'Topic');
  assert.equal(result[1].text, 'Formatted text');
  assert.deepEqual(result[1].runs, content.runs);
});

function text(value) { return { nodeType: 3, nodeValue: value }; }
function element(tagName, style = {}, ...childNodes) {
  return { nodeType: 1, tagName, style, childNodes,
    get children() { return childNodes.filter(child => child.nodeType === 1); },
    getAttribute() { return null; },
    querySelector() { return null; } };
}

test('clipboard heading, paragraph styles, and list become separate phone blocks', () => {
  const headingNode = element('H2', {}, text('Study topic'));
  const strong = element('STRONG', {}, text('important'));
  const paragraphNode = element('P', { textAlign: 'center', fontSize: '18px' }, text('Keep '), strong);
  const bullet = element('LI', {}, text('First item'));
  const list = element('UL', {}, bullet);
  const body = element('BODY', {}, headingNode, paragraphNode, list);
  const blocks = parseRichNoteHtml('ignored by injected document', { body });
  assert.deepEqual(blocks.map(block => block.type), ['HEADING_2', 'PARAGRAPH', 'BULLET']);
  assert.equal(blocks[1].align, 'CENTER');
  assert.equal(blocks[1].runs[0].fontSizeSp, 18);
  assert.deepEqual(blocks[1].runs[1].marks, ['BOLD']);
  assert.equal(blocks[2].text, 'First item');
});

test('ChatGPT-style list items keep wrapped text with their marker and formatting', () => {
  const bullets = element('UL', {},
    element('LI', {}, element('P', {}, text('• '), text('What '), element('STRONG', {}, text('works')))),
    element('LI', {}, element('P', {}, text('Next item'))));
  const numbers = element('OL', {},
    element('LI', {}, element('P', {}, text('First step'))),
    element('LI', {}, element('P', {}, text('Second step'))));
  const blocks = parseRichNoteHtml('', { body: element('BODY', {}, bullets, numbers) });
  assert.deepEqual(blocks.map(block => [block.type, block.text]), [
    ['BULLET', 'What works'], ['BULLET', 'Next item'],
    ['NUMBERED', 'First step'], ['NUMBERED', 'Second step'],
  ]);
  assert.deepEqual(blocks[0].runs.at(-1).marks, ['BOLD']);
});

test('plain-text bullet and numbered markers become list blocks', () => {
  const blocks = parsePlainNoteText('Intro\n- one\n• two\n1. first\n2) second\nOutro');
  assert.deepEqual(blocks.map(block => [block.type, block.text]), [
    ['PARAGRAPH', 'Intro'], ['BULLET', 'one'], ['BULLET', 'two'],
    ['NUMBERED', 'first'], ['NUMBERED', 'second'], ['PARAGRAPH', 'Outro'],
  ]);
});
