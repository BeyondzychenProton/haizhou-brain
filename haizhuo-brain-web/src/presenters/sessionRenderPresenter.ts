export interface RenderBlock {
  blockId: string
  type: string
  text: string
  lastAppliedOffset: number
}

export interface RenderMessage {
  messageId: string
  runId: string
  attemptId?: string | null
  executorRoleId?: string | null
  kind: string
  itemType: string
  text: string
  blocks: RenderBlock[]
  resultId?: string | null
  bodySource: string
  phase?: string | null
  partial: boolean
  occurredAt: string
  pending?: boolean
  resultLoadState?: 'failed'
  legacySummary: boolean
  mediaType?: string
  bodySha256?: string
}

export interface RenderView {
  schemaVersion: 3
  sessionId: string
  runs: Array<{ runId: string; state: string }>
  items: Array<{
    messageId: string
    runId: string
    attemptId?: string | null
    executorRoleId?: string | null
    kind: string
    itemType: string
    text: string
    blocks: RenderBlock[]
    resultId: string | null
    legacySummary: boolean
    partial: boolean
    phase?: string | null
    occurredAt: string
    bodySource: string
  }>
  snapshotCursor: number
  cursorFloor: number
  renderCursor: number
  renderCursorFloor: number
  draftRecoveryStatus: string
  nextCursor: string | null
  hasMore: boolean
}

export interface RenderEvent {
  schemaVersion: 3
  eventId: string
  sessionId: string
  sessionCursor: number | null
  renderCursor: number | null
  runId: string | null
  runSequence: number | null
  attemptId: string | null
  streamOffset: number | null
  type: string
  visibility: string
  durability: string
  occurredAt: string
  payload: {
    messageId: string | null
    blockId: string | null
    fromOffset: number | null
    toOffset: number | null
    delta: string | null
    text: string | null
    resultId: string | null
    metadata?: Record<string, unknown>
  }
}

export interface RenderProjection {
  sessionId: string
  items: RenderMessage[]
  sessionCursor: number
  renderCursor: number
  lastOffsetByAttempt: Record<string, number>
  sealedRuns: Record<string, boolean>
  reloadRequired: boolean
  draftRecoveryStatus: string
}

export function projectionFromView(view: RenderView, previous?: RenderProjection): RenderProjection {
  const previousFinals = new Map((previous?.items ?? [])
    .filter(item => item.bodySource === 'canonical-result').map(item => [item.runId, item]))
  const items: RenderMessage[] = view.items.map(item => {
    const preserved = previousFinals.get(item.runId)
    if (item.kind === 'ROOT_FINAL' && preserved) return preserved
    return {
      messageId: item.kind === 'ROOT_FINAL' ? item.runId + '-assistant' : item.messageId,
      runId: item.runId,
      attemptId: item.attemptId,
      executorRoleId: item.executorRoleId,
      kind: item.kind,
      itemType: item.itemType,
      text: item.text ?? '',
      blocks: item.blocks ?? [],
      resultId: item.resultId,
      bodySource: item.bodySource,
      phase: item.phase,
      partial: item.partial,
      legacySummary: item.legacySummary,
      occurredAt: item.occurredAt,
    }
  })
  const offsets: Record<string, number> = {}
  const sealedRuns: Record<string, boolean> = {}
  for (const run of view.runs) {
    if (isSealedRunState(run.state)) sealedRuns[run.runId] = true
  }
  for (const item of items) {
    if (item.kind === 'ROOT_FINAL') sealedRuns[item.runId] = true
    if (item.kind !== 'ASSISTANT_DRAFT' || !item.attemptId) continue
    const key = attemptKey(item.runId, item.attemptId)
    offsets[key] = Math.max(offsets[key] ?? 0, ...item.blocks.map(block => block.lastAppliedOffset))
  }
  for (const [runId, isSealed] of Object.entries(previous?.sealedRuns ?? {})) {
    if (isSealed) sealedRuns[runId] = true
  }
  return {
    sessionId: view.sessionId,
    items: mergeCanonical(items, previous?.items ?? []),
    sessionCursor: view.snapshotCursor,
    renderCursor: view.renderCursor,
    lastOffsetByAttempt: offsets,
    sealedRuns,
    reloadRequired: false,
    draftRecoveryStatus: view.draftRecoveryStatus,
  }
}

export function applyRenderEvent(projection: RenderProjection, event: RenderEvent): RenderProjection {
  if (event.sessionId !== projection.sessionId) return projection
  if (event.type === 'SESSION_CURSOR_EXPIRED' || event.type === 'RENDER_CURSOR_EXPIRED')
    return { ...projection, reloadRequired: true }
  if (event.visibility !== 'USER') return projection
  if (event.durability === 'durable') {
    const cursor = event.sessionCursor ?? 0
    if (cursor <= projection.sessionCursor) return projection
    if (!event.runId) return { ...projection, sessionCursor: cursor }
    if (event.type === 'RUN_COMPLETED') {
      const key = event.runId + '-assistant'
      const existing = projection.items.find(item => item.messageId === key)
      const final: RenderMessage = {
        messageId: key, runId: event.runId, kind: 'ROOT_FINAL', itemType: event.type,
        text: event.payload.text ?? '', blocks: [], resultId: event.payload.resultId,
        bodySource: event.payload.resultId ? 'canonical-result' : 'legacy-summary',
        phase: 'COMPLETE', partial: false, legacySummary: !event.payload.resultId,
        occurredAt: event.occurredAt,
      }
      return {
        ...projection,
        items: existing ? projection.items.map(item => item.messageId === key
          ? item.bodySource === 'canonical-result' ? { ...item, phase: 'COMPLETE', partial: false, pending: false }
            : { ...item, ...final }
          : item)
          : [...projection.items, final],
        sealedRuns: { ...projection.sealedRuns, [event.runId]: true },
        sessionCursor: cursor,
      }
    }
    const key = event.payload.messageId ?? (event.runId + '-' + (event.runSequence ?? cursor) + '-' + event.type)
    if (projection.items.some(item => item.messageId === key)) return { ...projection, sessionCursor: cursor }
    const kind = event.type === 'USER_INPUT' ? 'USER_INPUT' : 'PUBLIC_STATUS'
    const terminalPhase = terminalPhaseForEvent(event.type)
    return {
      ...projection,
      items: [...projection.items.map(item => terminalPhase && item.runId === event.runId
        && item.kind === 'ASSISTANT_DRAFT'
        ? { ...item, phase: terminalPhase, pending: false }
        : item), {
        messageId: key, runId: event.runId, kind, itemType: event.type, text: event.payload.text ?? '',
        blocks: [], resultId: event.payload.resultId, bodySource: kind === 'USER_INPUT' ? 'input' : 'status',
        phase: terminalPhase ?? undefined, partial: false, legacySummary: false,
        occurredAt: event.occurredAt,
      }],
      sealedRuns: terminalPhase ? { ...projection.sealedRuns, [event.runId]: true } : projection.sealedRuns,
      sessionCursor: cursor,
    }
  }
  if (event.type !== 'message.text.batch' || event.renderCursor == null || !event.runId
      || !event.attemptId || !event.payload.messageId || !event.payload.blockId
      || event.payload.fromOffset == null || event.payload.toOffset == null || !event.payload.delta) return projection
  if (event.renderCursor <= projection.renderCursor) return projection
  if (event.renderCursor !== projection.renderCursor + 1) return { ...projection, reloadRequired: true }
  if (projection.sealedRuns[event.runId]) return { ...projection, renderCursor: event.renderCursor }
  const attempt = attemptKey(event.runId, event.attemptId)
  const expected = projection.lastOffsetByAttempt[attempt] ?? 0
  if (event.payload.toOffset - event.payload.fromOffset !== [...event.payload.delta].length)
    return { ...projection, reloadRequired: true }
  if (event.payload.toOffset <= expected) return { ...projection, renderCursor: event.renderCursor }
  if (event.payload.fromOffset !== expected) return { ...projection, reloadRequired: true }

  const items = [...projection.items]
  const index = items.findIndex(item => item.messageId === event.payload.messageId
    && item.kind === 'ASSISTANT_DRAFT' && item.attemptId === event.attemptId)
  if (index < 0) {
    items.push({
      messageId: event.payload.messageId, runId: event.runId, attemptId: event.attemptId,
      kind: 'ASSISTANT_DRAFT', itemType: event.type, text: event.payload.delta,
      blocks: [{ blockId: event.payload.blockId, type: 'TEXT', text: event.payload.delta,
        lastAppliedOffset: event.payload.toOffset }],
      bodySource: 'draft', phase: 'generating', partial: true, pending: true, occurredAt: event.occurredAt,
      legacySummary: false,
    })
  } else {
    const item = items[index]
    const blocks = [...item.blocks]
    const blockIndex = blocks.findIndex(block => block.blockId === event.payload.blockId)
    if (blockIndex < 0) blocks.push({ blockId: event.payload.blockId, type: 'TEXT',
      text: event.payload.delta, lastAppliedOffset: event.payload.toOffset })
    else blocks[blockIndex] = { ...blocks[blockIndex], text: blocks[blockIndex].text + event.payload.delta,
      lastAppliedOffset: event.payload.toOffset }
    items[index] = { ...item, blocks, text: blocks.map(block => block.text).join('') }
  }
  return {
    ...projection,
    items,
    renderCursor: event.renderCursor,
    lastOffsetByAttempt: { ...projection.lastOffsetByAttempt, [attempt]: event.payload.toOffset },
  }
}

export function applyCanonicalRenderResult(projection: RenderProjection, result: {
  runId: string; resultId: string | null; body: string; mediaType: string; bodySha256: string
}): RenderProjection {
  const key = result.runId + '-assistant'
  const current = projection.items.find(item => item.messageId === key)
  const final: RenderMessage = {
    messageId: key, runId: result.runId, kind: 'ROOT_FINAL', itemType: 'RUN_COMPLETED',
    text: result.body, blocks: [], resultId: result.resultId, bodySource: 'canonical-result',
    phase: 'COMPLETE', partial: false, pending: false, legacySummary: false,
    occurredAt: current?.occurredAt ?? new Date().toISOString(), mediaType: result.mediaType,
    bodySha256: result.bodySha256,
    resultLoadState: undefined,
  }
  return {
    ...projection,
    items: current ? projection.items.map(item => item.messageId === key ? final : item) : [...projection.items, final],
    sealedRuns: { ...projection.sealedRuns, [result.runId]: true },
  }
}

export function markRenderResultLoadFailed(projection: RenderProjection, runId: string): RenderProjection {
  const key = runId + '-assistant'
  return {
    ...projection,
    items: projection.items.map(item => item.messageId === key && item.kind === 'ROOT_FINAL'
      ? { ...item, resultLoadState: 'failed' }
      : item),
  }
}

function attemptKey(runId: string, attemptId: string): string { return runId + ':' + attemptId }
function isSealedRunState(state: string): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TERMINATED', 'EXPIRED', 'RECOVERY_REQUIRED'].includes(state)
}
function terminalPhaseForEvent(type: string): string | null {
  const phases: Record<string, string> = {
    RUN_FAILED: 'FAILED', RUN_CANCELLED: 'CANCELLED', RUN_RECOVERY_REQUIRED: 'RECOVERY_REQUIRED',
    RUN_RECOVERY_TERMINATED: 'TERMINATED', RUN_EXPIRED: 'EXPIRED',
  }
  return phases[type] ?? null
}
function mergeCanonical(items: RenderMessage[], previous: RenderMessage[]): RenderMessage[] {
  const finals = new Map(previous.filter(item => item.bodySource === 'canonical-result')
    .map(item => [item.messageId, item]))
  return items.map(item => finals.get(item.messageId) ?? item)
}
