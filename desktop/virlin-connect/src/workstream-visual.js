// Match Android's activityState projection before falling back to the stream state.
export function workstreamVisual(stream, tasks = []) {
  const active = tasks.filter(task => task.workStreamId === stream.id &&
    !['DONE', 'CANCELLED'].includes(task.status) && task.executionState);
  const running = active.filter(task => ['FOCUS', 'PROCESSING'].includes(task.executionState));
  let kind;
  if (running.length >= 2) kind = 'multi-run';
  else if (active.some(task => task.executionState === 'FOCUS')) kind = 'focus';
  else if (active.some(task => task.executionState === 'PROCESSING')) kind = 'running';
  else if (active.some(task => task.executionState === 'CHECK')) kind = 'review';
  else if (active.some(task => task.executionState === 'SNOOZED')) kind = 'waiting';
  else if (active.some(task => task.executionState === 'PAUSED')) kind = 'paused';
  else if (active.some(task => task.executionState === 'BLOCKED')) kind = 'blocked';
  else kind = ({ FOCUS: 'focus', PROCESSING: 'running', CHECK: 'review', READY: 'ready',
    SNOOZED: 'waiting', PAUSED: 'paused', BLOCKED: 'blocked', DONE: 'done' })[stream.effectiveState || stream.state] || 'ready';
  return { kind, label: ({ 'multi-run': 'MULTI RUN', focus: 'FOCUS', running: 'RUNNING',
    review: 'REVIEW', ready: 'READY', waiting: 'WAITING', paused: 'PAUSED', blocked: 'BLOCKED', done: 'DONE' })[kind],
    count: kind === 'multi-run' ? running.length : null };
}

const common = 'viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"';
const icons = {
  ready: '<rect x="12" y="13" width="22" height="27" rx="3" stroke="currentColor" stroke-width="2.5"/><rect x="18" y="9" width="10" height="7" rx="2" fill="white" stroke="currentColor" stroke-width="2.5"/><path d="M18 23h10M18 29h10M18 35h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path class="icon-sparkle" d="M37 6l1.5 4L42 11.5l-3.5 1.5L37 17l-1.5-4L32 11.5l3.5-1.5z" fill="currentColor"/>',
  review: '<circle cx="24" cy="24" r="18" stroke="currentColor" stroke-opacity=".24" stroke-width="1.5"/><path d="M15 30c-5-3-2-10 4-10 2-7 12-7 14 0 7 0 9 10 2 11H17" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/><circle class="icon-orbit" cx="24" cy="6" r="2.7" fill="currentColor"/>',
  running: '<path class="icon-wave icon-wave-one" d="M3 18c6-9 12 9 18 0s12 9 24 0" stroke="currentColor" stroke-width="3" stroke-linecap="round"/><path class="icon-wave icon-wave-two" d="M3 25c6-9 12 9 18 0s12 9 24 0" stroke="currentColor" stroke-opacity=".6" stroke-width="3" stroke-linecap="round"/><path class="icon-wave icon-wave-three" d="M3 32c6-9 12 9 18 0s12 9 24 0" stroke="currentColor" stroke-opacity=".35" stroke-width="3" stroke-linecap="round"/>',
  focus: '<g class="icon-focus-frame" stroke="currentColor" stroke-width="3.5" stroke-linecap="round"><path d="M7 18V8h10M31 8h10v10M41 31v10H31M17 41H7V31"/></g><path d="M24 18l6 6-6 6-6-6z" fill="currentColor"/>',
  'multi-run': '<g class="icon-multi-orbit" stroke-width="3.5" stroke-linecap="round"><path d="M12 13a17 17 0 0 1 13-6" stroke="#1FA676"/><path d="M30 10a17 17 0 0 1 11 13" stroke="#49A9FF"/><path d="M38 31a17 17 0 0 1-13 10" stroke="#E6A400"/><path d="M18 39A17 17 0 0 1 7 25" stroke="#7757E8"/></g><circle cx="24" cy="24" r="8" fill="white"/>',
  waiting: '<g class="icon-waiting-dots" fill="currentColor"><circle cx="24" cy="8" r="2.5"/><circle cx="35" cy="13" r="2.5"/><circle cx="40" cy="24" r="2.5"/><circle cx="35" cy="35" r="2.5"/><circle cx="24" cy="40" r="2.5"/><circle cx="13" cy="35" r="2.5"/><circle cx="8" cy="24" r="2.5"/><circle cx="13" cy="13" r="2.5"/></g>',
  paused: '<path d="M6 24h4m3 0h4m3 0h4" stroke="currentColor" stroke-width="3" stroke-linecap="round"/><circle class="icon-pause-halo" cx="28" cy="24" r="8" fill="currentColor" opacity=".13"/><circle cx="28" cy="24" r="3.5" fill="currentColor"/><path d="M35 16v16" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/><circle cx="41" cy="24" r="1.5" fill="currentColor"/>',
  blocked: '<path d="M3 24h12m18 0h12" stroke="#BCC6C2" stroke-width="5" stroke-linecap="round"/><rect x="19" y="20" width="10" height="12" rx="2" fill="currentColor"/><path d="M21 20v-4a3 3 0 0 1 6 0v4" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>',
  done: '<circle cx="24" cy="24" r="17" stroke="currentColor" stroke-width="3.5"/><path d="m15 24 6 6 12-13" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>',
};

export function workstreamIconMarkup(kind) {
  return `<svg ${common} aria-hidden="true" focusable="false">${icons[kind] || icons.ready}</svg>`;
}
