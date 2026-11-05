/** Bounded, local-only edit history. Phone writes still happen only on explicit Save. */
export function createNoteHistory(initial, limit = 100) {
  let present = JSON.stringify(initial);
  const baseline = present;
  const past = [];
  const future = [];
  let group = null;
  const state = () => ({ canUndo: past.length > 0, canRedo: future.length > 0, dirty: present !== baseline });
  return {
    state,
    record(next, kind = 'action', key = '', now = Date.now()) {
      const serialized = JSON.stringify(next);
      if (serialized === present) return state();
      const coalesce = kind === 'typing' && group?.key === key && now - group.at < 700;
      if (!coalesce) {
        past.push(present);
        if (past.length > limit) past.shift();
      }
      present = serialized;
      future.length = 0;
      group = kind === 'typing' ? { key, at: now } : null;
      return state();
    },
    undo() {
      if (!past.length) return null;
      future.push(present);
      present = past.pop();
      group = null;
      return JSON.parse(present);
    },
    redo() {
      if (!future.length) return null;
      past.push(present);
      present = future.pop();
      group = null;
      return JSON.parse(present);
    },
  };
}
