import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyLink } from './link-preview.js';

test('recognizes only exact YouTube hosts and supported video forms', () => {
  for (const url of [
    'https://youtu.be/dQw4w9WgXcQ',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5',
    'https://m.youtube.com/shorts/dQw4w9WgXcQ',
    'https://youtube.com/live/dQw4w9WgXcQ'
  ]) assert.equal(classifyLink(url)?.embedUrl, 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');
  assert.equal(classifyLink('https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ')?.kind, 'website');
  assert.equal(classifyLink('https://youtube.com/watch?v=bad')?.kind, 'website');
});

test('rejects non-web and malformed URLs', () => {
  assert.equal(classifyLink('javascript:alert(1)'), null);
  assert.equal(classifyLink('not a url'), null);
  assert.equal(classifyLink('https://example.com/path')?.kind, 'website');
});
