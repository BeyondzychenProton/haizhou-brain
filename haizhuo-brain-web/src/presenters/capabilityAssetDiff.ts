export interface AssetFileShape {
  relativePath: string
  byteSize: number
  sha256: string
  content: string
}

export interface AssetFileChange {
  relativePath: string
  kind: 'ADDED' | 'REMOVED' | 'CHANGED'
  previousBytes: number | null
  currentBytes: number | null
  previousHash: string | null
  currentHash: string | null
}

export interface TextDiffLine {
  kind: 'same' | 'removed' | 'added'
  text: string
}

export function compareAssetFiles(previous: AssetFileShape[], current: AssetFileShape[]): AssetFileChange[] {
  const before = new Map(previous.map(file => [file.relativePath, file]))
  const after = new Map(current.map(file => [file.relativePath, file]))
  const paths = [...new Set([...before.keys(), ...after.keys()])].sort((left, right) => left.localeCompare(right))
  const changes: AssetFileChange[] = []
  for (const relativePath of paths) {
    const oldFile = before.get(relativePath)
    const newFile = after.get(relativePath)
    if (!oldFile && newFile) {
      changes.push({ relativePath, kind: 'ADDED', previousBytes: null, currentBytes: newFile.byteSize,
        previousHash: null, currentHash: newFile.sha256 })
    } else if (oldFile && !newFile) {
      changes.push({ relativePath, kind: 'REMOVED', previousBytes: oldFile.byteSize, currentBytes: null,
        previousHash: oldFile.sha256, currentHash: null })
    } else if (oldFile && newFile && (oldFile.sha256 !== newFile.sha256 || oldFile.byteSize !== newFile.byteSize)) {
      changes.push({ relativePath, kind: 'CHANGED', previousBytes: oldFile.byteSize, currentBytes: newFile.byteSize,
        previousHash: oldFile.sha256, currentHash: newFile.sha256 })
    }
  }
  return changes
}

/** 以线性时间生成有界单块差异，原始文本仍会并列保留以供查看。 */
export function makeTextDiff(previousText: string, currentText: string, maxLines = 20_000): TextDiffLine[] {
  const previous = previousText.split(/\r?\n/)
  const current = currentText.split(/\r?\n/)
  if (previous.length + current.length > maxLines) {
    return [{ kind: 'same', text: `差异预览超过 ${maxLines.toLocaleString()} 行；请切换原文查看完整内容。` }]
  }
  if (previousText === currentText) return previous.map(text => ({ kind: 'same' as const, text }))

  let prefix = 0
  while (prefix < previous.length && prefix < current.length && previous[prefix] === current[prefix]) prefix++
  let suffix = 0
  while (suffix < previous.length - prefix && suffix < current.length - prefix
    && previous[previous.length - suffix - 1] === current[current.length - suffix - 1]) suffix++

  return [
    ...previous.slice(0, prefix).map(text => ({ kind: 'same' as const, text })),
    ...previous.slice(prefix, previous.length - suffix).map(text => ({ kind: 'removed' as const, text })),
    ...current.slice(prefix, current.length - suffix).map(text => ({ kind: 'added' as const, text })),
    ...(suffix > 0 ? previous.slice(previous.length - suffix).map(text => ({ kind: 'same' as const, text })) : []),
  ]
}
