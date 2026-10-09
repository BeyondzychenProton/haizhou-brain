<template>
  <div class="markdown-message" v-html="html" />
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { sanitizeDisplayText } from '../../utils/sanitizeDisplayText'

const props = defineProps<{ content: string; sanitizeSensitive?: boolean }>()

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: true,
})

/**
 * 图片是模型输出里最容易造成布局和安全问题的内容块：
 * 只允许 http(s)、站内相对路径和 blob URL，禁止 javascript/data 等协议。
 */
markdown.renderer.rules.image = (tokens, index, options, env, self) => {
  const token = tokens[index]
  const source = String(token.attrGet('src') ?? '')
  if (!isSafeResourceUrl(source, true)) return ''
  token.attrSet('loading', 'lazy')
  token.attrSet('decoding', 'async')
  token.attrSet('referrerpolicy', 'no-referrer')
  return self.renderToken(tokens, index, options)
}

/** 外链统一新窗口打开，并交给 DOMPurify 再做一遍属性级清洗。 */
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const token = tokens[index]
  const href = String(token.attrGet('href') ?? '')
  if (!isSafeResourceUrl(href, false)) return ''
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer nofollow')
  return self.renderToken(tokens, index, options)
}

const html = computed(() => {
  const content = props.sanitizeSensitive ? sanitizeDisplayText(props.content) : (props.content ?? '')
  const rendered = markdown.render(content)
  return DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
    ALLOWED_TAGS: [
      'p', 'br', 'strong', 'em', 's', 'del', 'code', 'pre',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'blockquote', 'ul', 'ol', 'li',
      'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'a', 'img', 'hr',
    ],
    ALLOWED_ATTR: [
      'href', 'target', 'rel', 'title', 'alt', 'src',
      'width', 'height', 'loading', 'decoding', 'referrerpolicy',
    ],
    ALLOW_DATA_ATTR: false,
  })
})

function isSafeResourceUrl(value: string, image: boolean): boolean {
  if (!value || value.startsWith('#')) return !image
  if (image && /^data:image\/(?:png|jpeg|gif|webp);base64,/i.test(value)) return true
  if (/^(https?:|\/|\.\/|\.\.\/|blob:)/i.test(value)) return true
  return false
}
</script>

<style scoped>
.markdown-message {
  color: inherit;
  font-size: 15px;
  line-height: 1.78;
  overflow-wrap: anywhere;
}
.markdown-message :deep(p) { margin: 0 0 12px; }
.markdown-message :deep(p:last-child),
.markdown-message :deep(ul:last-child),
.markdown-message :deep(ol:last-child),
.markdown-message :deep(blockquote:last-child),
.markdown-message :deep(pre:last-child),
.markdown-message :deep(table:last-child) { margin-bottom: 0; }
.markdown-message :deep(h1),
.markdown-message :deep(h2),
.markdown-message :deep(h3),
.markdown-message :deep(h4),
.markdown-message :deep(h5),
.markdown-message :deep(h6) {
  color: #172033;
  font-weight: 650;
  line-height: 1.4;
  margin: 18px 0 8px;
}
.markdown-message :deep(h1:first-child),
.markdown-message :deep(h2:first-child),
.markdown-message :deep(h3:first-child),
.markdown-message :deep(h4:first-child) { margin-top: 0; }
.markdown-message :deep(h1) { font-size: 21px; }
.markdown-message :deep(h2) { font-size: 19px; }
.markdown-message :deep(h3) { font-size: 17px; }
.markdown-message :deep(h4),
.markdown-message :deep(h5),
.markdown-message :deep(h6) { font-size: 16px; }
.markdown-message :deep(ul),
.markdown-message :deep(ol) {
  margin: 6px 0 14px;
  padding-left: 1.45em;
}
.markdown-message :deep(li) { padding-left: 0.22em; }
.markdown-message :deep(li + li) { margin-top: 7px; }
.markdown-message :deep(blockquote) {
  margin: 10px 0 14px;
  padding: 8px 12px;
  border-left: 3px solid #93b4ff;
  border-radius: 0 8px 8px 0;
  background: rgb(47 107 255 / 6%);
  color: #475569;
}
.markdown-message :deep(pre) {
  margin: 10px 0 14px;
  padding: 12px 14px;
  overflow-x: auto;
  border: 1px solid #dce3ec;
  border-radius: 10px;
  background: #111827;
  color: #e5e7eb;
  font: 13px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre;
}
.markdown-message :deep(code) {
  padding: 2px 5px;
  border-radius: 5px;
  background: rgb(15 23 42 / 7%);
  color: #b42318;
  font: 0.9em ui-monospace, SFMono-Regular, Consolas, monospace;
}
.markdown-message :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font: inherit;
}
.markdown-message :deep(table) {
  display: block;
  max-width: 100%;
  margin: 12px 0 16px;
  overflow-x: auto;
  border-collapse: collapse;
  border-spacing: 0;
  font-size: 14px;
}
.markdown-message :deep(th),
.markdown-message :deep(td) {
  min-width: 96px;
  padding: 9px 12px;
  border: 1px solid #dbe3ee;
  text-align: left;
  vertical-align: top;
}
.markdown-message :deep(th) {
  background: #edf3ff;
  color: #1e3a8a;
  font-weight: 650;
  white-space: nowrap;
}
.markdown-message :deep(tr:nth-child(even) td) { background: rgb(248 250 252 / 72%); }
.markdown-message :deep(img) {
  display: block;
  max-width: min(100%, 560px);
  height: auto;
  margin: 12px 0;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  object-fit: contain;
}
.markdown-message :deep(a) {
  color: #2459dc;
  text-decoration: underline;
  text-decoration-color: rgb(36 89 220 / 35%);
  text-underline-offset: 3px;
}
.markdown-message :deep(hr) { margin: 16px 0; border: 0; border-top: 1px solid #dbe3ee; }
</style>
