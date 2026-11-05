/** Convert clipboard HTML to the phone's allowlisted block/run format. Never persist HTML. */
function argbColor(value) {
  const source = String(value || '').trim();
  const hex = source.match(/^#([\da-f]{3}|[\da-f]{6}|[\da-f]{8})$/i);
  if (hex) {
    let digits = hex[1];
    if (digits.length === 3) digits = [...digits].map(char => char + char).join('');
    if (digits.length === 8) return Number.parseInt(digits.slice(6) + digits.slice(0, 6), 16) >>> 0;
    return (0xff000000 + Number.parseInt(digits, 16)) >>> 0;
  }
  const rgb = source.match(/^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([\d.]+))?\s*\)$/i);
  if (!rgb) return undefined;
  const channels = rgb.slice(1, 4).map(Number);
  if (channels.some(channel => channel > 255)) return undefined;
  const alpha = rgb[4] == null ? 255 : Math.round(Math.min(1, Math.max(0, Number(rgb[4]))) * 255);
  return ((alpha * 0x1000000) + (channels[0] << 16) + (channels[1] << 8) + channels[2]) >>> 0;
}

export function parseRichNoteHtml(html, doc = new DOMParser().parseFromString(html, 'text/html')) {
  const blocks = [];
  const blockTags = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'BLOCKQUOTE', 'PRE', 'LI']);
  const ignored = new Set(['SCRIPT', 'STYLE', 'META', 'IMG', 'IFRAME', 'OBJECT']);
  const typeFor = (tag, listType) => (listType && ['LI', 'P', 'DIV'].includes(tag) ? listType : null) || ({ H1: 'HEADING_1', H2: 'HEADING_2', H3: 'HEADING_3',
    BLOCKQUOTE: 'QUOTE', PRE: 'CODE', LI: listType || 'BULLET' })[tag] || 'PARAGRAPH';
  const styled = (element, inherited) => {
    const marks = new Set(inherited.marks || []);
    const weight = element.style?.fontWeight || '';
    if (['B', 'STRONG'].includes(element.tagName) || weight === 'bold' || Number(weight) >= 600) marks.add('BOLD');
    if (['I', 'EM'].includes(element.tagName) || element.style?.fontStyle === 'italic') marks.add('ITALIC');
    const decoration = `${element.style?.textDecoration || ''} ${element.style?.textDecorationLine || ''}`;
    if (element.tagName === 'U' || decoration.includes('underline')) marks.add('UNDERLINE');
    if (['S', 'DEL', 'STRIKE'].includes(element.tagName) || decoration.includes('line-through')) marks.add('STRIKE');
    if (element.tagName === 'CODE') marks.add('CODE');
    const family = element.style?.fontFamily?.toLowerCase() || '';
    const fontFamily = family.includes('mono') ? 'Mono' : family.includes('serif') && !family.includes('sans') ?
      'Serif' : family.includes('sans') ? 'Inter' : inherited.fontFamily;
    const sizeMatch = element.style?.fontSize?.match(/^([\d.]+)(px|pt)$/i);
    const size = sizeMatch ? Math.round(Number(sizeMatch[1]) * (sizeMatch[2].toLowerCase() === 'pt' ? 4 / 3 : 1)) : inherited.fontSizeSp;
    const href = element.tagName === 'A' ? element.getAttribute('href') : inherited.linkUrl;
    const linkUrl = typeof href === 'string' && /^https?:\/\/\S+$/i.test(href) && href.length <= 2048 ? href : undefined;
    return { marks: [...marks], fontFamily, fontSizeSp: size >= 8 && size <= 96 ? size : undefined, linkUrl,
      color: argbColor(element.style?.color) ?? inherited.color,
      highlight: argbColor(element.style?.backgroundColor) ?? inherited.highlight };
  };
  const appendBlock = (element, listType, inherited, inheritedAlign) => {
    let text = '';
    const runs = [];
    const walk = (node, style) => {
      if (node.nodeType === 3) {
        const value = node.nodeValue || '';
        if (listType && /^[\s\u2022\u25cf\u25aa\u25e6]*$/.test(value)) return;
        if (!value) return;
        const start = text.length;
        text += value;
        if (style.marks?.length || style.fontFamily || style.fontSizeSp || style.linkUrl || style.color != null || style.highlight != null)
          runs.push({ start, end: text.length, ...style });
        return;
      }
      if (node.nodeType !== 1 || ignored.has(node.tagName)) return;
      if (node.tagName === 'UL' || node.tagName === 'OL') return;
      if (node.tagName === 'BR') { text += '\n'; return; }
      const next = styled(node, style);
      for (const child of node.childNodes) walk(child, next);
    };
    walk(element, inherited);
    if (!text.length) return;
    const compactRuns = [];
    for (const run of runs) {
      const previous = compactRuns.at(-1);
      const style = ({ start, end, ...rest }) => JSON.stringify(rest);
      if (previous && previous.end === run.start && style(previous) === style(run)) previous.end = run.end;
      else compactRuns.push(run);
    }
    const align = (element.style?.textAlign || inheritedAlign || 'LEFT').toUpperCase();
    blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: typeFor(element.tagName, listType), text,
      runs: compactRuns, align: ['LEFT', 'CENTER', 'RIGHT'].includes(align) ? align : 'LEFT', indent: 0, checked: false });
  };
  const visit = (parent, listType = null, inherited = { marks: [] }, align = 'LEFT') => {
    for (const child of parent.childNodes) {
      if (child.nodeType === 3) {
        if (child.nodeValue?.trim()) {
          const p = doc.createElement('p'); p.textContent = child.nodeValue;
          appendBlock(p, listType, inherited, align);
        }
        continue;
      }
      if (child.nodeType !== 1 || ignored.has(child.tagName)) continue;
      const next = styled(child, inherited);
      const childAlign = child.style?.textAlign?.toUpperCase() || align;
      if (child.tagName === 'UL' || child.tagName === 'OL') {
        visit(child, child.tagName === 'UL' ? 'BULLET' : 'NUMBERED', next, childAlign);
      } else if (child.tagName === 'LI') {
        // A list item may wrap its text in <p>/<div>. Keep that text and its
        // formatting in one block; nested lists become subsequent blocks.
        appendBlock(child, listType || 'BULLET', inherited, align);
        for (const nested of child.querySelectorAll?.('ul,ol') || []) {
          if (nested.closest?.('li') === child) visit(nested, nested.tagName === 'UL' ? 'BULLET' : 'NUMBERED', next, childAlign);
        }
      } else if ([...child.children].some(grandchild => blockTags.has(grandchild.tagName) ||
          grandchild.tagName === 'UL' || grandchild.tagName === 'OL') ||
          (!blockTags.has(child.tagName) && child.querySelector('p,div,h1,h2,h3,blockquote,pre,li,ul,ol'))) {
        visit(child, listType, next, childAlign);
      } else appendBlock(child, listType, inherited, align);
    }
  };
  visit(doc.body);
  if (blocks.length > 500 || blocks.reduce((sum, block) => sum + block.text.length, 0) > 32768 ||
      blocks.reduce((sum, block) => sum + block.runs.length, 0) > 3000)
    throw new Error('Pasted note exceeds the desktop note limit.');
  return blocks;
}

/** Recognize Markdown/plain-text list markers without changing ordinary prose pastes. */
export function parsePlainNoteText(value) {
  const lines = String(value).replace(/\r\n?/g, '\n').split('\n');
  const blocks = [];
  let paragraph = [];
  const flush = () => {
    if (!paragraph.length) return;
    blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: 'PARAGRAPH', text: paragraph.join('\n'),
      runs: [], align: 'LEFT', indent: 0, checked: false });
    paragraph = [];
  };
  for (const line of lines) {
    const bullet = line.match(/^\s*[-*+\u2022\u25cf\u25aa\u25e6]\s+(.+)$/);
    const numbered = line.match(/^\s*\d{1,4}[.)]\s+(.+)$/);
    if (bullet || numbered) {
      flush();
      blocks.push({ id: `desktop-${crypto.randomUUID()}`, type: bullet ? 'BULLET' : 'NUMBERED',
        text: (bullet || numbered)[1], runs: [], align: 'LEFT', indent: 0, checked: false });
    } else paragraph.push(line);
  }
  flush();
  return blocks;
}

function sliceRuns(runs, start, end) {
  return (runs || []).filter(run => run.end > start && run.start < end).map(run => ({ ...run,
    start: Math.max(run.start, start) - start, end: Math.min(run.end, end) - start }));
}

/** Replace a selection without dropping formatting on either side of the paste. */
export function insertRichNoteBlocks(blocks, index, start, end, pasted) {
  const original = blocks[index];
  if (!original || !pasted.length || start < 0 || end < start || end > (original.text || '').length)
    throw new Error('Invalid paste position.');
  const before = (original.text || '').slice(0, start);
  const after = (original.text || '').slice(end);
  const prefix = { ...original, text: before, runs: sliceRuns(original.runs, 0, start) };
  const suffix = { ...original, id: `desktop-${crypto.randomUUID()}`, text: after,
    runs: sliceRuns(original.runs, end, original.text.length) };
  const isHeading = /^HEADING_/.test(original.type || '');
  const replacement = [];
  if (before.length || isHeading) replacement.push(prefix);
  replacement.push(...pasted);
  if (after.length) replacement.push(suffix);
  return [...blocks.slice(0, index), ...replacement, ...blocks.slice(index + 1)];
}
