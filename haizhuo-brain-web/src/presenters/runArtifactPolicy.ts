export interface MarkdownArtifactRun {
  runId: string
  state: string
}

export interface MarkdownArtifactResult {
  runId: string
  legacySummary: boolean
}

/** 仅当选中的 Run 已成功且其规范结果已完整读取时，才允许发起新的导出。 */
export function isMarkdownArtifactExportAllowed(
  run: MarkdownArtifactRun | null | undefined,
  result: MarkdownArtifactResult | null | undefined,
): boolean {
  return Boolean(run && run.state === 'SUCCEEDED' && result
    && result.runId === run.runId && !result.legacySummary)
}
