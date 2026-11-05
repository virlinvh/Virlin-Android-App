import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.route('**/map-test', route => route.fulfill({ contentType: 'text/html', body: '<div id="workspace" class="workspace-app"><aside class="workspace-sidebar">Virlin</aside><main class="workspace-main"><header class="directory-header">Project</header><div id="project-list"></div></main></div>' }));
  await page.goto(`${process.env.MAP_TEST_URL || 'http://localhost:5173'}/map-test`);
  const mount = async readOnly => page.evaluate(async readOnly => {
    const { renderProjectMap } = await import('/src/project-map.js');
    const project = { id: 'p', title: 'Project' };
    const tree = { deviceId: 'test-phone', workstreams: [{ id: 'w', projectId: 'p', title: 'Workstream' }], tasks: [
      { id: 'a', projectId: 'p', workStreamId: 'w', title: 'A long title with sufficient words to test responsive wrapping' },
      { id: 'b', projectId: 'p', workStreamId: 'w', parentTaskId: 'a', title: 'Child' },
    ] };
    window.calls = [];
    document.querySelector('#project-list').replaceChildren(renderProjectMap(project, tree, { readOnly,
      onBack: () => window.calls.push('back'), onOpen: node => window.calls.push(node.kind),
      onCreate: () => window.calls.push('create'), onBulk: () => window.calls.push('bulk'),
      onRename: async (node, title) => window.calls.push(title),
    }));
  }, readOnly);
  await mount(false);
  await page.waitForTimeout(100);
  assert.equal(await page.locator('.virlin-map-node').count(), 4);
  await page.getByRole('button', { name: 'Expand mind map', exact: true }).click();
  assert.equal(await page.locator('.virlin-map').evaluate(el => el.classList.contains('is-expanded')), true);
  assert.equal(await page.locator('.virlin-map-stage').evaluate(el => el.clientHeight), 900);
  await page.getByRole('button', { name: 'Return to project page' }).click();
  await page.getByRole('button', { name: 'Collapse Workstream', exact: true }).click();
  assert.equal(await page.locator('.virlin-map-node').count(), 2);
  await page.getByRole('button', { name: 'Expand Workstream', exact: true }).click();
  assert.equal(await page.locator('.virlin-map-node').count(), 4);
  const node = page.locator('[data-key="task:a"]');
  const before = await node.boundingBox();
  await page.mouse.move(before.x + 35, before.y + 20); await page.mouse.down();
  await page.mouse.move(before.x + 95, before.y + 55, { steps: 8 }); await page.mouse.up();
  const after = await node.boundingBox(); assert.ok(after.x > before.x + 50);
  const edge = await node.locator('.virlin-map-resize').boundingBox();
  await page.mouse.move(edge.x + edge.width / 2, edge.y + 8); await page.mouse.down();
  await page.mouse.move(edge.x + 90, edge.y + 8, { steps: 8 }); await page.mouse.up();
  assert.ok((await node.boundingBox()).width > after.width + 60);
  const savedWidth = await node.evaluate(el => el.style.width);
  const savedX = await node.evaluate(el => el.style.left);
  assert.equal(await node.locator('strong').evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await page.locator('.virlin-map-launcher').click();
  await page.getByRole('button', { name: 'Edit selected box', exact: true }).click();
  await page.locator('.virlin-map-edit input').fill('Updated title');
  await page.getByRole('button', { name: 'Save title to phone' }).click();
  assert.ok(await page.evaluate(() => window.calls.includes('Updated title')));
  await mount(true);
  assert.equal(await page.locator('[data-key="task:a"]').evaluate(el => el.style.width), savedWidth);
  assert.equal(await page.locator('[data-key="task:a"]').evaluate(el => el.style.left), savedX);
  await page.locator('.virlin-map-node[data-key="task:a"]').click();
  await page.locator('.virlin-map-launcher').click();
  assert.equal(await page.getByRole('button', { name: 'Edit selected box', exact: true }).isDisabled(), true);
  assert.equal(await page.getByRole('button', { name: 'Create branch', exact: true }).isDisabled(), true);
  console.log('PASS: normal/full canvas, folding, drag, resize, wrapped text, confirmed rename, offline guards');
} finally { await browser.close(); }
