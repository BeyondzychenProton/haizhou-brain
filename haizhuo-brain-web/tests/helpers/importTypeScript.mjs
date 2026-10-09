import { readFile } from 'node:fs/promises'
import ts from 'typescript'

/** Load a pure TypeScript module for Node's built-in test runner without adding a runtime dependency. */
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
