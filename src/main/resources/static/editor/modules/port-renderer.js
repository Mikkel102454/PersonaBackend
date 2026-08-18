/** Declarative port presentation; schema data is rendered only as inert text/classes. */
export function describePort(pin) {
  const category = `${String(pin.channel || 'DATA').toLowerCase()}-${pin.direction}`;
  return { text: pin.label, className: `graph-pin ${pin.direction} ${category}`,
    title: `${category.replace('-', ' ')} · ${pin.valueType || pin.semanticType} · ${pin.cardinality}${pin.required ? ' · required' : ' · optional'}`,
    ariaLabel: `${category.replace('-', ' ')} pin ${pin.label}, type ${pin.valueType || pin.semanticType}, ${pin.cardinality}`
      + (pin.required ? ', required' : ', optional') };
}
