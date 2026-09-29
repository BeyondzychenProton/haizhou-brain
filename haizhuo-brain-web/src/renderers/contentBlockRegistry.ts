/**
 * 可扩展的内容块协议。当前只提供注册/查找能力；文档解析和预览不在本轮实现。
 * Renderer 接收已经过权限过滤的内容，不应自行请求未授权资源。
 */
export type ContentBlockType =
  | 'text'
  | 'image'
  | 'audio'
  | 'video'
  | 'document'
  | 'tool'
  | 'artifact'
  | 'interaction'

export interface ContentBlock<T = Record<string, unknown>> {
  id?: string
  type: ContentBlockType | (string & {})
  data: T
  mimeType?: string
  title?: string
  sourceEventId?: string
}

export interface ContentBlockRenderContext {
  runId?: string
  sessionId?: string
  readonly: boolean
}

export type ContentBlockRenderer<T extends ContentBlock = ContentBlock> =
  (block: T, context: ContentBlockRenderContext) => unknown

const renderers = new Map<string, ContentBlockRenderer>()

export function registerContentBlockRenderer(type: string, renderer: ContentBlockRenderer): () => void {
  if (!type.trim()) throw new Error('content block type is required')
  renderers.set(type, renderer)
  return () => {
    if (renderers.get(type) === renderer) renderers.delete(type)
  }
}

export function rendererForContentBlock(type: string): ContentBlockRenderer | undefined {
  return renderers.get(type)
}

export function hasContentBlockRenderer(type: string): boolean {
  return renderers.has(type)
}

