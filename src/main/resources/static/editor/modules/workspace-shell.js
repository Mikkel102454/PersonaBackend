import { CONTENT_WINDOW, boundedResources, resourceMatches } from './content-browser.js';
import { closeTabsToRight, reorderTabs } from './resource-tabs.js';
import { requestText } from './action-form.js';

const GROUPS = [
  ['npc', 'NPCs'], ['dialogue', 'Dialogues'], ['quest', 'Quests'],
  ['behavior', 'Behaviours'], ['script', 'Scripts'], ['other', 'Other YAML']
];
const ICONS = { npc: '◆', dialogue: '◌', quest: '◇', behavior: '▰', script: 'ƒ', other: 'Y' };

function scalar(content, key) {
  const match = content.match(new RegExp(`^${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:\\s*(?:["']([^"']*)["']|([^#\\r\\n]+))`, 'm'));
  return (match?.[1] ?? match?.[2] ?? '').trim();
}

export function kindForPath(path) {
  if (path.startsWith('scripts/')) return 'script';
  for (const [kind] of GROUPS) if (kind !== 'other' && path.startsWith(`${kind}s/`)) return kind;
  return 'other';
}

export function deriveResources(files) {
  const resources = [];
  for (const [path, content] of files) {
    if (path === '.persona/project.yml') continue;
    const kind = kindForPath(path);
    const id = scalar(content, 'id') || path;
    const label = scalar(content, kind === 'npc' ? 'display-name' : 'title') || id;
    resources.push(resource(kind, id, label, path, content, ''));
  }
  return resources;
}

function resource(kind, id, label, path, content, yamlPath) {
  return { identity: `${kind}:${id}`, kind, id, label, path, yamlPath,
    folder: path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '',
    search: `${kind} ${id} ${label} ${path} ${content}`.toLowerCase() };
}

export class WorkspaceShell {
  constructor(options) {
    this.options = options;
    this.root = document.querySelector('#project');
    this.sources = document.querySelector('#sources-tree');
    this.assetBreadcrumbs = document.querySelector('#asset-breadcrumbs');
    this.resultCount = document.querySelector('#content-result-count');
    this.recursiveToggle = document.querySelector('#content-recursive');
    this.viewToggle = document.querySelector('#content-view-toggle');
    this.tabsElement = document.querySelector('#resource-tabs');
    this.breadcrumbs = document.querySelector('#breadcrumbs');
    this.search = document.querySelector('#content-search');
    this.filter = document.querySelector('#content-filter');
    this.sort = document.querySelector('#content-sort');
    this.density = document.querySelector('#content-density');
    this.quick = document.querySelector('#quick-open');
    this.quickSearch = document.querySelector('#quick-open-search');
    this.quickResults = document.querySelector('#quick-open-results');
    this.backButton = document.querySelector('#history-back');
    this.forwardButton = document.querySelector('#history-forward');
    this.resources = [];
    this.folders = new Set(GROUPS.filter(([kind]) => kind !== 'other').map(([kind]) => `${kind}s`));
    this.openTabs = [];
    this.closedTabs = [];
    this.active = null;
    this.back = [];
    this.forward = [];
    this.recent = [];
    this.nestedBreadcrumbs = [];
    this.browserView = 'library';
    this.browserLimits = Object.fromEntries(GROUPS.map(([kind]) => [kind, CONTENT_WINDOW]));
    this.preferenceKey = 'persona:workspace-preferences:v1';
    this.preferences = this.loadPreferences();
    this.selectedFolder = this.preferences.selectedFolder || 'npcs';
    this.bind();
  }

  bind() {
    for (const control of [this.search, this.filter, this.sort]) control.addEventListener('input', () => {
      this.browserLimits = Object.fromEntries(GROUPS.map(([kind]) => [kind, CONTENT_WINDOW])); this.renderBrowser();
    });
    this.density.addEventListener('click', () => {
      this.preferences.compact = !this.preferences.compact;
      this.savePreferences(); this.renderBrowser();
    });
    this.recursiveToggle?.addEventListener('change', () => {
      this.preferences.recursive = this.recursiveToggle.checked; this.savePreferences(); this.renderBrowser();
    });
    this.viewToggle?.addEventListener('click', () => {
      this.preferences.viewStyle = this.preferences.viewStyle === 'tiles' ? 'list' : 'tiles';
      this.savePreferences(); this.renderBrowser();
    });
    this.backButton.addEventListener('click', () => this.navigateHistory('back'));
    this.forwardButton.addEventListener('click', () => this.navigateHistory('forward'));
    this.quickSearch.addEventListener('input', () => this.renderQuickOpen());
    this.quick.addEventListener('close', () => this.options.restoreFocus?.());
    window.addEventListener('keydown', event => this.keydown(event));
  }

  update(resources, activeIdentity = this.active, folders = this.folders) {
    this.resources = resources;
    this.folders = this.buildFolders(folders);
    if (!this.folders.has(this.selectedFolder)) this.selectedFolder = 'npcs';
    this.openTabs = this.openTabs.filter(identity => resources.some(item => item.identity === identity));
    if (activeIdentity && resources.some(item => item.identity === activeIdentity)) this.active = activeIdentity;
    else if (this.active && !resources.some(item => item.identity === this.active)) this.active = null;
    this.render();
  }

  openResource(resourceOrIdentity, history = true) {
    const resource = typeof resourceOrIdentity === 'string'
      ? this.resources.find(item => item.identity === resourceOrIdentity) : resourceOrIdentity;
    if (!resource) return;
    if (history && this.active && this.active !== resource.identity) {
      this.back.push(this.active); this.forward.length = 0;
    }
    if (this.active && this.active !== resource.identity) this.options.beforeOpen?.(this.active);
    if (!this.openTabs.includes(resource.identity)) this.openTabs.push(resource.identity);
    this.active = resource.identity;
    this.selectedFolder = resource.folder;
    this.preferences.selectedFolder = this.selectedFolder;
    this.savePreferences();
    this.recent = [resource.identity, ...this.recent.filter(item => item !== resource.identity)].slice(0, 100);
    this.options.open(resource);
    this.render();
  }

  closeResource(identity, reopen = true) {
    const index = this.openTabs.indexOf(identity);
    if (index < 0) return;
    if (reopen) this.closedTabs.unshift(identity);
    this.openTabs.splice(index, 1);
    if (this.active === identity) {
      this.active = this.openTabs[Math.min(index, this.openTabs.length - 1)] ?? null;
      if (this.active) this.openResource(this.active, false);
      else this.options.empty?.();
    }
    this.render();
  }

  reopenClosed() {
    const identity = this.closedTabs.shift();
    if (identity) this.openResource(identity);
  }

  activeResource() { return this.resources.find(item => item.identity === this.active) ?? null; }

  showView(view) {
    if (!['library', 'bookmarks', 'recents'].includes(view)) return;
    this.browserView = view;
    if (view === 'recents') this.sort.value = 'recent';
    for (const button of document.querySelectorAll('#navigation-rail button')) {
      const active = button.id === `rail-${view}`; button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    }
    this.renderBrowser();
  }

  setNestedBreadcrumbs(parts = []) {
    this.nestedBreadcrumbs = Array.isArray(parts) ? parts.slice(0, 8) : [];
    this.renderBreadcrumbs();
  }

  render() { this.renderBrowser(); this.renderTabs(); this.renderBreadcrumbs(); this.renderHistoryButtons(); }

  renderBrowser() {
    const query = this.search.value.trim().toLowerCase();
    const filter = this.filter.value;
    const recursive = Boolean(this.preferences.recursive);
    let visible = this.resources.filter(item => (item.folder === this.selectedFolder
        || recursive && item.folder.startsWith(this.selectedFolder + '/'))
      && resourceMatches(item, query, this.options.searchTerms?.(item) || '')
      && this.matchesFilter(item, filter));
    if (this.browserView === 'bookmarks') visible = visible.filter(item => this.options.bookmarked?.(item));
    const recentIndex = identity => { const index = this.recent.indexOf(identity); return index < 0 ? Number.MAX_SAFE_INTEGER : index; };
    visible.sort((left, right) => {
      if (this.sort.value === 'path') return left.path.localeCompare(right.path) || left.id.localeCompare(right.id);
      if (this.sort.value === 'kind') return left.kind.localeCompare(right.kind) || left.label.localeCompare(right.label);
      if (this.sort.value === 'recent') return recentIndex(left.identity) - recentIndex(right.identity);
      if (this.sort.value === 'validation') return Number(this.options.invalid?.(right)) - Number(this.options.invalid?.(left)) || left.label.localeCompare(right.label);
      return left.label.localeCompare(right.label) || left.id.localeCompare(right.id);
    });
    this.root.classList.toggle('compact', this.preferences.compact);
    this.root.classList.toggle('tile-view', this.preferences.viewStyle === 'tiles');
    this.root.setAttribute('role', this.preferences.viewStyle === 'tiles' ? 'grid' : 'list');
    this.density.setAttribute('aria-pressed', String(this.preferences.compact));
    this.density.textContent = this.preferences.compact ? 'Comfortable' : 'Compact';
    this.recursiveToggle.checked = recursive;
    this.viewToggle.textContent = this.preferences.viewStyle === 'tiles' ? 'List view' : 'Tile view';
    this.renderSources(); this.renderAssetBreadcrumbs();
    this.resultCount.textContent = `${visible.length} result${visible.length === 1 ? '' : 's'}`;
    const rendered = boundedResources(visible, Math.max(...Object.values(this.browserLimits)));
    const fragment = document.createDocumentFragment();
    for (const item of rendered) fragment.append(this.browserItem(item));
    if (!rendered.length) { const empty = document.createElement('p'); empty.className = 'content-empty'; empty.textContent = query || filter !== 'all' ? 'No matches in this search scope' : 'This folder has no resources'; fragment.append(empty); }
    this.root.replaceChildren(fragment);
  }

  buildFolders(declared) {
    const result = new Set(GROUPS.filter(([kind]) => kind !== 'other').map(([kind]) => `${kind}s`));
    for (const folder of declared || []) result.add(folder);
    for (const item of this.resources) {
      let folder = item.folder;
      while (folder) { result.add(folder); const slash = folder.lastIndexOf('/'); if (slash < 0) break; folder = folder.slice(0, slash); }
    }
    return result;
  }

  selectFolder(folder, history = true) {
    if (!this.folders.has(folder)) return;
    if (history && this.selectedFolder !== folder) { this.back.push(`folder:${this.selectedFolder}`); this.forward.length = 0; }
    this.selectedFolder = folder; this.preferences.selectedFolder = folder; this.savePreferences();
    this.renderBrowser(); this.renderHistoryButtons();
  }

  renderSources() {
    const fragment = document.createDocumentFragment();
    const folders = [...this.folders].sort((left, right) => left.localeCompare(right));
    for (const folder of folders) {
      const depth = folder.split('/').length - 1;
      const parent = folder.includes('/') ? folder.slice(0, folder.lastIndexOf('/')) : null;
      if (parent && [...this.preferences.collapsedFolders].some(value => parent === value || parent.startsWith(value + '/'))) continue;
      const row = document.createElement('div'); row.className = 'source-row'; row.setAttribute('role', 'treeitem');
      row.setAttribute('aria-level', String(depth + 1)); row.style.setProperty('--source-depth', depth);
      const children = folders.some(value => value.startsWith(folder + '/') && value.split('/').length === folder.split('/').length + 1);
      const expand = document.createElement('button'); expand.type = 'button'; expand.className = 'source-expand';
      expand.textContent = children ? (this.preferences.collapsedFolders.has(folder) ? '▸' : '▾') : '';
      expand.disabled = !children; expand.setAttribute('aria-label', `${this.preferences.collapsedFolders.has(folder) ? 'Expand' : 'Collapse'} ${folder}`);
      expand.addEventListener('click', () => { this.preferences.collapsedFolders.has(folder)
        ? this.preferences.collapsedFolders.delete(folder) : this.preferences.collapsedFolders.add(folder); this.savePreferences(); this.renderSources(); });
      const button = document.createElement('button'); button.type = 'button'; button.className = 'source-folder';
      button.textContent = `${this.preferences.folderFavorites.has(folder) ? '★ ' : ''}${folder.split('/').at(-1)}`;
      button.style.setProperty('--folder-color', this.preferences.folderColors[folder] || 'transparent');
      if (folder === this.selectedFolder) { button.setAttribute('aria-current', 'true'); row.setAttribute('aria-selected', 'true'); }
      button.addEventListener('click', () => this.selectFolder(folder));
      button.addEventListener('contextmenu', event => { event.preventDefault(); this.openFolderMenu(folder, event.clientX, event.clientY, button); });
      if (depth) { button.draggable = true; button.addEventListener('dragstart', event => event.dataTransfer.setData('application/x-persona-folder', folder)); }
      button.addEventListener('dragover', event => { if ([...event.dataTransfer.types].some(type => type.startsWith('application/x-persona-'))) event.preventDefault(); });
      button.addEventListener('drop', event => this.dropOnFolder(event, folder));
      row.append(expand, button); fragment.append(row);
    }
    this.sources.replaceChildren(fragment);
  }

  renderAssetBreadcrumbs() {
    const parts = this.selectedFolder.split('/');
    this.assetBreadcrumbs.replaceChildren(...parts.flatMap((part, index) => {
      const path = parts.slice(0, index + 1).join('/'), button = document.createElement('button');
      button.type = 'button'; button.textContent = part; button.addEventListener('click', () => this.selectFolder(path));
      return index ? [document.createTextNode('›'), button] : [button];
    }));
  }

  openFolderMenu(folder, x, y, restore) {
    document.querySelector('#folder-context-menu')?.remove();
    const menu = document.createElement('div'); menu.id = 'folder-context-menu'; menu.className = 'resource-tab-menu';
    menu.setAttribute('role', 'menu'); menu.style.left = `${x}px`; menu.style.top = `${y}px`;
    const root = !folder.includes('/');
    const action = (label, run, disabled = false) => { const button = document.createElement('button'); button.type = 'button';
      button.setAttribute('role', 'menuitem'); button.textContent = label; button.disabled = disabled;
      button.addEventListener('click', () => { menu.remove(); run(); }); menu.append(button); };
    action('New Folder', () => this.options.createFolder?.(folder));
    action('Create Resource Here', () => this.options.createHere?.(folder));
    action('Rename', () => this.options.moveFolder?.(folder), root);
    action('Move', async () => {
      const parent = folder.includes('/') ? folder.slice(0, folder.lastIndexOf('/')) : folder;
      const destination = await requestText(`Move ${folder} beneath folder:`, parent);
      if (destination && destination !== parent) this.options.moveFolder?.(folder, destination);
    }, root);
    action('Delete', () => this.options.deleteFolder?.(folder), root);
    action(this.preferences.folderFavorites.has(folder) ? 'Remove from Favorites' : 'Add to Favorites', () => {
      this.preferences.folderFavorites.has(folder) ? this.preferences.folderFavorites.delete(folder) : this.preferences.folderFavorites.add(folder);
      this.savePreferences(); this.renderSources();
    });
    action('Set Local Color', async () => { const color = await requestText('Folder color (CSS hex)', this.preferences.folderColors[folder] || '#5d77a8');
      if (color && /^#[0-9a-f]{6}$/i.test(color)) { this.preferences.folderColors[folder] = color; this.savePreferences(); this.renderSources(); } });
    action('Copy Path', () => navigator.clipboard?.writeText(folder));
    document.body.append(menu); menu.querySelector('button:not(:disabled)')?.focus();
    setTimeout(() => document.addEventListener('pointerdown', event => { if (!menu.contains(event.target)) { menu.remove(); restore?.focus(); } }, { once: true }), 0);
  }

  dropOnFolder(event, folder) {
    event.preventDefault();
    const sourceFolder = event.dataTransfer.getData('application/x-persona-folder');
    if (sourceFolder) { this.options.moveFolder?.(sourceFolder, folder); return; }
    const identity = event.dataTransfer.getData('application/x-persona-project-resource');
    const resource = this.resources.find(item => item.identity === identity);
    if (!resource) return;
    if (event.ctrlKey || event.metaKey) this.options.copyResourceToFolder?.(resource, folder);
    else this.options.moveResourceToFolder?.(resource, folder);
  }

  browserItem(item) {
    const row = document.createElement('li');
    const button = document.createElement('button'); button.type = 'button'; button.className = 'content-item';
    const badges = `${this.options.dirty?.(item) ? '<span class="resource-badge dirty" aria-label="Unsaved changes">●</span>' : ''}${this.options.invalid?.(item) ? '<span class="resource-badge error" aria-label="Validation error">!</span>' : ''}`;
    button.innerHTML = `<span class="kind-icon" aria-hidden="true">${ICONS[item.kind]}</span><span class="content-label"><strong></strong><small></small></span>${badges}`;
    button.querySelector('strong').textContent = item.label;
    button.querySelector('small').textContent = `${item.id === item.label ? '' : `${item.id} · `}${item.path}`;
    button.title = `${item.kind}: ${item.id}\n${item.path}`;
    if (item.identity === this.active) button.setAttribute('aria-current', 'page');
    if (['npc','dialogue','quest','behavior','script'].includes(item.kind)) { button.draggable = true;
      button.addEventListener('dragstart', event => { event.dataTransfer.effectAllowed = 'copyMove';
        event.dataTransfer.setData('application/x-persona-project-resource', item.identity);
        event.dataTransfer.setData('application/x-persona-resource', JSON.stringify({ kind: item.kind, id: item.id })); }); }
    button.addEventListener('click', () => this.openResource(item));
    row.append(button); return row;
  }

  renderTabs() {
    this.tabsElement.replaceChildren(...this.openTabs.map((identity, index) => {
      const resource = this.resources.find(item => item.identity === identity);
      if (!resource) return document.createTextNode('');
      const tab = document.createElement('div'); tab.className = 'resource-tab';
      tab.draggable = true;
      const open = document.createElement('button'); open.type = 'button'; open.className = 'tab-open';
      open.innerHTML = `<span aria-hidden="true">${ICONS[resource.kind]}</span><span></span>${this.options.dirty?.(resource) ? '<span class="dirty" aria-label="Unsaved changes">●</span>' : ''}${this.options.invalid?.(resource) ? '<span class="error" aria-label="Error">!</span>' : ''}`;
      open.querySelectorAll('span')[1].textContent = resource.label;
      if (identity === this.active) open.setAttribute('aria-current', 'page');
      open.addEventListener('click', () => this.openResource(resource));
      open.addEventListener('auxclick', event => { if (event.button === 1) this.closeResource(identity); });
      open.addEventListener('contextmenu', event => { event.preventDefault(); this.openTabMenu(identity, index, event.clientX, event.clientY, open); });
      const close = document.createElement('button'); close.type = 'button'; close.className = 'tab-close'; close.textContent = '×'; close.setAttribute('aria-label', `Close ${resource.label}`);
      close.addEventListener('click', () => this.closeResource(identity));
      tab.addEventListener('dragstart', event => event.dataTransfer.setData('text/persona-tab', identity));
      tab.addEventListener('dragover', event => event.preventDefault());
      tab.addEventListener('drop', event => { event.preventDefault();
        this.openTabs = reorderTabs(this.openTabs, event.dataTransfer.getData('text/persona-tab'), index); this.renderTabs(); });
      tab.append(open, close); return tab;
    }));
  }

  openTabMenu(identity, index, x, y, restore) {
    document.querySelector('#resource-tab-menu')?.remove();
    const menu = document.createElement('div'); menu.id = 'resource-tab-menu'; menu.className = 'resource-tab-menu';
    menu.setAttribute('role', 'menu'); menu.style.left = `${x}px`; menu.style.top = `${y}px`;
    const action = (label, run, disabled = false) => {
      const button = document.createElement('button'); button.type = 'button'; button.setAttribute('role', 'menuitem');
      button.textContent = label; button.disabled = disabled;
      button.addEventListener('click', () => { run(); menu.remove(); }); menu.append(button);
    };
    action('Close', () => this.closeResource(identity));
    action('Close others', () => {
      for (const other of this.openTabs.filter(value => value !== identity)) this.closedTabs.unshift(other);
      this.openTabs = [identity]; this.openResource(identity, false); this.render();
    }, this.openTabs.length < 2);
    action('Close to the right', () => {
      const result = closeTabsToRight(this.openTabs, index);
      for (const other of result.closed) this.closedTabs.unshift(other);
      this.openTabs = result.kept; this.render();
    }, index >= this.openTabs.length - 1);
    menu.addEventListener('keydown', event => {
      const buttons = [...menu.querySelectorAll('button:not(:disabled)')], current = buttons.indexOf(document.activeElement);
      if (event.key === 'Escape') { event.preventDefault(); menu.remove(); restore.focus(); }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault(); buttons[(current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus();
      }
    });
    document.body.append(menu); menu.querySelector('button:not(:disabled)')?.focus();
    setTimeout(() => document.addEventListener('pointerdown', event => {
      if (!menu.contains(event.target)) menu.remove();
    }, { once: true }), 0);
  }

  renderBreadcrumbs() {
    const resource = this.activeResource();
    if (!resource) { this.breadcrumbs.replaceChildren(); return; }
    const group = GROUPS.find(([kind]) => kind === resource.kind)?.[1] ?? 'Other YAML';
    const parts = [['Project', () => this.search.focus()], [group, () => { this.filter.value = 'all'; this.search.value = ''; this.renderBrowser(); }],
      [resource.label, () => this.openResource(resource, false)],
      ...this.nestedBreadcrumbs.map(part => [part.label, part.action || (() => {})])];
    this.breadcrumbs.replaceChildren(...parts.flatMap(([label, action], index) => {
      const button = document.createElement('button'); button.type = 'button'; button.textContent = label; button.addEventListener('click', action);
      return index ? [document.createTextNode('›'), button] : [button];
    }));
  }

  matchesFilter(item, filter) {
    if (filter === 'dirty') return Boolean(this.options.dirty?.(item));
    if (filter === 'clean') return !this.options.dirty?.(item);
    if (filter === 'invalid') return Boolean(this.options.invalid?.(item));
    if (filter === 'referenced') return Boolean(this.options.referenced?.(item));
    if (filter === 'unreferenced') return !this.options.referenced?.(item);
    if (filter === 'missing') return Boolean(this.options.missing?.(item));
    if (filter === 'live') return Boolean(this.options.live?.(item));
    return true;
  }

  showQuickOpen() { this.quick.showModal(); this.quickSearch.value = ''; this.renderQuickOpen(); this.quickSearch.focus(); }
  renderQuickOpen() {
    const query = this.quickSearch.value.toLowerCase();
    const results = this.resources.filter(item => !query || item.search.includes(query)).slice(0, 100);
    this.quickResults.replaceChildren(...results.map(item => {
      const row = document.createElement('li'), button = document.createElement('button'); button.type = 'button';
      button.textContent = `${ICONS[item.kind]} ${item.label} — ${item.path}`;
      button.addEventListener('click', () => { this.quick.close(); this.openResource(item); }); row.append(button); return row;
    }));
    this.quickResults.querySelector('button')?.focus();
  }

  navigateHistory(direction) {
    const source = direction === 'back' ? this.back : this.forward;
    const target = direction === 'back' ? this.forward : this.back;
    const identity = source.pop(); if (!identity) return;
    if (identity.startsWith('folder:')) {
      target.push(`folder:${this.selectedFolder}`); this.selectFolder(identity.slice(7), false);
    } else {
      if (this.active) target.push(this.active);
      this.openResource(identity, false);
    }
    this.renderHistoryButtons();
  }
  renderHistoryButtons() { this.backButton.disabled = !this.back.length; this.forwardButton.disabled = !this.forward.length; }

  keydown(event) {
    const command = event.ctrlKey || event.metaKey;
    if (command && event.key.toLowerCase() === 'p') { event.preventDefault(); this.showQuickOpen(); }
    else if (command && event.key === 'Tab' && this.openTabs.length) {
      event.preventDefault(); const index = this.openTabs.indexOf(this.active), direction = event.shiftKey ? -1 : 1;
      this.openResource(this.openTabs[(index + direction + this.openTabs.length) % this.openTabs.length]);
    } else if (event.altKey && event.key === 'ArrowLeft') { event.preventDefault(); this.navigateHistory('back'); }
    else if (event.altKey && event.key === 'ArrowRight') { event.preventDefault(); this.navigateHistory('forward'); }
    else if (command && event.shiftKey && event.key.toLowerCase() === 't') { event.preventDefault(); this.reopenClosed(); }
  }

  setPreferenceScope(scope) {
    const key = `persona:workspace-preferences:v2:${scope}`;
    if (key === this.preferenceKey) return;
    this.preferenceKey = key; this.preferences = this.loadPreferences(); this.renderBrowser();
  }

  loadPreferences() {
    try {
      const saved = JSON.parse(localStorage.getItem(this.preferenceKey || 'persona:workspace-preferences:v1') || '{}');
      return { compact: Boolean(saved.compact), collapsed: saved.collapsed && typeof saved.collapsed === 'object' ? saved.collapsed : {},
        selectedFolder: typeof saved.selectedFolder === 'string' ? saved.selectedFolder : 'npcs',
        viewStyle: saved.viewStyle === 'tiles' ? 'tiles' : 'list', recursive: Boolean(saved.recursive),
        collapsedFolders: new Set(Array.isArray(saved.collapsedFolders) ? saved.collapsedFolders : []),
        folderFavorites: new Set(Array.isArray(saved.folderFavorites) ? saved.folderFavorites : []),
        folderColors: saved.folderColors && typeof saved.folderColors === 'object' ? saved.folderColors : {} };
    } catch { return { compact: false, collapsed: {}, selectedFolder: 'npcs', viewStyle: 'list', recursive: false,
      collapsedFolders: new Set(), folderFavorites: new Set(), folderColors: {} }; }
  }
  savePreferences() { localStorage.setItem(this.preferenceKey, JSON.stringify({ ...this.preferences,
    collapsedFolders: [...this.preferences.collapsedFolders], folderFavorites: [...this.preferences.folderFavorites] })); }
}
