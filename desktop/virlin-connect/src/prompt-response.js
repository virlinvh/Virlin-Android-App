import { Marked } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import kotlin from 'highlight.js/lib/languages/kotlin';
import TurndownService from 'turndown';
import { gfm } from 'turndown-plugin-gfm';
import './prompt-response.css';

hljs.registerLanguage('kotlin', kotlin);
const escapeHtml = text => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const markdown = new Marked({ gfm: true, breaks: true, renderer: {
  code(token) {
    let text = token.text;
    const opening = token.raw.match(/^ {0,3}(`{3,}|~{3,})[^\r\n]*\r?\n/);
    if (opening) {
      const body = token.raw.slice(opening[0].length);
      const close = new RegExp(`^ {0,3}${opening[1][0]}{${opening[1].length},}[ \\t]*(?:\\r?\\n)?$`, 'm').exec(body);
      text = close ? body.slice(0, close.index) : body;
    }
    const lang = (token.lang || '').split(/\s/)[0].replace(/[^\w+#.-]/g, '');
    return `<pre><code${lang ? ` class="language-${escapeHtml(lang)}"` : ''}>${escapeHtml(text)}</code></pre>`;
  },
} });
const allowed = ['p','br','strong','b','em','i','u','s','del','h1','h2','h3','h4','h5','h6',
  'ul','ol','li','blockquote','pre','code','a','hr','table','thead','tbody','tr','th','td','span'];
function safeHtml(html) {
  return DOMPurify.sanitize(html, { ALLOWED_TAGS: allowed,
    ALLOWED_ATTR: ['href','title','class','start','colspan','rowspan'], ALLOW_DATA_ATTR: false,
    FORBID_CONTENTS: ['button','script','style','iframe','object','svg','math','noscript'] });
}

/** HTML is imported as portable Markdown, never as executable saved markup. */
export function importResponseClipboard(html, plain) {
  if (!html) return plain;
  if (html.length > 2 * 1024 * 1024) throw new Error('Clipboard HTML is too large');
  const doc = new DOMParser().parseFromString(safeHtml(html), 'text/html');
  if (!doc.body.querySelector('h1,h2,h3,h4,h5,h6,strong,b,em,i,ul,ol,blockquote,pre,code,table,a')) return plain;
  const converter = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced', bulletListMarker: '-' });
  converter.use(gfm);
  converter.addRule('source-code', {
    filter: 'pre', replacement: (_content, node) => {
      const code = node.querySelector('code') || node;
      const text = code.textContent || '';
      const language = ((code.className || '').match(/(?:language|lang)-([\w+#.-]+)/) || [])[1] || '';
      const longest = Math.max(2, ...[...text.matchAll(/`+/g)].map(match => match[0].length));
      const fence = '`'.repeat(longest + 1);
      return `\n\n${fence}${language}\n${text}${text.endsWith('\n') ? '' : '\n'}${fence}\n\n`;
    },
  });
  return converter.turndown(doc.body);
}

/** Rendering is display-only: the canonical response string is never rewritten. */
export function renderPromptResponse(host, source, { codeOnly = false, language = '', feedback = () => {} } = {}) {
  host.classList.add('prompt-response-document');
  if (codeOnly) {
    const pre = document.createElement('pre'), code = document.createElement('code');
    code.textContent = source; if (language) code.dataset.language = language;
    pre.append(code); host.replaceChildren(pre);
  } else host.innerHTML = safeHtml(markdown.parse(source, { async: false }));
  // Marked normalizes CRLF. Recover each fenced code body from the original source
  // before highlighting/copying, including its indentation and trailing blank lines.
  const originalCodes = [];
  if (!codeOnly) {
    const openings = /^ {0,3}(`{3,}|~{3,})[^\r\n]*\r?\n/gm;
    let opening;
    while ((opening = openings.exec(source))) {
      const start = openings.lastIndex;
      const closing = new RegExp(`^ {0,3}${opening[1][0]}{${opening[1].length},}[ \\t]*(?=\\r?$)`, 'gm');
      closing.lastIndex = start;
      const end = closing.exec(source);
      originalCodes.push(source.slice(start, end ? end.index : source.length));
      openings.lastIndex = end ? closing.lastIndex : source.length;
    }
  }
  host.querySelectorAll('a').forEach(link => {
    if (!/^(https?:|mailto:)/i.test(link.getAttribute('href') || '')) link.removeAttribute('href');
    link.target = '_blank'; link.rel = 'noopener noreferrer';
  });
  for (const pre of host.querySelectorAll('pre')) {
    const code = pre.querySelector('code'); if (!code) continue;
    let raw = code.textContent;
    const original = originalCodes.findIndex(text => text.replace(/\r\n|\r/g, '\n') === raw);
    if (original >= 0) { raw = originalCodes.splice(original, 1)[0]; code.textContent = raw; }
    const lang = code.dataset.language || (code.className.match(/language-([^\s]+)/) || [])[1] || '';
    // Bound work and never auto-detect untrusted, potentially large code strings.
    if (raw.length <= 32768 && lang && hljs.getLanguage(lang)) {
      try { code.innerHTML = hljs.highlight(raw, { language: lang, ignoreIllegals: true }).value; }
      catch { code.textContent = raw; }
    }
    const panel = document.createElement('section'); panel.className = 'prompt-response-code';
    const bar = document.createElement('div'); bar.className = 'prompt-response-code-bar';
    const label = document.createElement('span'); label.textContent = lang || 'Code';
    const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = 'Copy code';
    copy.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(raw); copy.textContent = 'Copied'; feedback('Code copied.'); }
      catch { feedback('Clipboard access was denied. Select the code to copy it.'); }
    });
    const expand = document.createElement('button'); expand.type = 'button'; expand.textContent = 'Expand'; expand.setAttribute('aria-expanded', 'false');
    expand.addEventListener('click', () => { const expanded = panel.classList.toggle('is-expanded'); expand.setAttribute('aria-expanded', String(expanded)); expand.textContent = expanded ? 'Collapse' : 'Expand'; });
    bar.append(label, expand, copy); pre.replaceWith(panel); panel.append(bar, pre);
  }
}

export async function copyPromptResponse(source, host) {
  if (typeof ClipboardItem === 'undefined') return navigator.clipboard.writeText(source);
  const copy = host.cloneNode(true);
  copy.querySelectorAll('.prompt-response-code-bar').forEach(bar => bar.remove());
  copy.querySelectorAll('pre').forEach(pre => { pre.style.cssText = 'white-space:pre;overflow-wrap:normal;background:#f0f4f2;padding:16px;font:14px/1.6 Consolas,monospace;'; });
  copy.querySelectorAll('code').forEach(code => { code.style.fontFamily = 'Consolas,monospace'; });
  for (const [selector, color] of Object.entries({ '.hljs-keyword,.hljs-selector-tag': '#7946ad',
    '.hljs-string,.hljs-attr': '#13664e', '.hljs-number,.hljs-literal': '#a04a21',
    '.hljs-comment': '#718178', '.hljs-title,.hljs-built_in': '#245da0' })) {
    copy.querySelectorAll(selector).forEach(token => { token.style.color = color; });
  }
  await navigator.clipboard.write([new ClipboardItem({ 'text/plain': new Blob([source], { type: 'text/plain' }),
    'text/html': new Blob([copy.innerHTML], { type: 'text/html' }) })]);
}
