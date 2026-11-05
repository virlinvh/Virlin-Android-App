/** One project's in-session confirmed map commands. Failed network writes never enter history. */
export class MapHistory {
  constructor(limit = 25) { this.limit = limit; this.past = []; this.future = []; }
  get canUndo() { return this.past.length > 0; }
  get canRedo() { return this.future.length > 0; }
  record(command) {
    this.past.push(command);
    if (this.past.length > this.limit) this.past.shift();
    this.future = [];
  }
  candidate(direction) { return direction === 'undo' ? this.past.at(-1) : this.future.at(-1); }
  confirm(direction) {
    if (direction === 'undo') this.future.push(this.past.pop());
    else this.past.push(this.future.pop());
  }
  clear() { this.past = []; this.future = []; }
}
