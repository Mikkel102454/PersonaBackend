const VERSION = 2;
const DEFAULTS = Object.freeze({
  version: VERSION, browserWidth: 280, inspectorWidth: 320, dockHeight: 0,
  browserCollapsed: false, inspectorCollapsed: false, dockCollapsed: true,
  inspectorTab: 'inspector', dockTab: 'yaml', centerSplit: 'visual'
});

const clamp = (value, minimum, maximum, fallback) =>
  Math.min(maximum, Math.max(minimum, Number.isFinite(Number(value)) ? Number(value) : fallback));

/** Normalizes presentation-only preferences. Authoritative/editor data is never accepted. */
export function normalizeLayout(value, availableHeight = 900) {
  if (!value || value.version !== VERSION) return { ...DEFAULTS };
  const maxDock = Math.max(180, Math.floor(Math.max(320, availableHeight) * .55));
  return {
    version: VERSION,
    browserWidth: clamp(value.browserWidth, 220, 480, DEFAULTS.browserWidth),
    inspectorWidth: clamp(value.inspectorWidth, 280, 520, DEFAULTS.inspectorWidth),
    dockHeight: clamp(value.dockHeight, 180, maxDock, 260),
    browserCollapsed: Boolean(value.browserCollapsed),
    inspectorCollapsed: Boolean(value.inspectorCollapsed),
    dockCollapsed: value.dockCollapsed !== false,
    inspectorTab: ['inspector', 'history'].includes(value.inspectorTab) ? value.inspectorTab : 'inspector',
    dockTab: ['problems', 'references', 'simulation', 'yaml', 'changes', 'live'].includes(value.dockTab) ? value.dockTab : 'yaml',
    centerSplit: ['visual', 'split', 'yaml'].includes(value.centerSplit) ? value.centerSplit : 'visual'
  };
}

export class PanelLayout {
  constructor(key, { onStorageError } = {}) {
    this.key = key;
    this.onStorageError = onStorageError;
    this.storageErrorAnnounced = false;
    this.workspace = document.querySelector('#workspace');
    this.editor = document.querySelector('.editor');
    this.browser = document.querySelector('#content-browser');
    this.inspector = document.querySelector('#inspector');
    this.split = document.querySelector('.split');
    this.dock = document.querySelector('#output-dock');
    this.browserHandle = document.querySelector('#browser-resizer');
    this.inspectorHandle = document.querySelector('#source-resizer');
    this.dockHandle = document.querySelector('#output-resizer');
    this.browserToggle = document.querySelector('#browser-toggle');
    this.inspectorToggle = document.querySelector('#inspector-toggle');
    this.dockToggle = document.querySelector('#output-collapse');
    this.resetButton = document.querySelector('#layout-reset');
    this.value = this.load();
    this.narrow = matchMedia('(max-width: 899px)').matches;
    if (this.narrow) { this.value.browserCollapsed = true; this.value.inspectorCollapsed = true; }
    this.bind();
    this.apply(false);
  }

  bind() {
    this.drag(this.browserHandle, 'horizontal', event => {
      const rail = matchMedia('(max-width: 899px)').matches ? 0 : 48;
      this.value.browserWidth = event.clientX - rail;
      this.value.browserCollapsed = false;
    });
    this.drag(this.inspectorHandle, 'horizontal', event => {
      this.value.inspectorWidth = this.split.getBoundingClientRect().right - event.clientX;
      this.value.inspectorCollapsed = false;
    });
    this.drag(this.dockHandle, 'vertical', event => {
      this.value.dockHeight = this.editor.getBoundingClientRect().bottom - event.clientY - 24;
      this.value.dockCollapsed = false;
    });
    this.browserToggle.addEventListener('click', () => this.toggle('browser'));
    this.inspectorToggle.addEventListener('click', () => this.toggle('inspector'));
    this.dockToggle.addEventListener('click', () => this.toggle('dock'));
    this.resetButton.addEventListener('click', () => { this.value = { ...DEFAULTS }; this.apply(); });
    document.querySelectorAll('[data-inspector-tab]').forEach(tab => tab.addEventListener('click', () => {
      this.value.inspectorTab = tab.dataset.inspectorTab; this.apply();
    }));
    window.addEventListener('keydown', event => {
      const command = event.ctrlKey || event.metaKey;
      if (!command && !event.altKey && event.key === '[') { event.preventDefault(); this.toggle('browser'); }
      else if (!command && !event.altKey && event.key === ']') { event.preventDefault(); this.toggle('inspector'); }
      else if (command && event.key.toLowerCase() === 'j') { event.preventDefault(); this.toggle('dock'); }
      else if (event.key === 'Escape' && matchMedia('(max-width: 899px)').matches) {
        if (!this.value.inspectorCollapsed) this.toggle('inspector');
        else if (!this.value.browserCollapsed) this.toggle('browser');
        const target = this.drawerReturnFocus; this.drawerReturnFocus = null;
        requestAnimationFrame(() => target?.isConnected && target.focus());
      }
    });
    window.addEventListener('resize', () => this.apply(false));
  }

  drag(handle, orientation, update) {
    handle?.addEventListener('pointerdown', event => {
      event.preventDefault(); handle.setPointerCapture(event.pointerId);
      const move = current => { update(current); this.apply(false); };
      const end = () => { handle.removeEventListener('pointermove', move); this.save(); };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', end, { once: true });
      handle.addEventListener('pointercancel', end, { once: true });
    });
    handle?.addEventListener('keydown', event => {
      const keys = orientation === 'vertical' ? ['ArrowUp', 'ArrowDown'] : ['ArrowLeft', 'ArrowRight'];
      if (!keys.includes(event.key)) return;
      event.preventDefault();
      const delta = (event.shiftKey ? 32 : 8) * (['ArrowLeft', 'ArrowUp'].includes(event.key) ? -1 : 1);
      if (handle === this.browserHandle) this.value.browserWidth += delta;
      else if (handle === this.inspectorHandle) this.value.inspectorWidth -= delta;
      else this.value.dockHeight -= delta;
      this.apply();
    });
  }

  toggle(panel) {
    const key = `${panel}Collapsed`;
    const narrow = matchMedia('(max-width: 899px)').matches;
    const opening = this.value[key];
    if (narrow && opening) this.drawerReturnFocus = document.activeElement;
    this.value[key] = !this.value[key];
    if (narrow && !this.value[key]) {
      if (panel === 'browser') this.value.inspectorCollapsed = true;
      if (panel === 'inspector') this.value.browserCollapsed = true;
    }
    this.apply();
    if (narrow && !opening) {
      const target = this.drawerReturnFocus; this.drawerReturnFocus = null;
      requestAnimationFrame(() => target?.isConnected && target.focus());
    }
  }

  selectDock(name, expand = true) {
    this.value.dockTab = name;
    if (expand) this.value.dockCollapsed = false;
    this.apply();
  }

  show(panel) {
    const narrow = matchMedia('(max-width: 899px)').matches;
    if (narrow) this.drawerReturnFocus = document.activeElement;
    if (panel === 'browser') { this.value.browserCollapsed = false; if (narrow) this.value.inspectorCollapsed = true; }
    if (panel === 'inspector') { this.value.inspectorCollapsed = false; if (narrow) this.value.browserCollapsed = true; }
    this.apply();
  }

  setCenterSplit(value) { this.value.centerSplit = value; this.apply(); }

  rekey(key) {
    if (!key || key === this.key) return;
    this.key = key; this.value = this.load();
    if (this.narrow) { this.value.browserCollapsed = true; this.value.inspectorCollapsed = true; }
    this.apply(false);
  }

  apply(save = true) {
    this.value = normalizeLayout(this.value, this.editor?.clientHeight || innerHeight);
    const narrow = matchMedia('(max-width: 899px)').matches;
    if (narrow !== this.narrow) {
      this.narrow = narrow;
      if (narrow) { this.value.browserCollapsed = true; this.value.inspectorCollapsed = true; }
    }
    this.workspace.style.setProperty('--browser-width', this.value.browserCollapsed ? '0px' : `${this.value.browserWidth}px`);
    this.split.style.setProperty('--inspector-width', this.value.inspectorCollapsed ? '0px' : `${this.value.inspectorWidth}px`);
    this.editor.style.setProperty('--dock-height', this.value.dockCollapsed ? '34px' : `${this.value.dockHeight}px`);
    this.browser.hidden = this.value.browserCollapsed;
    this.browserHandle.hidden = this.value.browserCollapsed || narrow;
    const inspectorHidden = this.value.inspectorCollapsed || narrow && !this.value.browserCollapsed;
    this.inspector.hidden = inspectorHidden;
    this.inspectorHandle.hidden = inspectorHidden || narrow;
    for (const [element, hidden] of [[this.browser, this.value.browserCollapsed], [this.inspector, inspectorHidden]]) {
      if (narrow && !hidden) { element.setAttribute('role', 'dialog'); element.setAttribute('aria-modal', 'true'); }
      else { element.removeAttribute('role'); element.removeAttribute('aria-modal'); }
    }
    this.dockHandle.hidden = this.value.dockCollapsed;
    this.browserToggle.setAttribute('aria-expanded', String(!this.value.browserCollapsed));
    this.browserToggle.textContent = this.value.browserCollapsed ? 'Show browser' : 'Hide browser';
    this.inspectorToggle.setAttribute('aria-expanded', String(!this.value.inspectorCollapsed));
    this.inspectorToggle.setAttribute('aria-label', this.value.inspectorCollapsed ? 'Show Inspector' : 'Hide Inspector');
    this.inspectorToggle.textContent = this.value.inspectorCollapsed ? '‹' : '›';
    this.dockToggle.setAttribute('aria-expanded', String(!this.value.dockCollapsed));
    this.dockToggle.textContent = this.value.dockCollapsed ? 'Expand output' : 'Collapse output';
    this.updateSeparator(this.browserHandle, 220, 480, this.value.browserWidth);
    this.updateSeparator(this.inspectorHandle, 280, 520, this.value.inspectorWidth);
    this.updateSeparator(this.dockHandle, 180, Math.floor((this.editor?.clientHeight || innerHeight) * .55), this.value.dockHeight);
    document.querySelectorAll('[data-inspector-tab]').forEach(tab =>
      tab.setAttribute('aria-selected', String(tab.dataset.inspectorTab === this.value.inspectorTab)));
    document.querySelectorAll('[data-inspector-panel]').forEach(panel =>
      panel.hidden = panel.dataset.inspectorPanel !== this.value.inspectorTab);
    document.querySelectorAll('[data-output]').forEach(tab =>
      tab.setAttribute('aria-selected', String(tab.dataset.output === this.value.dockTab)));
    document.querySelectorAll('.output-panel').forEach(panel =>
      panel.hidden = this.value.dockCollapsed || panel.dataset.panel !== this.value.dockTab);
    if (save) this.save();
  }

  updateSeparator(element, minimum, maximum, current) {
    element?.setAttribute('aria-valuemin', String(minimum));
    element?.setAttribute('aria-valuemax', String(maximum));
    element?.setAttribute('aria-valuenow', String(Math.round(current)));
  }

  load() {
    try { return normalizeLayout(JSON.parse(localStorage.getItem(this.key) || 'null'), innerHeight); }
    catch (error) { this.storageError(error); return { ...DEFAULTS }; }
  }
  save() {
    try { localStorage.setItem(this.key, JSON.stringify(this.value)); }
    catch (error) { this.storageError(error); }
  }
  storageError(error) {
    if (this.storageErrorAnnounced) return;
    this.storageErrorAnnounced = true;
    this.onStorageError?.(error);
  }
}
