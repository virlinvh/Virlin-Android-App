import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/response-test', route => route.fulfill({ contentType: 'text/html', body: '<main id="response"></main>' }));
  await page.goto(`${process.env.KNOWLEDGE_TEST_URL || 'http://127.0.0.1:5173'}/response-test`);
  const result = await page.evaluate(async () => {
    const { importResponseClipboard, renderPromptResponse } = await import('/src/prompt-response.js');
    const source = '# Heading\n\n**Important** and `inline`.\n\n- First\n- Second\n\n```javascript\nconst x = "two  spaces";\n  console.log(x);\n```\n\n|A|B|\n|-|-|\n|1|2|';
    const host = document.querySelector('#response');
    renderPromptResponse(host, source);
    const formatted = { heading: host.querySelector('h1')?.textContent, bold: host.querySelector('strong')?.textContent,
      list: host.querySelectorAll('li').length, table: !!host.querySelector('table'), highlight: !!host.querySelector('.hljs-keyword'),
      code: host.querySelector('pre').textContent };
    const plain = '  text\n\n  - keep  spaces\r\n';
    const unchanged = importResponseClipboard('', plain) === plain;
    const imported = importResponseClipboard('<h2>Answer</h2><p><b>Important</b> answer</p><ol><li>One</li><li>Two</li></ol><pre><code class="language-python">  print("x")\n\n</code></pre>', 'fallback');
    const nested = importResponseClipboard('<pre><code class="language-js">const fence = "```";\n</code></pre>', 'fallback');
    renderPromptResponse(host, imported);
    const roundtrip = host.querySelector('pre').textContent;
    renderPromptResponse(host, '# Safe\n\n<script>window.hacked=true</script><img src=x onerror="window.hacked=true"><a href="javascript:alert(1)">bad</a>');
    const safe = !window.hacked && !host.querySelector('script,img,iframe,[onclick],[onerror],[href^="javascript:"]');
    renderPromptResponse(host, '  exact\n\tcode  ', { codeOnly: true, language: 'unknown-language' });
    const exactCode = host.querySelector('pre').textContent;
    return { formatted, unchanged, imported, nested, roundtrip, safe, exactCode };
  });
  assert.equal(result.formatted.heading, 'Heading'); assert.equal(result.formatted.bold, 'Important');
  assert.equal(result.formatted.list, 2); assert(result.formatted.table); assert(result.formatted.highlight);
  assert.equal(result.formatted.code, 'const x = "two  spaces";\n  console.log(x);\n');
  assert(result.unchanged); assert(result.imported.includes('## Answer')); assert(result.imported.includes('**Important**'));
  assert.equal(result.roundtrip, '  print("x")\n\n'); assert(result.nested.includes('````js'));
  assert(result.safe); assert.equal(result.exactCode, '  exact\n\tcode  ');
  await page.locator('.prompt-response-code-bar button', { hasText: 'Expand' }).click();
  assert(await page.locator('.prompt-response-code').evaluate(e => e.classList.contains('is-expanded')));
  await page.evaluate(() => { Object.defineProperty(navigator.clipboard, 'writeText', { configurable: true, value: async text => { window.copied = text; } }); });
  await page.getByRole('button', { name: 'Copy code' }).click();
  assert.equal(await page.evaluate(() => window.copied), '  exact\n\tcode  ');
  await page.evaluate(async () => {
    const { renderPromptResponse } = await import('/src/prompt-response.js');
    renderPromptResponse(document.querySelector('#response'), '```javascript\r\nconst x = 1;\r\n\r\n```');
  });
  await page.getByRole('button', { name: 'Copy code' }).click();
  assert.equal(await page.evaluate(() => window.copied), 'const x = 1;\r\n\r\n');
  assert.deepEqual(errors, []);
  console.log('PASS: rich paste, raw whitespace, Markdown headings/lists/tables, highlighted code, exact code copy, expand, unsafe HTML, and nested fences.');
} finally { await browser.close(); }
