import test from 'node:test';
import assert from 'node:assert/strict';
import { fittedImageSize, cropRectangle } from './image-edit-utils.js';

test('large images fit the editing canvas without enlarging small images', () => {
  assert.deepEqual(fittedImageSize(8000, 4000), { width: 4096, height: 2048 });
  assert.deepEqual(fittedImageSize(640, 480), { width: 640, height: 480 });
});

test('crop drag works in either direction and stays within image bounds', () => {
  assert.deepEqual(cropRectangle({ x: 90, y: 80 }, { x: 10, y: 20 }, 100, 100),
    { x: 10, y: 20, width: 80, height: 60 });
  assert.deepEqual(cropRectangle({ x: -20, y: -20 }, { x: 200, y: 180 }, 100, 100),
    { x: 0, y: 0, width: 100, height: 100 });
});
