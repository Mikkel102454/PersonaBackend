import { connectionOwners, connectionsForNode } from './graph-connections.js';

export class GraphInspector {
  constructor({ onSelectSource, onEditField, onFocusNode, onParameterAction, suggestions, inputForField }) {
    this.content = document.querySelector('#inspector-content');
    this.selection = document.querySelector('#inspector-selection');
    this.onSelectSource = onSelectSource;
    this.onEditField = onEditField;
    this.onFocusNode = onFocusNode;
    this.onParameterAction = onParameterAction;
    this.suggestions = suggestions || (() => []);
    this.inputForField = inputForField || (() => null);
  }
  render(nodes, projection = null, liveNodeKeys = new Set()) {
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
    const properties = this.section('Properties');
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
      if (datalist) label.append(datalist); properties.append(label);
    }
    if (!node.fields?.length) {
      const empty = document.createElement('p'); empty.textContent = node.custom
        ? 'This exact YAML range is preserved and can be edited in the YAML panel.'
        : 'This node has no scalar properties at this level.'; properties.append(empty);
    }
    fragment.append(properties);
    const issues = (projection?.diagnostics || []).filter(issue => issue.nodeId === node.id
      || issue.yamlPath && node.yamlPath && (issue.yamlPath === node.yamlPath || issue.yamlPath.startsWith(node.yamlPath + '/')));
    fragment.append(this.itemsSection('Validation', issues, issue => `${issue.severity || 'issue'}: ${issue.message}`,
      'No diagnostics for this selection.', issue => this.onSelectSource(issue.yamlPath, issue.range)));
    const references = (projection?.edges || []).filter(edge => this.edgeTouches(edge, node, projection));
    fragment.append(this.itemsSection('References', references,
      edge => `${edge.label || edge.semanticType} · ${edge.resolved ? 'resolved' : 'unresolved'}`,
      'No projected references for this selection.'));
    const live = liveNodeKeys?.has?.(node.title) || liveNodeKeys?.has?.(node.yamlPath);
    fragment.append(this.itemsSection('Live', live ? ['This node is active in the trusted read-only live overlay.'] : [],
      value => value, 'No active live state for this selection.'));
    this.content.replaceChildren(fragment);
  }

  section(title) {
    const section = document.createElement('section'); section.className = 'inspector-section';
    const heading = document.createElement('h4'); heading.textContent = title; section.append(heading); return section;
  }

  itemsSection(title, values, label, empty, activate) {
    const section = this.section(title), list = document.createElement('ul');
    if (!values.length) { const item = document.createElement('li'); item.textContent = empty; list.append(item); }
    for (const value of values) {
      const item = document.createElement('li');
      if (activate && value.range) {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = label(value);
        button.addEventListener('click', () => activate(value)); item.append(button);
      } else item.textContent = label(value);
      list.append(item);
    }
    section.append(list); return section;
  }

  portsSection(node, projection) {
    const section = this.section('Ports'), list = document.createElement('ul');
    const edges = projection?.edges || [];
    const parameters = (node.pins || []).filter(port => port.channel === 'DATA').sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    for (const port of node.pins || []) {
      const count = edges.filter(edge => edge.sourcePinId === port.id || edge.targetPinId === port.id).length;
      const item = document.createElement('li'), button = document.createElement('button'); button.type = 'button';
      button.textContent = `${port.direction} ${port.label} · ${port.valueType || port.semanticType} · ${port.cardinality} · ${count} connection${count === 1 ? '' : 's'}`;
      button.disabled = !port.sourceRange && !port.yamlPath;
      button.addEventListener('click', () => this.onSelectSource(port.yamlPath, port.sourceRange));
      item.append(button);
      if (this.onParameterAction && ['script-input', 'script-output'].includes(node.kind) && port.channel === 'DATA') {
        const actions = document.createElement('span'); actions.className = 'inspector-port-actions';
        const addAction = (label, type, neighbor = null) => {
          const action = document.createElement('button'); action.type = 'button'; action.textContent = label;
          action.setAttribute('aria-label', `${label} parameter ${port.label}`);
          action.addEventListener('click', () => this.onParameterAction({ type, node, port, neighbor }));
          actions.append(action);
        };
        const index = parameters.findIndex(value => value.id === port.id);
        addAction('Rename', 'RENAME_SCRIPT_PARAMETER');
        addAction('Type', 'CHANGE_SCRIPT_PARAMETER_TYPE');
        if (index > 0) addAction('↑', 'REORDER_SCRIPT_PARAMETER', parameters[index - 1]);
        if (index >= 0 && index < parameters.length - 1) addAction('↓', 'REORDER_SCRIPT_PARAMETER', parameters[index + 1]);
        addAction('Delete', 'DELETE_SCRIPT_PARAMETER');
        item.append(actions);
      }
      list.append(item);
    }
    if (!node.pins?.length) { const item = document.createElement('li'); item.textContent = 'No connection ports.'; list.append(item); }
    section.append(list); return section;
  }

  connectionsSection(node, projection) {
    const section = this.section('Connections'), list = document.createElement('ul');
    const owners = connectionOwners(projection);
    const nodes = new Map((projection?.nodes || []).map(value => [value.id, value]));
    const edges = connectionsForNode(projection, node.id);
    for (const edge of edges) {
      const outgoing = owners.get(edge.sourcePinId) === node.id;
      const other = nodes.get(owners.get(outgoing ? edge.targetPinId : edge.sourcePinId));
      const item = document.createElement('li'), button = document.createElement('button'); button.type = 'button';
      button.textContent = `${outgoing ? 'To' : 'From'} ${other?.title || 'unresolved'} via ${edge.label || edge.semanticType}`;
      button.addEventListener('click', () => other && this.onFocusNode?.(other.id)); item.append(button); list.append(item);
    }
    if (!edges.length) { const item = document.createElement('li'); item.textContent = 'No connections.'; list.append(item); }
    section.append(list); return section;
  }

  edgeTouches(edge, node, projection) {
    const ids = new Set((node.pins || []).map(port => port.id));
    return ids.has(edge.sourcePinId) || ids.has(edge.targetPinId);
  }
}
