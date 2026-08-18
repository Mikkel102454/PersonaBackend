/** Bounded, stable-ID graph selection state shared by rendering and keyboard actions. */
export class GraphSelection extends Set {
  constructor(values = [], maximum = 20_000) { super([...values].slice(0, maximum)); this.maximum = maximum; }
  add(value) { if (this.size < (this.maximum || 20_000)) super.add(value); return this; }
  retain(validIds) { for (const id of this) if (!validIds.has(id)) this.delete(id); return this; }
}
