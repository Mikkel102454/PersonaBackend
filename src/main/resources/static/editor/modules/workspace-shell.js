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
  if (path === 'scripts.yml') return 'script';
  for (const [kind] of GROUPS) if (kind !== 'other' && path.startsWith(`${kind}s/`)) return kind;
  return 'other';
}

export function deriveResources(files) {
  const resources = [];
  for (const [path, content] of files) {
    const kind = kindForPath(path);
    if (kind === 'script') {
      const scriptsLine = content.search(/^scripts:\s*(?:#.*)?$/m);
      if (scriptsLine >= 0) {
        const tail = content.slice(scriptsLine).split(/\r?\n/).slice(1);
        for (const line of tail) {
          if (line && !/^\s/.test(line)) break;
          const match = line.match(/^ {2}([a-z0-9][a-z0-9_.-]{0,127}):(?:\s|$)/);
          if (match) resources.push(resource('script', match[1], match[1], path, content,
            `/scripts/${match[1].replaceAll('~', '~0').replaceAll('/', '~1')}`));
        }
      }
      if (!resources.some(item => item.path === path)) resources.push(resource('other', path, 'scripts.yml', path, content, ''));
      continue;
    }
    const id = scalar(content, 'id') || path;
    const label = scalar(content, kind === 'npc' ? 'display-name' : 'title') || id;
    resources.push(resource(kind, id, label, path, content, ''));
  }
  return resources;
}

function resource(kind, id, label, path, content, yamlPath) {
  return { identity: `${kind}:${id}`, kind, id, label, path, yamlPath,
    search: `${kind} ${id} ${label} ${path} ${content}`.toLowerCase() };
}

export class WorkspaceShell {
  constructor(options) {
    this.options = options;
    this.root = document.querySelector('#project');
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
    this.openTabs = [];
    this.closedTabs = [];
    this.active = null;
    this.back = [];
    this.forward = [];
    this.recent = [];
    this.nestedBreadcrumbs = [];
    this.preferences = this.loadPreferences();
    this.bind();
  }

  bind() {
    for (const control of [this.search, this.filter, this.sort]) control.addEventListener('input', () => this.renderBrowser());
    this.density.addEventListener('click', () => {
      this.preferences.compact = !this.preferences.compact;
      this.savePreferences(); this.renderBrowser();
    });
    this.backButton.addEventListener('click', () => this.navigateHistory('back'));
    this.forwardButton.addEventListener('click', () => this.navigateHistory('forward'));
    this.quickSearch.addEventListener('input', () => this.renderQuickOpen());
    this.quick.addEventListener('close', () => this.options.restoreFocus?.());
    window.addEventListener('keydown', event => this.keydown(event));
  }

  update(resources, activeIdentity = this.active) {
    this.resources = resources;
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

  setNestedBreadcrumbs(parts = []) {
    this.nestedBreadcrumbs = Array.isArray(parts) ? parts.slice(0, 8) : [];
    this.renderBreadcrumbs();
  }

  render() { this.renderBrowser(); this.renderTabs(); this.renderBreadcrumbs(); this.renderHistoryButtons(); }

  renderBrowser() {
    const query = this.search.value.trim().toLowerCase();
    const filter = this.filter.value;
    let visible = this.resources.filter(item => (!query || item.search.includes(query)) && this.matchesFilter(item, filter));
    const recentIndex = identity => { const index = this.recent.indexOf(identity); return index < 0 ? Number.MAX_SAFE_INTEGER : index; };
    visible.sort((left, right) => {
      if (this.sort.value === 'path') return left.path.localeCompare(right.path) || left.id.localeCompare(right.id);
      if (this.sort.value === 'kind') return left.kind.localeCompare(right.kind) || left.label.localeCompare(right.label);
      if (this.sort.value === 'recent') return recentIndex(left.identity) - recentIndex(right.identity);
      if (this.sort.value === 'validation') return Number(this.options.invalid?.(right)) - Number(this.options.invalid?.(left)) || left.label.localeCompare(right.label);
      return left.label.localeCompare(right.label) || left.id.localeCompare(right.id);
    });
    this.root.classList.toggle('compact', this.preferences.compact);
    this.density.setAttribute('aria-pressed', String(this.preferences.compact));
    this.density.textContent = this.preferences.compact ? 'Comfortable' : 'Compact';
    const fragment = document.createDocumentFragment();
    for (const [kind, label] of GROUPS) {
      const items = visible.filter(item => item.kind === kind);
      const allCount = this.resources.filter(item => item.kind === kind).length;
      const section = document.createElement('section'); section.className = 'content-group';
      const heading = document.createElement('button'); heading.type = 'button'; heading.className = 'content-group-heading';
      heading.setAttribute('aria-expanded', String(!this.preferences.collapsed[kind]));
      heading.innerHTML = `<span>${label}</span><span>${items.length === allCount ? allCount : `${items.length}/${allCount}`}</span>`;
      heading.addEventListener('click', () => { this.preferences.collapsed[kind] = !this.preferences.collapsed[kind]; this.savePreferences(); this.renderBrowser(); });
      section.append(heading);
      if (!this.preferences.collapsed[kind]) {
        const list = document.createElement('ul');
        for (const item of items) list.append(this.browserItem(item));
        if (!items.length) { const empty = document.createElement('li'); empty.className = 'content-empty'; empty.textContent = query || filter !== 'all' ? 'No matches' : 'No content'; list.append(empty); }
        section.append(list);
      }
      fragment.append(section);
    }
    this.root.replaceChildren(fragment);
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
      const close = document.createElement('button'); close.type = 'button'; close.className = 'tab-close'; close.textContent = '×'; close.setAttribute('aria-label', `Close ${resource.label}`);
      close.addEventListener('click', () => this.closeResource(identity));
      tab.addEventListener('dragstart', event => event.dataTransfer.setData('text/persona-tab', identity));
      tab.addEventListener('dragover', event => event.preventDefault());
      tab.addEventListener('drop', event => { event.preventDefault(); const source = event.dataTransfer.getData('text/persona-tab'), from = this.openTabs.indexOf(source); if (from < 0) return; this.openTabs.splice(from, 1); this.openTabs.splice(index, 0, source); this.renderTabs(); });
      tab.append(open, close); return tab;
    }));
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
    if (this.active) target.push(this.active);
    this.openResource(identity, false); this.renderHistoryButtons();
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

  loadPreferences() {
    try {
      const saved = JSON.parse(localStorage.getItem('persona:workspace-preferences:v1') || '{}');
      return { compact: Boolean(saved.compact), collapsed: saved.collapsed && typeof saved.collapsed === 'object' ? saved.collapsed : {} };
    } catch { return { compact: false, collapsed: {} }; }
  }
  savePreferences() { localStorage.setItem('persona:workspace-preferences:v1', JSON.stringify(this.preferences)); }
}
