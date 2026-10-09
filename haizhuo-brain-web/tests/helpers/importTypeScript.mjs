import { readFile } from 'node:fs/promises'
import ts from 'typescript'

/** 通过 Node 内置测试运行器加载纯 TypeScript 模块，不增加运行时依赖。 */
export async function importTypeScript(sourceUrl) {
  const source = await readFile(sourceUrl, 'utf8')
  const compiled = ts.transpileModule(source, {
    fileName: sourceUrl.pathname,
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText

  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
}
