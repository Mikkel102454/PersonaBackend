const VERSION = 1;
const DEFAULTS = Object.freeze({ version: VERSION, browserWidth: 288, sourceRatio: 0.5, browserCollapsed: false });

export function normalizeLayout(value) {
  if (!value || value.version !== VERSION) return { ...DEFAULTS };
  return {
    version: VERSION,
    browserWidth: Math.min(520, Math.max(192, Number(value.browserWidth) || DEFAULTS.browserWidth)),
    sourceRatio: Math.min(0.75, Math.max(0.25, Number(value.sourceRatio) || DEFAULTS.sourceRatio)),
    browserCollapsed: Boolean(value.browserCollapsed)
  };
}

export class PanelLayout {
  constructor(key) {
    this.key = key;
    this.workspace = document.querySelector('#workspace');
    this.browser = document.querySelector('#content-browser');
    this.split = document.querySelector('.split');
    this.browserHandle = document.querySelector('#browser-resizer');
    this.sourceHandle = document.querySelector('#source-resizer');
    this.toggle = document.querySelector('#browser-toggle');
    this.resetButton = document.querySelector('#layout-reset');
    this.value = this.load();
    this.bind();
    this.apply();
  }

  bind() {
    this.drag(this.browserHandle, event => {
      this.value.browserWidth = Math.min(520, Math.max(192, event.clientX));
      this.value.browserCollapsed = false; this.apply(false);
    });
    this.drag(this.sourceHandle, event => {
      const bounds = this.split.getBoundingClientRect();
      this.value.sourceRatio = Math.min(.75, Math.max(.25, (event.clientX - bounds.left) / bounds.width));
      this.apply(false);
    });
    this.toggle.addEventListener('click', () => {
      this.value.browserCollapsed = !this.value.browserCollapsed; this.apply();
    });
    this.resetButton.addEventListener('click', () => { this.value = { ...DEFAULTS }; this.apply(); });
  }

  drag(handle, update) {
    handle.addEventListener('pointerdown', event => {
      event.preventDefault(); handle.setPointerCapture(event.pointerId);
      const move = current => update(current);
      const end = () => { handle.removeEventListener('pointermove', move); this.save(); };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', end, { once: true });
      handle.addEventListener('pointercancel', end, { once: true });
    });
    handle.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      event.preventDefault();
      const delta = event.key === 'ArrowLeft' ? -16 : 16;
      if (handle === this.browserHandle) this.value.browserWidth += delta;
      else this.value.sourceRatio += delta / Math.max(320, this.split.clientWidth);
      this.value = normalizeLayout(this.value); this.apply();
    });
  }

  apply(save = true) {
    this.value = normalizeLayout(this.value);
    this.workspace.style.setProperty('--browser-width', this.value.browserCollapsed ? '0px' : this.value.browserWidth + 'px');
    this.split.style.setProperty('--visual-width', (this.value.sourceRatio * 100) + '%');
    this.browser.hidden = this.value.browserCollapsed;
    this.browserHandle.hidden = this.value.browserCollapsed;
    this.toggle.setAttribute('aria-expanded', String(!this.value.browserCollapsed));
    this.browserHandle.setAttribute('aria-valuemin', '192');
    this.browserHandle.setAttribute('aria-valuemax', '520');
    this.browserHandle.setAttribute('aria-valuenow', String(Math.round(this.value.browserWidth)));
    this.sourceHandle.setAttribute('aria-valuemin', '25');
    this.sourceHandle.setAttribute('aria-valuemax', '75');
    this.sourceHandle.setAttribute('aria-valuenow', String(Math.round(this.value.sourceRatio * 100)));
    this.toggle.textContent = this.value.browserCollapsed ? 'Show browser' : 'Hide browser';
    if (save) this.save();
  }

  load() {
    try { return normalizeLayout(JSON.parse(localStorage.getItem(this.key) || 'null')); }
    catch { return { ...DEFAULTS }; }
  }
  save() { localStorage.setItem(this.key, JSON.stringify(this.value)); }
}
