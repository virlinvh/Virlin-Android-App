/** Return a reordered copy; block objects (including image paths and formatting) stay intact. */
export function reorderNoteBlocks(blocks, sourceId, targetId, position) {
  if (position !== 'before' && position !== 'after') return { blocks, moved: false };
  const from = blocks.findIndex(block => block.id === sourceId);
  const target = blocks.findIndex(block => block.id === targetId);
  if (from < 0 || target < 0 || from === target) return { blocks, moved: false };
  const next = [...blocks];
  const [item] = next.splice(from, 1);
  const targetAfterRemoval = next.findIndex(block => block.id === targetId);
  const insertion = targetAfterRemoval + (position === 'after' ? 1 : 0);
  next.splice(insertion, 0, item);
  const moved = next.some((block, index) => block !== blocks[index]);
  return { blocks: moved ? next : blocks, moved };
}

/** Pick the nearest visible block, including when a pointer is between blocks. */
export function noteDropTarget(rows, sourceId, pointerY) {
  const source = rows.find(row => row.id === sourceId);
  if (source && pointerY >= source.top && pointerY <= source.bottom) return null;
  const candidates = rows.filter(row => row.id !== sourceId);
  if (!candidates.length) return null;
  const distance = row => pointerY < row.top ? row.top - pointerY :
    pointerY > row.bottom ? pointerY - row.bottom : 0;
  const target = candidates.reduce((nearest, row) =>
    distance(row) < distance(nearest) ? row : nearest);
  return { id: target.id, position: pointerY < (target.top + target.bottom) / 2 ? 'before' : 'after' };
}
