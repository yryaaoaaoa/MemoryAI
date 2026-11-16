import test from 'node:test'
import assert from 'node:assert/strict'
import { exampleNote, flattenNodes, locateNode, moveNode, parseTreeNote, toMarkdown } from '../src/prototypes/tree-notes/tree-note'
import { readTree, treeFields } from '../src/utils/knowledge-tree'

test('persistent tree round-trip retains IDs and exact Markdown bodies, including code', () => {
  const nodes = exampleNote().nodes
  const stored = treeFields(nodes)
  assert.deepEqual(readTree(stored.treeJson).nodes, nodes)
  assert.ok(stored.content.startsWith('## 先理解任务的执行顺序\n\n'))
  assert.ok(stored.content.includes('new ThreadPoolExecutor('))
  assert.ok(stored.content.endsWith('\n'))
})

test('persistent tree boundary rejects invalid versions, duplicate IDs and excessive depth', () => {
  const node = { id: 'a', title: '知识点', body: '', children: [] as any[] }
  assert.throws(() => readTree(JSON.stringify({ version: 2, nodes: [node] })))
  assert.throws(() => readTree(JSON.stringify({ version: 1, nodes: [node, node] })))
  const root = structuredClone(node)
  let current = root
  for (let i = 1; i < 33; i++) { const child = { ...node, id: String(i), children: [] }; current.children.push(child); current = child }
  assert.throws(() => readTree(JSON.stringify({ version: 1, nodes: [root] })))
})

test('moving a branch preserves every node, body and stable ID', () => {
  const note = exampleNote()
  const before = flattenNodes(note.nodes).map(({ node }) => [node.id, node.body]).sort()
  assert.equal(moveNode(note.nodes, 'queue', 'indent'), true)
  assert.equal(locateNode(note.nodes, 'queue')?.parent?.id, 'core')
  assert.equal(locateNode(note.nodes, 'unbounded')?.parent?.id, 'queue')
  assert.equal(moveNode(note.nodes, 'queue', 'outdent'), true)
  assert.equal(locateNode(note.nodes, 'queue')?.parent?.id, 'flow')
  assert.deepEqual(flattenNodes(note.nodes).map(({ node }) => [node.id, node.body]).sort(), before)
})

test('invalid moves leave the original tree unchanged', () => {
  const note = exampleNote()
  const before = JSON.stringify(note)
  assert.equal(moveNode(note.nodes, 'flow', 'indent'), false)
  assert.equal(moveNode(note.nodes, 'flow', 'up'), false)
  assert.equal(moveNode(note.nodes, 'flow', 'outdent'), false)
  assert.equal(moveNode(note.nodes, 'review', 'down'), false)
  assert.equal(JSON.stringify(note), before)
})

test('stored notes reject duplicate IDs and malformed descendants', () => {
  const note = exampleNote()
  assert.deepEqual(parseTreeNote(JSON.stringify(note)), note)
  note.nodes[0]!.children[0]!.id = note.nodes[0]!.id
  assert.throws(() => parseTreeNote(JSON.stringify(note)))
  assert.throws(() => parseTreeNote('{"version":1,"title":"note","status":"REVIEW","nodes":[{"id":"a","title":"A","body":"","children":[null]}]}'))
})

test('Markdown export includes full explanations, nested content, code and tables', () => {
  const note = exampleNote()
  const result = toMarkdown(note)
  assert.match(result, /^# 线程池/)
  assert.match(result, /#### 为什么无界队列/)
  assert.match(result, /```java\nnew ThreadPoolExecutor/)
  assert.match(result, /\| 第 7 个 \| 触发拒绝策略 \|/)
  for (const { node } of flattenNodes(note.nodes)) assert.ok(result.includes(node.title))
})
