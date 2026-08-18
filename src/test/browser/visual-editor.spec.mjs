import { test, expect } from '@playwright/test';

async function nodeMenuAction(node,label){await node.getByRole('button',{name:/^Actions for /}).click();await node.getByRole('menuitem',{name:label,exact:true}).click();}
import AxeBuilder from '@axe-core/playwright';

const sessionPath = '/editor/session/11111111-1111-4111-8111-111111111111';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const originalVerify = crypto.subtle.verify.bind(crypto.subtle);
    Object.defineProperty(crypto.subtle, 'verify', {
      configurable: true,
      value: async (...args) => args[0] === 'Ed25519' ? true : originalVerify(...args)
    });
    window.__personaServerAvailable = true;
    class FixtureWebSocket {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(url) {
        this.url = url; this.readyState = FixtureWebSocket.CONNECTING;
        this.sequence = 0;
        window.__personaSocket = this;
        queueMicrotask(() => {
          if (!window.__personaServerAvailable) {
            this.readyState = FixtureWebSocket.CLOSED;
            this.onerror?.(new Event('error')); this.onclose?.({ code: 1012, reason: 'Persona unavailable' });
          } else {
            this.readyState = FixtureWebSocket.OPEN; this.onopen?.(new Event('open'));
          }
        });
      }
      send(serialized) {
        const message = JSON.parse(serialized);
        this.sent = this.sent || []; this.sent.push(message);
        if (message.type === 'VALIDATION_REQUEST') setTimeout(() => this.onmessage?.({ data: JSON.stringify({
          protocolVersion: 3, sessionId: '11111111-1111-4111-8111-111111111111', sequence: ++this.sequence,
          type: 'VALIDATION_RESULT', signature: btoa(String.fromCharCode(...new Uint8Array(64))), payload: {
            protocolVersion: 3, requestId: message.payload.requestId, valid: true, diagnostics: [],
            proposedRevision: 'a'.repeat(64), contentFormatVersion: 1
          }
        }) }), 0);
      }
      close() {
        if (this.readyState === FixtureWebSocket.CLOSED) return;
        this.readyState = FixtureWebSocket.CLOSED; queueMicrotask(() => this.onclose?.({ code: 1000, reason: 'closed' }));
      }
      forceDisconnect() {
        window.__personaServerAvailable = false; this.readyState = FixtureWebSocket.CLOSED;
        this.onerror?.(new Event('error')); this.onclose?.({ code: 1012, reason: 'Persona disconnected' });
      }
    }
    Object.defineProperty(window, 'WebSocket', { configurable: true, value: FixtureWebSocket });
    window.__disconnectPersona = () => window.__personaSocket.forceDisconnect();
    window.__setPersonaServerAvailable = value => { window.__personaServerAvailable = value; };
  });
  await page.goto(sessionPath);
});

async function connect(page) {
  await page.getByLabel('Verification code').fill('ABC123');
  await page.getByRole('button', { name: 'Verify browser' }).click();
  await expect(page.locator('#workspace')).toBeVisible();
  if (await page.locator('#content-browser').isHidden()) await page.locator('#browser-toggle').click();
  await page.locator('#project').getByText('demo:walker', { exact: true }).click();
  await expect(page.locator('.graph-node-card')).toHaveCount(3);
}

async function createResource(page, kind, id) {
  await page.keyboard.press('Control+n');
  await page.locator('#create-kind').selectOption(kind);
  await page.locator('#create-id').fill(id);
  await expect(page.getByRole('button', { name: 'Create and open' })).toBeEnabled();
  await page.getByRole('button', { name: 'Create and open' }).click();
  await expect(page.locator('#create-dialog')).toBeHidden();
}

test('keeps creation read-only until Draft Edit trust is granted', async ({ page }) => {
  await page.route('**/verify', async route => {
    const response = await route.fetch();
    const verified = await response.json();
    await route.fulfill({ response, json: { ...verified, capabilities: ['CONTENT_VIEW'] } });
  });
  await page.route('**/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ grantedCapabilities: ['CONTENT_VIEW'] })
  }));

  await connect(page);
  const create = page.getByRole('button', { name: 'Create', exact: true });
  await expect(create).toBeDisabled();
  await expect(create).toHaveAttribute('title', 'Requires Draft Edit trust in Minecraft');

  await page.keyboard.press('Control+n');
  await expect(page.locator('#create-dialog')).toBeHidden();
  await expect(page.locator('#status')).toContainText('Approve DRAFT_EDIT in Minecraft');
});

test('opens only after authentication and renders accessible nodes, pins, and wires', async ({ page }) => {
  await expect(page.locator('#workspace')).toBeHidden();
  await expect(page.getByText('locally editable fallback')).toHaveCount(0);
  await expect(page.locator('[id^="import"]')).toHaveCount(0);
  await connect(page);

  await expect(page.locator('#project')).toContainText('demo:walker');
  await expect(page.locator('.tab-open', { hasText: 'demo:walker' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('group', { name: /root, sequence/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /output pin 1, type execution/ })).toBeVisible();
  await expect(page.locator('.graph-wire')).toHaveCount(1);
  await expect(page.locator('.graph-node-card.custom-yaml')).toContainText('Custom YAML');

  await page.keyboard.press('Control+p');
  await expect(page.getByRole('dialog').filter({ has: page.getByLabel('Quick open') })).toBeVisible();
  await page.locator('#quick-open-results').getByRole('button', { name: /demo:walker/ }).click();
  await expect(page.locator('#quick-open')).toBeHidden();
});

test('keeps Inspector ports, textual connections, source ranges, and graph state synchronized', async ({ page }) => {
  await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ });
  await root.click();
  await expect(root).toHaveAccessibleName(/selected, not live, editable/);
  await expect(page.locator('#inspector-content').getByRole('heading', { name: 'Ports' })).toBeVisible();
  await expect(page.locator('#inspector-content').getByRole('heading', { name: 'Connections' })).toBeVisible();
  await expect(page.locator('#inspector-content').getByRole('button', { name: /To wait-one via 1/ })).toBeVisible();
  await expect(root.getByRole('button', { name: /output pin 1.*1 current connection/ })).toBeVisible();
  await page.locator('#inspector-content').getByRole('button', { name: /To wait-one via 1/ }).click();
  await expect(page.getByRole('group', { name: /wait-one, wait/ })).toBeFocused();
});

test('provides complete local alignment, snap, tidy, focus, and one-pixel keyboard layout commands', async ({ page }) => {
  await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ });
  const wait = page.getByRole('group', { name: /wait-one, wait/ });
  await root.click(); await wait.click({ modifiers: ['Control'] });
  await page.getByRole('button', { name: 'Align middle' }).click();
  const middle = await Promise.all([root, wait].map(node => node.evaluate(element => element.style.transform)));
  expect(middle[0]).not.toBe(''); expect(middle[1]).not.toBe('');
  await page.getByRole('button', { name: 'Snap selection' }).click();
  await page.getByRole('button', { name: 'Tidy selection' }).click();
  await root.focus(); const before = await root.getAttribute('style');
  await page.keyboard.press('Control+ArrowRight');
  await expect.poll(() => root.getAttribute('style')).not.toBe(before);
  await page.keyboard.press('Control+Shift+ArrowDown');
  await page.getByRole('button', { name: 'Focus connected' }).click();
  await expect(page.getByRole('group', { name: /Custom YAML/ })).toHaveClass(/graph-dimmed/);
  await page.locator('#graph-canvas').focus(); await page.keyboard.press('Escape');
  await expect(page.getByRole('group', { name: /Custom YAML/ })).not.toHaveClass(/graph-dimmed/);
});

test('supports accessible resource-tab close-others and reopen workflows', async ({ page }) => {
  await connect(page);
  await page.locator('#project').getByText('demo:target', { exact: true }).click();
  const target = page.locator('.tab-open', { hasText: 'demo:target' });
  await target.click({ button: 'right' });
  await expect(page.getByRole('menu')).toBeVisible();
  await page.getByRole('menuitem', { name: 'Close others' }).click();
  await expect(page.locator('.resource-tab')).toHaveCount(1);
  await page.keyboard.press('Control+Shift+t');
  await expect(page.locator('.resource-tab')).toHaveCount(2);
});

test('supports canvas zoom, node movement, selection synchronization, and keyboard traversal', async ({ page }) => {
  await connect(page);
  const plane = page.locator('#graph-plane');
  const beforeZoom = await plane.getAttribute('style');
  await page.locator('#graph-canvas').hover();
  await page.mouse.wheel(0, -300);
  await expect.poll(() => plane.getAttribute('style')).not.toBe(beforeZoom);
  await expect(page.locator('#graph-zoom')).not.toHaveText('100%');

  const root = page.getByRole('group', { name: /root, sequence/ });
  const beforeMove = await root.getAttribute('style');
  const header = root.locator('.graph-node-header');
  const box = await header.boundingBox();
  await page.mouse.move(box.x + 20, box.y + 15);
  await page.mouse.down();
  await page.mouse.move(box.x + 90, box.y + 55);
  await page.mouse.up();
  await expect.poll(() => root.getAttribute('style')).not.toBe(beforeMove);
  const moved = await root.getAttribute('style');
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(root).toHaveAttribute('style', beforeMove);
  await page.getByRole('button', { name: 'Redo' }).click();
  await expect(root).toHaveAttribute('style', moved);

  await root.click();
  await expect(page.locator('#inspector-selection')).toHaveText('root');
  await expect.poll(() => page.locator('#source').evaluate(element => element.selectionStart)).toBeGreaterThan(0);
  await root.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('group', { name: /wait-one, wait/ })).toHaveAttribute('aria-current', 'true');
});

test('inserts through the digest-checked graph palette and preserves custom YAML', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: 'Add node' }).click();
  await expect(page.getByRole('dialog').filter({ has: page.getByLabel('Add graph node') })).toBeVisible();
  const search = page.locator('#palette-search');
  await search.pressSequentially('wait');
  await expect(search).toBeFocused();
  await expect(search).toHaveValue('wait');
  page.once('dialog', dialog => dialog.accept('wait-two'));
  await page.locator('#palette-results').getByRole('button', { name: 'Wait', exact: true }).click();

  await expect(page.getByRole('group', { name: /wait-two, wait/ })).toBeVisible();
  await expect(page.locator('.graph-node-card')).toHaveCount(4);
  await expect(page.locator('#source')).toHaveValue(/future: !vendor retained/);
  await expect(page.locator('#yaml-status')).toContainText('authoritative operation');
  await expect(page.getByRole('button', { name: 'Undo' })).toBeEnabled();
});

test('reorders behavior children visually through stable neighboring ports', async ({ page }) => {
  await connect(page);
  page.once('dialog', dialog => dialog.accept('second'));
  await page.getByRole('button', { name: 'Add node' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Wait' }).click();
  const second = page.getByRole('group', { name: /second, wait/ }); await expect(second).toBeVisible();
  await nodeMenuAction(second, 'Move earlier');
  await expect.poll(async () => {
    const value = await page.locator('#source').inputValue();
    return value.indexOf('id: second') < value.indexOf('id: wait-one');
  }).toBe(true);
});

test('discovers signed extension nodes in the shared palette and applies a narrow authoritative patch', async ({ page }) => {
  await connect(page);
  const before = await page.locator('#source').inputValue();
  await page.getByRole('button', { name: 'Add node' }).click();
  await page.locator('#palette-search').fill('vendor:wave');
  await expect(page.locator('#palette-results')).toContainText('Extension · vendor:wave');
  page.once('dialog', dialog => dialog.accept('wave-one'));
  await page.locator('#palette-results').getByRole('button', { name: 'Extension · vendor:wave' }).click();

  await expect(page.getByRole('group', { name: /wave-one, action/ })).toBeVisible();
  await expect(page.locator('#source')).toHaveValue(/- id: wave-one\n      type: action\n      action: vendor:wave/);
  await expect(page.locator('#source')).toHaveValue(/future: !vendor retained/);
  expect((await page.locator('#source').inputValue()).replace(
    /    - id: wave-one\n      type: action\n      action: vendor:wave\n/, '')).toBe(before);
});

test('inserts a compatible node on a behavior wire as one authoritative gesture', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: /Insert node on 1 connection/ }).click();
  await page.locator('#palette-search').fill('checkpoint');
  page.once('dialog', dialog => dialog.accept('before-wait'));
  await page.locator('#palette-results').getByRole('button', { name: 'Checkpoint', exact: true }).click();
  await expect(page.getByRole('group', { name: /before-wait, checkpoint/ })).toBeVisible();
  const yaml = await page.locator('#source').inputValue();
  expect(yaml.indexOf('id: before-wait')).toBeLessThan(yaml.indexOf('id: wait-one'));
  await expect(page.locator('#yaml-status')).toContainText('1 authoritative operation');
});

test('copies an exact node across compatible behavior tabs through the authoritative server', async ({ page }) => {
  await connect(page);
  await page.getByRole('group', { name: /wait-one, wait/ }).click();
  await page.getByRole('button', { name: 'Copy node' }).click();
  await createResource(page, 'behavior', 'demo:copy-target');
  await page.getByRole('group', { name: /root, sequence/ }).click();
  page.once('dialog', dialog => dialog.accept('wait-copy'));
  await page.getByRole('button', { name: 'Paste node' }).click();
  await expect(page.getByRole('group', { name: /wait-copy, wait/ })).toBeVisible();
  await expect(page.locator('#source')).toHaveValue(/- id: wait-copy\n      type: wait\n      duration: 1s/);
  await expect(page.locator('#yaml-status')).toContainText('Paste copied behavior node applied');
});

test('extracts a dialogue command to a reusable script and opens the new authoritative graph', async ({ page }) => {
  await connect(page);
  await createResource(page, 'dialogue', 'demo:extract-source');
  const say = page.getByRole('group', { name: /New dialogue line, script-say/ });
  page.once('dialog', dialog => dialog.accept('extracted-line'));
  await nodeMenuAction(say, 'Extract to reusable script');
  await expect(page.locator('.tab-open', { hasText: 'extracted-line' })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#source')).toHaveValue(/extracted-line:\n    inputs: \{\}\n    outputs: \{\}\n    nodes:/);
});

test('atomically creates and assigns an NPC reference before opening the new target', async ({ page }) => {
  await connect(page);
  await createResource(page, 'npc', 'demo:assign-source');
  page.once('dialog', dialog => dialog.accept('demo:assigned-walk'));
  await nodeMenuAction(page.getByRole('group', { name: /demo:assign-source, npc/ }), 'Create and assign player behavior');
  await expect(page.locator('.tab-open', { hasText: 'demo:assigned-walk' })).toHaveAttribute('aria-current', 'page');
  await page.locator('#project button', { hasText: 'demo:assign-source' }).click();
  await expect(page.locator('#source')).toHaveValue(/player-behavior: demo:assigned-walk/);
  await expect(page.locator('#project')).toContainText('demo:assigned-walk');
});

test('supports keyboard pin gestures and structured server rejection on wire disconnect', async ({ page }) => {
  await connect(page);
  const wait = page.getByRole('group', { name: /wait-one, wait/ });
  await wait.getByRole('button', { name: /output pin result/ }).click();
  await page.getByRole('group', { name: /root, sequence/ }).getByRole('button', { name: /input pin in/ }).click();
  await expect(page.locator('#yaml-status')).toContainText('cycle');

  await page.getByRole('button', { name: /Disconnect 1 connection/ }).click();
  await expect(page.locator('#yaml-status')).toContainText('ORPHAN_NOT_ALLOWED');
  await expect(page.locator('.graph-wire')).toHaveCount(1);
});

test('recovers from stale graph digests without applying or retaining a false undo entry', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: 'Add node' }).click();
  await page.locator('#palette-search').fill('wait');
  page.once('dialog', dialog => dialog.accept('stale-node'));
  await page.locator('#palette-results').getByRole('button', { name: 'Wait', exact: true }).click();
  await expect(page.locator('#status')).toContainText('conflicted');
  await expect(page.getByRole('group', { name: /stale-node/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Undo' })).toBeDisabled();
  await expect(page.locator('.graph-node-card')).toHaveCount(3);
});

test('discards an in-flight graph mutation when the authenticated server disconnects', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: 'Add node' }).click();
  await page.locator('#palette-search').fill('wait');
  page.once('dialog', dialog => dialog.accept('slow-node'));
  await page.locator('#palette-results').getByRole('button', { name: 'Wait', exact: true }).click();
  await page.evaluate(() => window.__disconnectPersona());
  await expect(page.locator('#workspace')).toBeHidden();
  await page.waitForTimeout(400);
  await page.evaluate(() => window.__setPersonaServerAvailable(true));
  await page.locator('#reconnect-now').click();
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.getByRole('group', { name: /slow-node/ })).toHaveCount(0);
  await expect(page.locator('#source')).not.toHaveValue(/slow-node/);
});

test('creates a resource through the server-previewed wizard and integrates it into tabs and dirty state', async ({ page }) => {
  await connect(page);
  await page.keyboard.press('Control+n');
  await expect(page.locator('#create-dialog')).toBeVisible();
  await page.locator('#create-kind').selectOption('npc');
  await page.locator('#create-id').fill('demo:guide');
  await expect(page.locator('#create-path')).toHaveValue('npcs/guide.yml');
  await expect(page.locator('#create-preview')).toHaveValue(/display-name/);
  await page.getByRole('button', { name: 'Create and open' }).click();

  await expect(page.locator('#create-dialog')).toBeHidden();
  await expect(page.locator('#content-browser')).toContainText('demo:guide');
  await expect(page.locator('.tab-open', { hasText: 'New NPC' })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#project .resource-badge.dirty')).toHaveCount(1);
});

test('integrates all five creation kinds with recovery, draft validation, semantic diff, export, and publication', async ({ page }) => {
  await connect(page);
  for (const [kind, id] of [['npc', 'demo:lifecycle-npc'], ['dialogue', 'demo:lifecycle-dialogue'],
    ['quest', 'demo:lifecycle-quest'], ['behavior', 'demo:lifecycle-behavior'], ['script', 'lifecycle-script']])
    await createResource(page, kind, id);

  await expect(page.locator('#project .resource-badge.dirty')).toHaveCount(5);
  await expect(page.locator('#validation-summary')).toContainText('Validated by Persona');
  await page.waitForTimeout(400);
  const recovery = await page.evaluate(() => JSON.parse(sessionStorage.getItem(
    'persona:recovery:11111111-1111-4111-8111-111111111111')));
  expect(recovery.version).toBe(2);
  expect(Object.values(recovery.changes).filter(value => value !== null)).toHaveLength(5);

  await page.getByRole('button', { name: 'Semantic diff' }).click();
  await expect(page.locator('#semantic-diff-summary')).toContainText('5 semantic changes');
  await page.locator('#semantic-diff-close').click();

  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download project' }).click();
  expect((await download).suggestedFilename()).toBe('persona-project.zip');

  await expect(page.getByRole('button', { name: 'Request publication' })).toBeEnabled();
  await page.getByRole('button', { name: 'Request publication' }).click();
  await expect(page.locator('#status')).toContainText('published revision');

  await page.reload();
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#status')).toContainText('Recovered unsaved changes');
  for (const id of ['demo:lifecycle-npc', 'demo:lifecycle-dialogue', 'demo:lifecycle-quest',
    'demo:lifecycle-behavior', 'lifecycle-script']) await expect(page.locator('#content-browser')).toContainText(id);
});

test('duplicates, atomically renames, safely locates, and deletes resources without reviving deleted YAML', async ({ page }) => {
  await connect(page);
  await createResource(page, 'behavior', 'demo:operations');

  const duplicateDialogs = dialog => dialog.type() === 'prompt'
    ? dialog.accept('demo:operations-copy') : dialog.accept();
  page.on('dialog', duplicateDialogs);
  await page.getByRole('button', { name: 'Duplicate', exact: true }).click();
  await expect(page.locator('#content-browser')).toContainText('demo:operations-copy');
  page.off('dialog', duplicateDialogs);

  const renameDialogs = dialog => dialog.type() === 'prompt'
    ? dialog.accept('demo:operations-renamed') : dialog.accept();
  page.on('dialog', renameDialogs);
  await page.getByRole('button', { name: 'Rename', exact: true }).click();
  await expect(page.locator('#content-browser')).toContainText('demo:operations-renamed');
  page.off('dialog', renameDialogs);

  await page.getByRole('button', { name: 'Move to safe path' }).click();
  await expect(page.locator('#status')).toContainText('canonical server-approved path');

  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.locator('#content-browser')).not.toContainText('demo:operations-renamed');
  await expect(page.locator('#source')).not.toHaveValue(/demo:operations-renamed/);
  await page.waitForTimeout(400);
  const recovery = await page.evaluate(() => JSON.parse(sessionStorage.getItem(
    'persona:recovery:11111111-1111-4111-8111-111111111111')));
  expect(Object.values(recovery.changes)).not.toContain(expect.stringContaining('demo:operations-renamed'));
});

test('restores graph selection and viewport independently for each open resource tab', async ({ page }) => {
  await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ });
  await root.click();
  const header = root.locator('.graph-node-header'), box = await header.boundingBox();
  await page.mouse.move(box.x + 20, box.y + 15); await page.mouse.down();
  await page.mouse.move(box.x + 110, box.y + 65); await page.mouse.up();
  const savedStyle = await root.getAttribute('style');

  await page.keyboard.press('Control+n');
  await page.locator('#create-kind').selectOption('npc');
  await page.locator('#create-id').fill('demo:context');
  await page.getByRole('button', { name: 'Create and open' }).click();
  await page.locator('#project').getByText('demo:walker', { exact: true }).click();

  await expect(root).toHaveAttribute('style', savedStyle);
  await expect(root).toHaveAttribute('aria-current', 'true');
});

test('keeps bounded comments, groups, bookmarks, color labels, collapse state, and layout undo outside YAML', async ({ page }) => {
  await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ });
  const wait = page.getByRole('group', { name: /wait-one, wait/ });
  await root.click(); await wait.click({ modifiers: ['Control'] });
  await expect(root).toHaveAttribute('aria-current', 'true');
  await expect(wait).toHaveAttribute('aria-current', 'true');

  page.once('dialog', dialog => dialog.accept('Opening sequence'));
  await page.getByRole('button', { name: 'Group', exact: true }).click();
  page.once('dialog', dialog => dialog.accept('Review this branch'));
  await page.getByRole('button', { name: 'Comment', exact: true }).click();
  page.once('dialog', dialog => dialog.accept('#336699'));
  await page.getByRole('button', { name: 'Color label' }).click();
  await expect(page.locator('.graph-layout-group')).toContainText('Opening sequence');
  await expect(page.locator('.graph-layout-comment')).toContainText('Review this branch');
  await page.getByRole('group', { name: /Custom YAML/ }).click();
  await expect(root).toHaveCSS('border-color', 'rgb(51, 102, 153)');

  await nodeMenuAction(root, 'Bookmark');
  await nodeMenuAction(root, 'Collapse');
  await page.getByRole('button', { name: /Add layout-only reroute/ }).click();
  await expect(page.getByRole('button', { name: /Reroute 1/ })).toBeVisible();
  await expect(root).toHaveClass(/bookmarked/); await expect(root).toHaveClass(/collapsed/);
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByRole('button', { name: /Reroute 1/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(root).not.toHaveClass(/collapsed/);
  await page.getByRole('button', { name: 'Redo' }).click();
  await expect(root).toHaveClass(/collapsed/);
  await page.getByRole('button', { name: 'Redo' }).click();
  await expect(page.getByRole('button', { name: /Reroute 1/ })).toBeVisible();

  await page.locator('#project').getByText('demo:target', { exact: true }).click();
  await page.locator('#project').getByText('demo:walker', { exact: true }).click();
  await expect(page.locator('.graph-layout-group')).toContainText('Opening sequence');
  await expect(page.locator('.graph-layout-comment')).toContainText('Review this branch');
  await expect(page.getByRole('button', { name: /Reroute 1/ })).toBeVisible();
  await expect(page.locator('#source')).toHaveValue(/future: !vendor retained/);
});

test('moves layout-only wire reroute points and restores their final drag with undo and redo', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: /Add layout-only reroute/ }).click();
  const reroute = page.getByRole('button', { name: /Reroute 1/ });
  await expect(reroute).toBeVisible();
  const before = { x: await reroute.getAttribute('cx'), y: await reroute.getAttribute('cy') };
  const box = await reroute.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + 75, box.y + 45, { steps: 4 });
  await page.mouse.up();
  await expect.poll(async () => ({ x: await reroute.getAttribute('cx'), y: await reroute.getAttribute('cy') }))
    .not.toEqual(before);
  const moved = { x: await reroute.getAttribute('cx'), y: await reroute.getAttribute('cy') };
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect.poll(async () => ({ x: await reroute.getAttribute('cx'), y: await reroute.getAttribute('cy') }))
    .toEqual(before);
  await page.getByRole('button', { name: 'Redo' }).click();
  await expect.poll(async () => ({ x: await reroute.getAttribute('cx'), y: await reroute.getAttribute('cy') }))
    .toEqual(moved);
});

test('opens quest objectives and lifecycle commands as a nested authoritative subgraph with breadcrumbs', async ({ page }) => {
  await connect(page);
  await createResource(page, 'quest', 'demo:nested');
  await expect(page.getByRole('group', { name: /start, quest-phase/ })).toBeVisible();
  await nodeMenuAction(page.getByRole('group', { name: /start, quest-phase/ }), 'Open objectives');
  await expect(page.locator('#breadcrumbs')).toContainText('Phase start');
  await expect(page.locator('#breadcrumbs')).toContainText('Objectives & lifecycle');
  await expect(page.getByRole('group', { name: /begin, quest-objective/ })).toBeVisible();
  await expect(page.getByRole('group', { name: /demo:nested, quest/ })).toHaveCount(0);
  await page.locator('#breadcrumbs').getByRole('button', { name: 'New quest' }).click();
  await expect(page.getByRole('group', { name: /demo:nested, quest/ })).toBeVisible();
});

test('relationship map exposes resolved typed links with keyboard open-source and open-target actions', async ({ page }) => {
  await connect(page);
  await page.getByRole('button', { name: 'Relationship Map' }).click();
  await expect(page.getByRole('group', { name: /demo:walker, relationship-behavior/ })).toBeVisible();
  await expect(page.getByRole('group', { name: /demo:target, relationship-behavior/ })).toBeVisible();
  await expect(page.locator('.graph-wire[data-type="reference:behavior"]')).toHaveCount(1);
  await expect(page.locator('.graph-wire.cyclic')).toHaveCount(1);
  await expect(page.locator('.graph-wire.unresolved')).toHaveCount(1);
  await expect(page.getByRole('group', { name: /demo:missing, missing-reference/ })).toContainText('unresolved');
  await nodeMenuAction(page.getByRole('group', { name: /demo:missing, missing-reference/ }), 'Create missing');
  await expect(page.locator('.tab-open', { hasText: 'demo:missing' })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: 'Relationship Map' }).click();
  await page.getByRole('button', { name: 'Open relationship target' }).first().focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.tab-open', { hasText: 'demo:target' })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: 'Relationship Map' }).click();
  await page.getByRole('button', { name: 'Open relationship source' }).first().click();
  await expect(page.locator('.tab-open', { hasText: 'demo:walker' })).toHaveAttribute('aria-current', 'page');
});

test('renders trusted live runtime state as a read-only graph overlay without changing YAML', async ({ page }) => {
  await connect(page); const before = await page.locator('#source').inputValue();
  await page.getByRole('button', { name: 'Live server' }).click();
  await page.evaluate(() => {
    const socket = window.__personaSocket;
    const subscriptionId = socket.sent.find(message => message.type === 'LIVE_SUBSCRIBE').payload.subscriptionId;
    socket.onmessage({ data: JSON.stringify({ protocolVersion: 3,
      sessionId: '11111111-1111-4111-8111-111111111111', sequence: 100, type: 'LIVE_SNAPSHOT',
      signature: btoa(String.fromCharCode(...new Uint8Array(64))), payload: { protocolVersion: 3,
        subscriptionId, revision: 1, full: true, removedKeys: [], players: [], npcs: [], quests: [], dialogues: [], memories: [],
        behaviors: [{ definitionId: 'demo:npc', instanceId: 'one', playerId: 'player', behaviorId: 'demo:walker',
          status: 'RUNNING', runningPath: ['root', 'wait-one'], checkpoint: 'wait-one', nextWakeAt: null,
          inbox: [], droppedEvents: 0, recentOutcomes: [], recentConditions: [] }], server: null }
    }) });
  });
  await expect(page.getByRole('group', { name: /root, sequence/ })).toHaveClass(/live-active/);
  await expect(page.getByRole('group', { name: /wait-one, wait/ })).toHaveClass(/live-active/);
  await expect(page.locator('#live-mode')).toContainText('read only');
  await expect(page.locator('#live-controls')).toBeHidden();
  await expect(page.locator('#source')).toHaveValue(before);
  await page.locator('#live-close').click();
});

test('locks immediately on server loss and refreshes authoritative state before editing resumes', async ({ page }) => {
  await connect(page);
  await page.evaluate(() => window.__disconnectPersona());
  await expect(page.locator('#workspace')).toBeHidden();
  await expect(page.locator('#reconnect')).toBeVisible();
  await expect(page.locator('#source')).toBeDisabled();
  await expect(page.locator('#status')).toContainText('locked');

  await page.evaluate(() => window.__setPersonaServerAvailable(true));
  await page.locator('#reconnect-now').click();
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#status')).toContainText('Connected securely');
  await expect(page.locator('#source')).toBeEnabled();
  await expect(page.locator('.graph-node-card')).toHaveCount(3);
});

test('responsive and reduced-motion modes retain keyboard-visible controls', async ({ page }) => {
  await page.setViewportSize({ width: 680, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'dark' });
  await connect(page);
  await expect(page.locator('#content-browser')).toBeHidden();
  await expect(page.locator('#graph-canvas')).toBeVisible();
  await page.locator('#browser-toggle').click();
  await expect(page.locator('#content-browser')).toBeVisible();
  await page.keyboard.press('Escape');
  await page.locator('#graph-canvas').focus();
  await expect(page.locator('#graph-canvas')).toBeFocused();
  await expect(page.getByRole('separator', { name: 'Resize Inspector' })).toBeHidden();
});

test('matches the bounded desktop hierarchy and persists clamped presentation-only layout', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 }); await connect(page);
  const toolbar = await page.locator('.global-toolbar').boundingBox();
  const browser = await page.locator('#content-browser').boundingBox();
  const inspector = await page.locator('#inspector').boundingBox();
  const dock = await page.locator('#output-dock').boundingBox();
  const statusBar = await page.locator('#workspace-status').boundingBox();
  expect(toolbar.height).toBeLessThanOrEqual(48); expect(browser.width).toBe(280);
  expect(inspector.width).toBe(320); expect(dock.height).toBe(34); expect(statusBar.height).toBe(24);
  await expect(page.locator('.source-pane')).toBeHidden();

  const splitter = page.getByRole('separator', { name: 'Resize content browser' });
  await splitter.focus(); await page.keyboard.press('ArrowRight');
  await expect(page.locator('#content-browser')).toHaveCSS('width', '288px');
  await page.reload(); await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#content-browser')).toHaveCSS('width', '288px');
  await page.evaluate(() => {
    const key = Object.keys(localStorage).find(value => value.startsWith('persona:panel-layout:v2:'));
    const layout = JSON.parse(localStorage.getItem(key)); layout.browserWidth = 50_000; localStorage.setItem(key, JSON.stringify(layout));
  });
  await page.reload(); await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#content-browser')).toHaveCSS('width', '480px');
});

test('uses exclusive narrow drawers and restores graph focus after Escape', async ({ page }) => {
  await page.setViewportSize({ width: 680, height: 900 }); await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ }); await root.click();
  await expect(page.locator('#content-browser')).toBeHidden(); await expect(page.locator('#inspector')).toBeVisible();
  await expect(page.locator('#inspector')).toHaveAttribute('aria-modal', 'true');
  await page.keyboard.press('Escape'); await expect(page.locator('#inspector')).toBeHidden();
  await expect(root).toBeFocused();
});

test('visual regression: default desktop canvas hierarchy', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Pixel baselines are recorded once; Firefox has behavioral coverage.');
  await page.setViewportSize({ width: 1440, height: 900 }); await connect(page);
  await expect(page).toHaveScreenshot('desktop-workspace.png', {
    animations: 'disabled', maxDiffPixelRatio: 0.01
  });
});

test('visual regression: expanded dock and selected validation/live states', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Pixel baselines are recorded once; Firefox has behavioral coverage.');
  await page.setViewportSize({ width: 1440, height: 900 }); await connect(page);
  const root = page.getByRole('group', { name: /root, sequence/ }); await root.click();
  await page.getByRole('tab', { name: 'YAML' }).click();
  await root.evaluate(element => element.classList.add('has-diagnostic', 'live-active'));
  await expect(page).toHaveScreenshot('desktop-dock-states.png', {
    animations: 'disabled', maxDiffPixelRatio: 0.01
  });
});

test('visual regression: narrow canvas-first workspace', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Pixel baselines are recorded once; Firefox has behavioral coverage.');
  await page.setViewportSize({ width: 680, height: 900 }); await connect(page);
  await expect(page).toHaveScreenshot('narrow-workspace.png', {
    animations: 'disabled', maxDiffPixelRatio: 0.01
  });
});

test.describe('touch input audit', () => {
  test.use({ hasTouch: true, viewport: { width: 1280, height: 900 } });
  test('touch can select nodes and open the compatible palette without a mouse', async ({ page }) => {
    await connect(page);
    await page.getByRole('button', { name: 'Zoom to fit' }).tap();
    const node = page.getByRole('group', { name: /root, sequence/ });
    await node.tap({ position: { x: 30, y: 30 } });
    await expect(node).toHaveAttribute('aria-current', 'true');
    await page.getByRole('button', { name: 'Add node' }).tap();
    await expect(page.getByRole('dialog').filter({ has: page.getByLabel('Add graph node') })).toBeVisible();
  });
});

test('large graph render, warm tab switch, pan/zoom, and browser heap stay within accepted budgets', async ({ page }) => {
  test.setTimeout(60_000);
  await connect(page);
  let started = Date.now();
  await page.locator('#project').getByText('perf:large', { exact: true }).click();
  await expect(page.getByRole('group', { name: /root, sequence/ })).toBeVisible({ timeout: 10_000 });
  expect(Date.now() - started).toBeLessThan(5000);
  await expect(page.locator('#graph-minimap .minimap-node')).toHaveCount(2001, { timeout: 10_000 });
  expect(await page.locator('.graph-node-card').count()).toBeLessThan(100);

  await page.locator('.tab-open', { hasText: 'demo:walker' }).click();
  await expect(page.locator('#graph-minimap .minimap-node')).toHaveCount(3);
  const warmSwitchMs = await page.evaluate(async () => {
    const tab = [...document.querySelectorAll('.tab-open')].find(item => item.textContent.includes('perf:large'));
    const start = performance.now(); tab.click();
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return performance.now() - start;
  });
  await expect(page.getByRole('group', { name: /root, sequence/ })).toBeVisible();
  expect(warmSwitchMs).toBeLessThan(1000);
  await expect(page.locator('#graph-minimap .minimap-node')).toHaveCount(2001);
  expect(await page.locator('.graph-node-card').count()).toBeLessThan(100);

  started = Date.now();
  await page.locator('#graph-canvas').evaluate(canvas => {
    for (let index = 0; index < 60; index++) canvas.dispatchEvent(new WheelEvent('wheel', {
      deltaY: index % 2 ? 12 : -12, clientX: 600, clientY: 400, bubbles: true, cancelable: true
    }));
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(Date.now() - started).toBeLessThan(2000);
  const heap = await page.evaluate(() => performance.memory?.usedJSHeapSize ?? 0);
  if (heap) expect(heap).toBeLessThan(256 * 1024 * 1024);
});

test('passes automated WCAG checks in workspace and dialog states at 200% zoom and forced colors', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await connect(page);
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(page.getByRole('button', { name: 'Add node' })).toBeVisible();
  let audit = await new AxeBuilder({ page }).include('#workspace').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(audit.violations, JSON.stringify(audit.violations, null, 2)).toEqual([]);

  await page.getByRole('button', { name: 'Add node' }).click();
  await expect(page.getByRole('dialog').filter({ has: page.getByLabel('Add graph node') })).toBeVisible();
  audit = await new AxeBuilder({ page }).include('#palette').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(audit.violations, JSON.stringify(audit.violations, null, 2)).toEqual([]);
  await page.keyboard.press('Escape');
  await page.emulateMedia({ reducedMotion: 'reduce', forcedColors: 'active' });
  await expect(page.getByRole('button', { name: 'Add node' })).toBeVisible();
  await page.locator('#graph-canvas').focus();
  await expect(page.locator('#graph-canvas')).toBeFocused();
});
