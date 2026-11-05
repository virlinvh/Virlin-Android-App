/** Map the browser selection to UTF-16 offsets in one editable note block. */
export function noteSelectionOffsets(element, selection = window.getSelection()) {
  if (!element || !selection || selection.rangeCount === 0 ||
      !element.contains(selection.anchorNode) || !element.contains(selection.focusNode)) return null;
  const range = selection.getRangeAt(0);
  const start = range.cloneRange(); start.selectNodeContents(element); start.setEnd(range.startContainer, range.startOffset);
  const end = range.cloneRange(); end.selectNodeContents(element); end.setEnd(range.endContainer, range.endOffset);
  return { start: start.toString().length, end: end.toString().length };
}

export function restoreNoteSelection(element, start, end = start) {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  const locate = offset => {
    let remaining = offset;
    for (const node of nodes) {
      if (remaining <= node.length) return [node, remaining];
      remaining -= node.length;
    }
    const last = nodes.at(-1);
    return last ? [last, last.length] : [element, 0];
  };
  const [startNode, startOffset] = locate(start);
  const [endNode, endOffset] = locate(end);
  const range = document.createRange();
  range.setStart(startNode, startOffset); range.setEnd(endNode, endOffset);
  const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
}
