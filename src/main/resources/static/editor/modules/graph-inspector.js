export class GraphInspector {
  constructor({ onSelectSource, onEditField, suggestions, inputForField }) {
    this.content = document.querySelector('#inspector-content');
    this.selection = document.querySelector('#inspector-selection');
    this.onSelectSource = onSelectSource;
    this.onEditField = onEditField;
    this.suggestions = suggestions || (() => []);
    this.inputForField = inputForField || (() => null);
  }
  render(nodes) {
    if (!nodes?.length) {
      this.selection.textContent = 'Nothing selected';
      this.content.innerHTML = '<p>Select a node to inspect its source-mapped properties.</p>';
      return;
    }
    this.selection.textContent = nodes.length === 1 ? nodes[0].title : nodes.length + ' nodes';
    if (nodes.length > 1) {
      const heading = document.createElement('p'); heading.textContent = 'Multi-selection';
      const list = document.createElement('ul');
      list.replaceChildren(...nodes.map(node => { const item = document.createElement('li'); item.textContent = node.title; return item; }));
      this.content.replaceChildren(heading, list); return;
    }
    const node = nodes[0], fragment = document.createDocumentFragment();
    const heading = document.createElement('h3'); heading.textContent = node.title;
    const subtitle = document.createElement('p'); subtitle.textContent = node.subtitle || node.kind;
    const path = document.createElement('button'); path.type = 'button'; path.className = 'inspector-path';
    path.textContent = node.yamlPath || 'Synthetic graph node'; path.disabled = !node.yamlPath;
    path.addEventListener('click', () => this.onSelectSource(node.yamlPath, node.range));
    fragment.append(heading, subtitle, path);
    for (const field of node.fields || []) {
      const label = document.createElement('label'); label.className = 'inspector-field';
      const title = document.createElement('span'); title.textContent = field.label + (field.required ? ' *' : '');
      const customInput = this.inputForField(field, node);
      const input = customInput || document.createElement('input');
      if (!customInput) {
        input.value = field.value ?? ''; input.disabled = !field.editable || field.custom;
        if (field.valueType === 'boolean') input.type = 'checkbox', input.checked = field.value === 'true';
        else if (field.valueType === 'integer' || field.valueType === 'number') input.type = 'number';
      }
      input.setAttribute('aria-describedby', field.id + '-path');
      if (!customInput) input.addEventListener('change', () => this.onEditField(field,
        input.type === 'checkbox' ? String(input.checked) : input.value));
      const suggestions = [...new Set(this.suggestions(field, node) || [])].filter(Boolean).slice(0, 200);
      let datalist = null;
      if (suggestions.length && input.type === 'text') {
        datalist = document.createElement('datalist'); datalist.id = `${field.id}-suggestions`;
        datalist.replaceChildren(...suggestions.map(value => { const option = document.createElement('option'); option.value = value; return option; }));
        input.setAttribute('list', datalist.id);
      }
      const location = document.createElement('small'); location.id = field.id + '-path'; location.className = 'inspector-path';
      location.textContent = field.yamlPath + ' · ' + field.range.startLine + ':' + field.range.startColumn;
      location.addEventListener('click', () => this.onSelectSource(field.yamlPath, field.range));
      label.append(title, input, location); if (input._catalogList) label.append(input._catalogList);
      if (datalist) label.append(datalist); fragment.append(label);
    }
    if (!node.fields?.length) {
      const empty = document.createElement('p'); empty.textContent = node.custom
        ? 'This exact YAML range is preserved and can be edited in the YAML panel.'
        : 'This node has no scalar properties at this level.'; fragment.append(empty);
    }
    this.content.replaceChildren(fragment);
  }
}
