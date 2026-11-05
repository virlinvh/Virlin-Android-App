const styleFields = ['marks', 'color', 'highlight', 'fontFamily', 'fontSizeSp', 'linkUrl'];

function sameStyle(a, b) {
  return styleFields.every(field => JSON.stringify(a[field] ?? null) === JSON.stringify(b[field] ?? null));
}

function compact(runs, length) {
  const sorted = runs.filter(run => run.end > run.start && run.start >= 0 && run.end <= length)
    .sort((a, b) => a.start - b.start);
  const out = [];
  for (const run of sorted) {
    const last = out.at(-1);
    if (last && last.end === run.start && sameStyle(last, run)) last.end = run.end;
    else out.push({ ...run, marks: [...(run.marks || [])] });
  }
  return out;
}

export function shiftNoteRuns(runs, before, after) {
  if (before === after) return runs;
  let start = 0;
  while (start < before.length && start < after.length && before[start] === after[start]) start++;
  let suffix = 0;
  while (suffix < before.length - start && suffix < after.length - start &&
    before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix++;
  const removedEnd = before.length - suffix;
  const inserted = after.length - start - suffix;
  const delta = after.length - before.length;
  const seed = start > 0 ? runs.find(run => run.start <= start - 1 && run.end > start - 1) :
    runs.find(run => run.start === 0 && run.end > 0);
  const result = [];
  for (const run of runs) {
    if (run.start < start) result.push({ ...run, end: Math.min(run.end, start) });
    if (run.end > removedEnd) result.push({ ...run,
      start: Math.max(run.start, removedEnd) + delta, end: run.end + delta });
  }
  if (inserted > 0 && seed) result.push({ ...seed, start, end: start + inserted });
  return compact(result, after.length);
}

export function toggleNoteMark(runs, length, start, end, mark) {
  if (start < 0 || end > length || end <= start) return runs;
  const edges = [...new Set([0, length, start, end, ...runs.flatMap(run => [run.start, run.end])])]
    .filter(edge => edge >= 0 && edge <= length).sort((a, b) => a - b);
  const hasEverywhere = edges.slice(0, -1).filter((edge, i) => edge >= start && edges[i + 1] <= end)
    .every(edge => (runs.find(run => run.start <= edge && run.end > edge)?.marks || []).includes(mark));
  const output = [];
  for (let i = 0; i < edges.length - 1; i++) {
    const a = edges[i], b = edges[i + 1];
    if (a === b) continue;
    const old = runs.find(run => run.start <= a && run.end >= b);
    const base = old ? { ...old, start: a, end: b } : { start: a, end: b, marks: [] };
    if (a >= start && b <= end) {
      const set = new Set(base.marks || []);
      if (hasEverywhere) set.delete(mark); else set.add(mark);
      base.marks = [...set];
    }
    if ((base.marks || []).length || styleFields.slice(1).some(field => base[field] != null)) output.push(base);
  }
  return compact(output, length);
}

export function setNoteStyle(runs, length, start, end, field, value) {
  if (!['fontFamily', 'fontSizeSp', 'linkUrl'].includes(field) || start < 0 || end > length || end <= start) return runs;
  const edges = [...new Set([0, length, start, end, ...runs.flatMap(run => [run.start, run.end])])]
    .filter(edge => edge >= 0 && edge <= length).sort((a, b) => a - b);
  const output = [];
  for (let i = 0; i < edges.length - 1; i++) {
    const a = edges[i], b = edges[i + 1];
    if (a === b) continue;
    const old = runs.find(run => run.start <= a && run.end >= b);
    const next = old ? { ...old, start: a, end: b } : { start: a, end: b, marks: [] };
    if (a >= start && b <= end) {
      if (value == null || value === '') delete next[field]; else next[field] = value;
    }
    if ((next.marks || []).length || styleFields.slice(1).some(key => next[key] != null)) output.push(next);
  }
  return compact(output, length);
}
