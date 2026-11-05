export const minimumWidth = 130;
export const maximumWidth = 520;
export function clampWidth(width) {
  return Math.max(minimumWidth, Math.min(maximumWidth, Number.isFinite(width) ? width : 200));
}
export function readMapLayout(value) {
  try {
    const input = JSON.parse(value || '{}');
    const result = {};
    for (const [key, item] of Object.entries(input).slice(0, 600)) {
      if (!/^(project|stream|task):[\w-]+$/.test(key) || !item || typeof item !== 'object') continue;
      result[key] = {
        x: Number.isFinite(item.x) ? Math.max(-100000, Math.min(100000, item.x)) : 0,
        y: Number.isFinite(item.y) ? Math.max(-100000, Math.min(100000, item.y)) : 0,
        ...(Number.isFinite(item.width) ? { width: clampWidth(item.width) } : {}),
        color: ['green', 'blue', 'gold'].includes(item.color) ? item.color : 'green',
      };
    }
    return result;
  } catch { return {}; }
}
export function descendants(node) {
  return [node, ...node.children.flatMap(descendants)];
}
export function moveBranch(layout, node, dx, dy) {
  for (const child of descendants(node)) {
    const previous = layout[child.key] || {};
    layout[child.key] = { ...previous, x: (previous.x || 0) + dx, y: (previous.y || 0) + dy };
  }
}
