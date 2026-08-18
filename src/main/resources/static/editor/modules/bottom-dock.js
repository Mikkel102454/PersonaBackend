export class BottomDock {
  constructor(layout) {
    this.layout = layout;
    this.tabs = [...document.querySelectorAll('.output-tabs [data-output]')];
    for (const tab of this.tabs) tab.addEventListener('click', () => this.show(tab.dataset.output));
  }
  show(name, expand = true) { this.layout.selectDock(name, expand); }
}
