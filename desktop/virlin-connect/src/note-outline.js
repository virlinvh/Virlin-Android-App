export function headingLevel(type) {
  return { HEADING_1: 1, HEADING_2: 2, HEADING_3: 3 }[type] || 0;
}

export function nextHeadingType(type) {
  return { HEADING_1: 'HEADING_2', HEADING_2: 'HEADING_3', HEADING_3: 'HEADING_1' }[type] || null;
}

export function buildNoteOutline(blocks, foldHeadings = true) {
  const headings = [];
  const hiddenIds = new Set();
  const ancestors = [];
  blocks.forEach((block, index) => {
    const level = headingLevel(block.type);
    if (level) while (ancestors.length && ancestors.at(-1).level >= level) ancestors.pop();
    const hidden = ancestors.some(heading => heading.collapsed);
    if (hidden) hiddenIds.add(block.id);
    if (level) {
      const next = blocks[index + 1];
      const hasChildren = Boolean(next && (!headingLevel(next.type) || headingLevel(next.type) > level));
      headings.push({ block, index, level, hidden, hasChildren });
      ancestors.push({ level, collapsed: foldHeadings && Boolean(block.isCollapsed) });
    }
  });
  return { headings, hiddenIds };
}
