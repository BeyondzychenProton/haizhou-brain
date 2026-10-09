export interface CanonicalRunResult {
  resultId: string | null
  runId: string
  mediaType: string
  body: string
  bodySha256: string
  byteSize: number
  schemaVersion: number
  createdAt: string
  legacySummary: boolean
  executorRoleId: string | null
  executorEmployeeId: number | null
  executorDefinitionVersionId: number | null
}

export interface RunResultLoaderOptions {
  maxConcurrent?: number
  maxEntries?: number
  maxBytes?: number
  retryDelaysMs?: number[]
  sleep?: (delayMs: number) => Promise<void>
  isRetryable?: (error: unknown) => boolean
}

export class ResultIdentityMismatchError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ResultIdentityMismatchError'
  }
}

/** 只读 ROOT_FINAL 加载器，不会启动或恢复 Run。 */
export class RunResultLoader {
  private readonly inFlight = new Map<string, Promise<CanonicalRunResult>>()
  private readonly cache = new Map<string, { result: CanonicalRunResult; bytes: number }>()
  private readonly epochs = new Map<string, number>()
  private readonly waiters: Array<() => void> = []
  private activeReads = 0
  private cacheBytes = 0
  private readonly maxConcurrent: number
  private readonly maxEntries: number
  private readonly maxBytes: number
  private readonly retryDelaysMs: number[]
  private readonly sleep: (delayMs: number) => Promise<void>
  private readonly isRetryable: (error: unknown) => boolean

  constructor(
    private readonly read: (runId: string) => Promise<CanonicalRunResult>,
    options: RunResultLoaderOptions = {},
  ) {
    this.maxConcurrent = Math.max(1, options.maxConcurrent ?? 4)
    this.maxEntries = Math.max(1, options.maxEntries ?? 50)
    this.maxBytes = Math.max(1, options.maxBytes ?? 25 * 1024 * 1024)
    this.retryDelaysMs = options.retryDelaysMs ?? [500, 1500]
    this.sleep = options.sleep ?? (delay => new Promise(resolve => setTimeout(resolve, delay)))
    this.isRetryable = options.isRetryable ?? (error => {
      const status = (error as { response?: { status?: number } } | undefined)?.response?.status
      return status === undefined || status >= 500
    })
  }

  ensure(sessionId: string, runId: string, expectedResultId?: string | null): Promise<CanonicalRunResult> {
    const key = this.key(sessionId, runId)
    const cached = this.cache.get(key)
    if (cached) {
      this.touch(key, cached)
      return Promise.resolve(this.validate(cached.result, runId, expectedResultId))
    }

    let request = this.inFlight.get(key)
    if (!request) {
      const epoch = this.epochs.get(sessionId) ?? 0
      this.epochs.set(sessionId, epoch)
      const started = this.withReadSlot(() => this.readWithRetry(runId))
        .then(result => {
          const validated = this.validate(result, runId)
          if ((this.epochs.get(sessionId) ?? 0) === epoch) this.remember(key, validated)
          return validated
        })
        .finally(() => {
          if (this.inFlight.get(key) === started) this.inFlight.delete(key)
        })
      request = started
      this.inFlight.set(key, request)
    }
    return request.then(result => this.validate(result, runId, expectedResultId))
  }

  clearSession(sessionId: string): void {
    this.epochs.set(sessionId, (this.epochs.get(sessionId) ?? 0) + 1)
    const prefix = `${sessionId}\u0000`
    for (const [key, entry] of this.cache) {
      if (!key.startsWith(prefix)) continue
      this.cache.delete(key)
      this.cacheBytes -= entry.bytes
    }
    for (const key of this.inFlight.keys()) if (key.startsWith(prefix)) this.inFlight.delete(key)
  }

  clearAll(): void {
    for (const sessionId of this.epochs.keys()) this.epochs.set(sessionId, (this.epochs.get(sessionId) ?? 0) + 1)
    this.cache.clear()
    this.cacheBytes = 0
    this.inFlight.clear()
  }

  private async readWithRetry(runId: string): Promise<CanonicalRunResult> {
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.read(runId)
      } catch (error) {
        const delay = this.retryDelaysMs[attempt]
        if (delay === undefined || !this.isRetryable(error)) throw error
        await this.sleep(delay)
      }
    }
  }

  private async withReadSlot<T>(work: () => Promise<T>): Promise<T> {
    if (this.activeReads >= this.maxConcurrent) await new Promise<void>(resolve => this.waiters.push(resolve))
    else this.activeReads++
    try {
      return await work()
    } finally {
      const next = this.waiters.shift()
      if (next) next()
      else this.activeReads--
    }
  }

  private validate(result: CanonicalRunResult, runId: string, expectedResultId?: string | null): CanonicalRunResult {
    if (!result || result.runId !== runId) throw new ResultIdentityMismatchError('Run result does not match the requested Run')
    if (expectedResultId && result.resultId !== expectedResultId) {
      throw new ResultIdentityMismatchError('Run result does not match the completed event')
    }
    return result
  }

  private remember(key: string, result: CanonicalRunResult): void {
    const bytes = new TextEncoder().encode(result.body).byteLength
    const old = this.cache.get(key)
    if (old) this.cacheBytes -= old.bytes
    this.cache.delete(key)
    this.cache.set(key, { result, bytes })
    this.cacheBytes += bytes
    while (this.cache.size > this.maxEntries || this.cacheBytes > this.maxBytes) {
      const oldestKey = this.cache.keys().next().value as string | undefined
      if (!oldestKey) break
      const oldest = this.cache.get(oldestKey)
      this.cache.delete(oldestKey)
      this.cacheBytes -= oldest?.bytes ?? 0
    }
  }

  private touch(key: string, entry: { result: CanonicalRunResult; bytes: number }): void {
    this.cache.delete(key)
    this.cache.set(key, entry)
  }

  private key(sessionId: string, runId: string): string {
    return `${sessionId}\u0000${runId}`
  }
}
