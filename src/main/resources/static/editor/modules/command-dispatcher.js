/** A single bounded route for toolbar, keyboard, palette, and canvas commands. */
export class CommandDispatcher {
  constructor() { this.commands = new Map(); }

  register(id, definition) {
    if (!id || this.commands.has(id) || typeof definition?.run !== 'function')
      throw new Error(`Invalid or duplicate command: ${id}`);
    this.commands.set(id, { id, label: definition.label || id, enabled: definition.enabled || (() => true),
      disabledReason: definition.disabledReason || (() => 'Unavailable in the current selection, capability, or connection state.'),
      keywords: definition.keywords || '', run: definition.run });
    return this;
  }

  execute(id, payload) {
    const command = this.commands.get(id);
    if (!command || !command.enabled(payload)) return false;
    command.run(payload); return true;
  }

  entries(query = '') {
    const needle = query.trim().toLowerCase();
    return [...this.commands.values()].filter(command =>
      !needle || `${command.label} ${command.keywords}`.toLowerCase().includes(needle))
      .map(command => ({ ...command, available: Boolean(command.enabled()),
        reason: command.enabled() ? '' : command.disabledReason() }));
  }

  bindButton(element, id, payload = () => undefined) {
    element.addEventListener('click', () => this.execute(id, payload()));
  }
}
