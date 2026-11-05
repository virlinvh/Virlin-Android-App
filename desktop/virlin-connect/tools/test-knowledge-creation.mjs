import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on('pageerror', e => { errors.push(e.message); console.error(e.message); });
  // Test-only instrumentation: production code has no debug entry points.
  await page.route('**/src/main.js*', async route => {
    const response = await route.fetch();
    await route.fulfill({ response, body: await response.text() + `\nwindow.creationTest = {
      open() { offlineMode=false; knowledgeTaskId=null; knowledgeCapabilities={knowledgeCreate:['note','checklist','prompt','link'],knowledgePromptSections:true};
        workspace.hidden=false; document.body.classList.add('workspace-active'); openKnowledgeTypePicker('project-test', document.querySelector('#sidebar-projects')); },
      readonly() { offlineMode=true; openKnowledgeComposer('project-test', null, 'checklist'); }
    };` });
  });
  await page.addInitScript(() => {
    window.calls = [];
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      window.calls.push({ command, args });
      if (command === 'offline_copy_list' || command === 'list_lan_addresses') return [];
      if (command === 'create_knowledge') {
        if (window.failNextSave) { window.failNextSave = false; throw new Error('Test connection interrupted'); }
        return JSON.stringify({ v: 1, type: 'knowledge_created', id: 'saved-item' });
      }
      if (command === 'project_knowledge') return JSON.stringify({ v: 1, type: 'knowledge', projectId: 'project-test', items: [] });
      return '';
    } };
  });
  await page.goto(process.env.KNOWLEDGE_TEST_URL || 'http://127.0.0.1:5173');
  await page.waitForFunction(() => window.creationTest);
  async function create(kind) {
    await page.evaluate(() => window.creationTest.open());
    await page.locator(`#knowledge-type-picker [data-kind="${kind}"]`).click();
    await page.locator('#knowledge-type-create').click();
  }
  await create('checklist');
  assert(await page.locator('.checklist-create-page').isVisible());
  assert(!await page.locator('#knowledge-kind').isVisible());
  assert(!await page.locator('#knowledge-content').isVisible());
  await page.locator('#knowledge-submit').click();
  assert.match(await page.locator('#knowledge-feedback').textContent(), /at least one task/);
  await page.locator('#knowledge-title').fill('Release checklist');
  async function addTasks(text) {
    await page.locator('.checklist-create-page .checklist-add').click();
    await page.locator('.checklist-batch-dialog textarea').fill(text);
    await page.locator('.checklist-batch-submit').click();
  }
  await addTasks('• Build APK\n1. Test Windows');
  await addTasks('["Review release", "Publish"]');
  assert.equal(await page.locator('.checklist-create-item').count(), 4);
  await page.locator('.checklist-create-page .checklist-add').click();
  await page.locator('.checklist-batch-dialog textarea').fill('[bad json');
  assert(await page.locator('.checklist-batch-submit').isDisabled());
  await page.locator('.checklist-batch-cancel').click();
  await page.evaluate(() => { window.failNextSave = true; });
  await page.locator('#knowledge-submit').click();
  await page.waitForFunction(() => document.querySelector('#knowledge-feedback').textContent.includes('Save not confirmed'));
  assert.equal(await page.locator('.checklist-create-item').count(), 4);
  await page.locator('#knowledge-submit').click();
  await page.waitForFunction(() => window.calls.some(c => c.command === 'create_knowledge'));
  let saved = await page.evaluate(() => window.calls.filter(c => c.command === 'create_knowledge').at(-1).args);
  assert.equal(saved.kind, 'checklist');
  assert.equal(saved.content, 'Build APK\nTest Windows\nReview release\nPublish');
  assert.equal(saved.title, 'Release checklist');
  assert(await page.evaluate(() => { const calls = window.calls.filter(c => c.command === 'create_knowledge'); return calls[0].args.key === calls[1].args.key; }));

  await create('prompt');
  assert(await page.locator('#knowledge-composer').evaluate(e => e.classList.contains('is-prompt-page')));
  await page.locator('#knowledge-title').fill('Build release');
  await page.locator('#knowledge-content').fill('Build safely.');
  await page.locator('[data-prompt-tab="code"]').click();
  await page.locator('#knowledge-code').fill('npm run build');
  await page.locator('[data-prompt-tab="response"]').click();
  await page.locator('#knowledge-response').fill('Build passed.');
  await page.locator('#knowledge-submit').click();
  await page.waitForFunction(() => window.calls.filter(c => c.command === 'create_knowledge').length === 3);
  saved = await page.evaluate(() => window.calls.filter(c => c.command === 'create_knowledge').at(-1).args);
  assert.equal(saved.kind, 'prompt'); assert.equal(saved.content, 'Build safely.');
  assert.equal(saved.codeText, 'npm run build'); assert.equal(saved.responseText, 'Build passed.');

  await create('link');
  assert(await page.locator('#knowledge-composer .knowledge-link-page').isVisible());
  assert(!await page.locator('#knowledge-url').isVisible());
  await page.locator('#knowledge-composer .knowledge-link-pen').click();
  await page.locator('#knowledge-composer .knowledge-link-input').fill('https://youtu.be/dQw4w9WgXcQ');
  await page.locator('#knowledge-composer .knowledge-link-apply').click();
  assert(await page.locator('#knowledge-composer .knowledge-link-video').isVisible());
  await page.locator('#knowledge-composer [data-link-tab="title"]').click();
  await page.locator('#knowledge-composer .knowledge-link-pen').click();
  await page.locator('#knowledge-composer .knowledge-link-input').fill('Video reference');
  await page.locator('#knowledge-composer .knowledge-link-apply').click();
  await page.locator('#knowledge-composer .knowledge-link-save').click();
  await page.waitForFunction(() => window.calls.filter(c => c.command === 'create_knowledge').length === 4);
  saved = await page.evaluate(() => window.calls.filter(c => c.command === 'create_knowledge').at(-1).args);
  assert.equal(saved.kind, 'link'); assert.equal(saved.title, 'Video reference');
  assert.equal(saved.url, 'https://youtu.be/dQw4w9WgXcQ');
  await create('prompt');
  await page.locator('#knowledge-content').fill('Review this response.');
  await page.locator('[data-prompt-tab="response"]').click();
  await page.locator('#knowledge-response').evaluate(area => {
    const data = new DataTransfer();
    data.setData('text/plain', 'Answer plain fallback');
    data.setData('text/html', '<h2>Answer</h2><p><b>Keep formatting</b></p><pre><code class="language-javascript">const x = "exact  spaces";\n\n</code></pre>');
    area.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: data }));
  });
  assert(await page.locator('#knowledge-response-field .prompt-response-document strong').isVisible());
  assert(await page.locator('#knowledge-response-field .hljs-keyword').isVisible());
  const responseSource = await page.locator('#knowledge-response').inputValue();
  await page.locator('#knowledge-prompt-edit').click();
  assert(await page.locator('#knowledge-response').isEditable());
  assert.equal(await page.locator('#knowledge-response').inputValue(), responseSource);
  await page.locator('#knowledge-prompt-edit').click();
  await page.locator('[data-prompt-tab="code"]').click();
  await page.locator('[data-prompt-tab="response"]').click();
  assert.equal(await page.locator('#knowledge-response').inputValue(), responseSource);
  await page.locator('#knowledge-submit').click();
  await page.waitForFunction(() => window.calls.filter(c => c.command === 'create_knowledge').length === 5);
  saved = await page.evaluate(() => window.calls.filter(c => c.command === 'create_knowledge').at(-1).args);
  assert.equal(saved.responseText, responseSource);
  await create('prompt');
  await page.locator('#knowledge-content').fill('Keep this draft until I confirm.');
  await page.locator('#knowledge-close').click();
  const warning = page.locator('#knowledge-discard-dialog');
  assert(await warning.isVisible());
  assert.equal(await page.evaluate(() => document.activeElement.id), 'knowledge-discard-stay');
  const box = await warning.boundingBox();
  assert(Math.abs(box.x + box.width / 2 - 720) < 2);
  assert(Math.abs(box.y + box.height / 2 - 500) < 2);
  await page.keyboard.press('Escape');
  await warning.waitFor({ state: 'hidden' });
  assert(!await warning.isVisible());
  assert.equal(await page.locator('#knowledge-content').inputValue(), 'Keep this draft until I confirm.');
  await page.locator('#knowledge-close').click();
  await page.locator('#knowledge-discard-stay').click();
  assert(await page.locator('#knowledge-composer').isVisible());
  await page.locator('#knowledge-cancel').click();
  await page.locator('#knowledge-discard-confirm').click();
  assert(!await page.locator('#knowledge-composer').isVisible());
  await page.evaluate(() => window.creationTest.readonly());
  assert(!await page.locator('#knowledge-composer').isVisible());
  assert.deepEqual(errors, []);
  console.log('PASS: picker routes, task list/JSON import, three prompt sections, YouTube link creation, save payloads, and offline guard.');
} finally { await browser.close(); }
