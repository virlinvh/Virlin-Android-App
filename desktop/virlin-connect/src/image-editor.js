import { fittedImageSize, cropRectangle } from './image-edit-utils.js';

export function createImageEditor(dialog, onApply) {
  const canvas = dialog.querySelector('#knowledge-image-canvas');
  const overlay = dialog.querySelector('#knowledge-image-overlay');
  const ctx = canvas.getContext('2d');
  const overlayCtx = overlay.getContext('2d');
  const feedback = dialog.querySelector('#knowledge-image-feedback');
  const color = dialog.querySelector('#knowledge-image-color');
  const size = dialog.querySelector('#knowledge-image-size');
  const text = dialog.querySelector('#knowledge-image-text');
  const apply = dialog.querySelector('#knowledge-image-apply');
  const undo = dialog.querySelector('#knowledge-image-undo');
  const reset = dialog.querySelector('#knowledge-image-reset');
  const toolButtons = [...dialog.querySelectorAll('[data-image-tool]')];
  let blockId = null, tool = 'draw', start = null, drawing = false, busy = false;
  let original = null, history = [];

  const setFeedback = message => { feedback.textContent = message; };
  const clearOverlay = () => overlayCtx.clearRect(0, 0, overlay.width, overlay.height);
  const remember = () => { history.push(canvas.toDataURL('image/png')); if (history.length > 8) history.shift(); undo.disabled = false; };
  const point = event => {
    const rect = overlay.getBoundingClientRect();
    return { x: (event.clientX - rect.left) * overlay.width / rect.width,
      y: (event.clientY - rect.top) * overlay.height / rect.height };
  };
  const chooseTool = next => {
    tool = next;
    toolButtons.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.imageTool === tool)));
    text.hidden = tool !== 'text';
    overlay.style.cursor = tool === 'text' ? 'text' : 'crosshair';
    setFeedback(tool === 'crop' ? 'Drag across the image to crop.' :
      tool === 'draw' ? 'Drag to draw on the image.' : 'Type an annotation, then click its position on the image.');
  };
  async function restore(data) {
    const image = new Image();
    await new Promise((resolve, reject) => { image.onload = resolve; image.onerror = reject; image.src = data; });
    canvas.width = overlay.width = image.naturalWidth;
    canvas.height = overlay.height = image.naturalHeight;
    ctx.drawImage(image, 0, 0);
    clearOverlay();
  }
  toolButtons.forEach(button => button.addEventListener('click', () => chooseTool(button.dataset.imageTool)));
  dialog.querySelector('#knowledge-image-close').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.querySelector('#knowledge-image-cancel').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  undo.addEventListener('click', async () => {
    if (busy || !history.length) return;
    busy = true;
    try { await restore(history.pop()); undo.disabled = !history.length; setFeedback('Last image edit undone.'); }
    finally { busy = false; }
  });
  reset.addEventListener('click', async () => {
    if (busy || !original) return;
    busy = true;
    try { await restore(original); history = []; undo.disabled = true; setFeedback('Original image restored.'); }
    finally { busy = false; }
  });
  overlay.addEventListener('pointerdown', event => {
    if (busy || event.button !== 0) return;
    const at = point(event);
    if (tool === 'text') {
      const value = text.value.trim();
      if (!value) { setFeedback('Type annotation text first.'); text.focus(); return; }
      remember();
      ctx.fillStyle = color.value;
      ctx.font = `600 ${Math.max(14, Number(size.value) * 3)}px sans-serif`;
      ctx.textBaseline = 'top';
      ctx.fillText(value, at.x, at.y, canvas.width - at.x);
      setFeedback('Text added. Apply to note when ready.');
      return;
    }
    drawing = true; start = at;
    overlay.setPointerCapture(event.pointerId);
    clearOverlay();
    if (tool === 'draw') {
      remember();
      overlayCtx.strokeStyle = color.value;
      overlayCtx.lineWidth = Number(size.value);
      overlayCtx.lineCap = 'round'; overlayCtx.lineJoin = 'round';
      overlayCtx.beginPath(); overlayCtx.moveTo(at.x, at.y);
    }
  });
  overlay.addEventListener('pointermove', event => {
    if (!drawing) return;
    const at = point(event);
    if (tool === 'draw') {
      overlayCtx.lineTo(at.x, at.y); overlayCtx.stroke();
    } else if (tool === 'crop') {
      clearOverlay();
      const rect = cropRectangle(start, at, canvas.width, canvas.height);
      overlayCtx.fillStyle = '#087d5c33'; overlayCtx.fillRect(rect.x, rect.y, rect.width, rect.height);
      overlayCtx.strokeStyle = '#087d5c'; overlayCtx.lineWidth = 2;
      overlayCtx.strokeRect(rect.x, rect.y, rect.width, rect.height);
    }
  });
  overlay.addEventListener('pointerup', event => {
    if (!drawing) return;
    drawing = false;
    const at = point(event);
    if (tool === 'draw') {
      overlayCtx.lineTo(at.x, at.y); overlayCtx.stroke();
      ctx.drawImage(overlay, 0, 0); clearOverlay();
      setFeedback('Drawing added. Apply to note when ready.');
    } else if (tool === 'crop') {
      const rect = cropRectangle(start, at, canvas.width, canvas.height);
      clearOverlay();
      if (rect.width < 5 || rect.height < 5) { setFeedback('Select a larger crop area.'); return; }
      remember();
      const cropped = document.createElement('canvas');
      cropped.width = rect.width; cropped.height = rect.height;
      cropped.getContext('2d').drawImage(canvas, rect.x, rect.y, rect.width, rect.height, 0, 0, rect.width, rect.height);
      canvas.width = overlay.width = rect.width;
      canvas.height = overlay.height = rect.height;
      ctx.drawImage(cropped, 0, 0);
      setFeedback('Image cropped. Apply to note when ready.');
    }
    start = null;
  });
  overlay.addEventListener('pointercancel', () => { drawing = false; start = null; clearOverlay(); });
  apply.addEventListener('click', async () => {
    if (busy || !blockId) return;
    busy = true; apply.disabled = true; setFeedback('Uploading edited image to your phone…');
    try {
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
      if (!blob) throw new Error('Could not prepare the edited image');
      await onApply(blockId, new File([blob], 'edited-note-image.png', { type: 'image/png' }), setFeedback);
      dialog.close();
    } catch (error) { setFeedback(`Image not applied: ${String(error)}`); }
    finally { busy = false; apply.disabled = false; }
  });
  return {
    async open(id, url) {
      const image = new Image();
      await new Promise((resolve, reject) => { image.onload = resolve; image.onerror = reject; image.src = url; });
      const dimensions = fittedImageSize(image.naturalWidth, image.naturalHeight);
      canvas.width = overlay.width = dimensions.width;
      canvas.height = overlay.height = dimensions.height;
      ctx.drawImage(image, 0, 0, dimensions.width, dimensions.height);
      original = canvas.toDataURL('image/png'); history = []; undo.disabled = true;
      blockId = id; text.value = ''; chooseTool('draw');
      dialog.showModal();
      if (dimensions.width !== image.naturalWidth || dimensions.height !== image.naturalHeight)
        setFeedback(`Editing a ${dimensions.width} × ${dimensions.height} copy for safe performance. The saved original remains unchanged until you save the note.`);
    },
  };
}
