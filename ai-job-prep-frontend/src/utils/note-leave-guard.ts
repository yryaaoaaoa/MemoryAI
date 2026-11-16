type LeaveGuard = () => Promise<boolean>

const guards = new Set<LeaveGuard>()
let confirmedNavigation = false
let checking: Promise<boolean> | undefined

export function registerNoteLeaveGuard(guard: LeaveGuard) {
  guards.add(guard)
  return () => { guards.delete(guard) }
}

export function confirmNoteLeave(): Promise<boolean> {
  if (confirmedNavigation) return Promise.resolve(true)
  if (!checking) checking = (async () => {
    for (const guard of [...guards]) if (!await guard()) return false
    return true
  })().finally(() => { checking = undefined })
  return checking
}

/** Used only after an explicit leave check, including logout or session expiry. */
export async function withConfirmedNoteNavigation(action: () => Promise<unknown>) {
  confirmedNavigation = true
  try { await action() } finally { confirmedNavigation = false }
}
