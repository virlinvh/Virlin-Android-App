export function fittedImageSize(width, height, maxSide = 4096) {
  if (![width, height, maxSide].every(Number.isFinite) || width <= 0 || height <= 0 || maxSide <= 0)
    throw new Error('Invalid image dimensions');
  const scale = Math.min(1, maxSide / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

export function cropRectangle(start, end, width, height) {
  const left = Math.max(0, Math.min(width, Math.floor(Math.min(start.x, end.x))));
  const top = Math.max(0, Math.min(height, Math.floor(Math.min(start.y, end.y))));
  const right = Math.max(0, Math.min(width, Math.ceil(Math.max(start.x, end.x))));
  const bottom = Math.max(0, Math.min(height, Math.ceil(Math.max(start.y, end.y))));
  return { x: left, y: top, width: right - left, height: bottom - top };
}
