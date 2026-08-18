/** Selects a server-owned UTF-16 source range without interpreting or rewriting YAML. */
export function revealSource(textarea, range, { focus = true } = {}) {
  if (!textarea || !range || !Number.isInteger(range.startOffset) || !Number.isInteger(range.endOffset)) return false;
  const start = Math.max(0, Math.min(textarea.value.length, range.startOffset));
  const end = Math.max(start, Math.min(textarea.value.length, range.endOffset));
  if (focus) textarea.focus(); textarea.setSelectionRange(start, end); return true;
}
