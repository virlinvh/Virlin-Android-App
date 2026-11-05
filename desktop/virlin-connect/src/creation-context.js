/** Only offer creation at the hierarchy level currently shown on screen. */
export function creationContext(projectId, section, taskLocation) {
  if (!projectId) return { label: '＋ Project', kind: 'project' };

  if (taskLocation) {
    const parentTaskId = taskLocation.path?.at(-1);
    if (parentTaskId) return {
      label: '＋ Subtask', kind: 'task', ownerType: 'task', ownerId: parentTaskId,
    };
    if (taskLocation.type === 'workstream') return {
      label: '＋ Task', kind: 'task', ownerType: 'workstream', ownerId: taskLocation.id,
    };
    return { label: '＋ Task', kind: 'task', ownerType: 'project', ownerId: projectId };
  }

  // The map has its own item picker. Knowledge creation is not connected yet.
  if (section === 'Map' || section === 'Knowledge') return null;
  return {
    label: '＋ Add to project', kind: 'workstream', ownerType: 'project', ownerId: projectId,
    showProjectKinds: true,
  };
}
