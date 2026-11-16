export interface KnowledgeNode {
  id: string
  title: string
  body: string
  children: KnowledgeNode[]
}

export interface KnowledgeTree { version: 1; nodes: KnowledgeNode[] }

export function readTree(text: string): KnowledgeTree {
  const value = JSON.parse(text)
  const ids = new Set<string>()
  const valid = (nodes: unknown, depth: number): boolean => Array.isArray(nodes) && depth <= 32 && nodes.every(n => {
    if (!n || typeof n.id !== 'string' || !n.id.trim() || n.id.length > 128 || ids.has(n.id) ||
      typeof n.title !== 'string' || !n.title.trim() || n.title.length > 120 || /[\r\n]/.test(n.title) ||
      typeof n.body !== 'string' || n.body.length > 40000 || !Array.isArray(n.children)) return false
    ids.add(n.id)
    return ids.size <= 1000 && (!n.children.length || valid(n.children, depth + 1))
  })
  if (value?.version !== 1 || !valid(value.nodes, 1)) throw new Error('知识树格式无效')
  return value as KnowledgeTree
}

export function treeContent(nodes: KnowledgeNode[], depth = 2): string {
  return nodes.map(node => `${depth <= 6 ? '#'.repeat(depth) + ' ' + node.title : '**' + node.title + '**'}\n\n${node.body ? node.body + '\n\n' : ''}${treeContent(node.children, depth + 1)}`).join('')
}

export function treeFields(nodes: KnowledgeNode[]) {
  return { treeJson: JSON.stringify({ version: 1, nodes }), content: treeContent(nodes).trimEnd() + (nodes.length ? '\n' : '') }
}

export function newKnowledgeNode(title: string, body = ''): KnowledgeNode {
  return { id: crypto.randomUUID(), title, body, children: [] }
}

export interface TreeNote {
  version: 1
  title: string
  status: 'PENDING' | 'REVIEW' | 'UNDERSTOOD'
  nodes: KnowledgeNode[]
}

export interface NodeLocation {
  node: KnowledgeNode
  siblings: KnowledgeNode[]
  index: number
  parent: KnowledgeNode | null
}

export function locateNode(nodes: KnowledgeNode[], id: string, parent: KnowledgeNode | null = null): NodeLocation | undefined {
  for (const [index, node] of nodes.entries()) {
    if (node.id === id) return { node, siblings: nodes, index, parent }
    const found = locateNode(node.children, id, node)
    if (found) return found
  }
}

export function flattenNodes(nodes: KnowledgeNode[], ancestors: string[] = []): { node: KnowledgeNode; ancestors: string[]; index: number; size: number }[] {
  return nodes.flatMap((node, index) => [
    { node, ancestors, index, size: nodes.length },
    ...flattenNodes(node.children, [...ancestors, node.id]),
  ])
}

export function moveNode(nodes: KnowledgeNode[], id: string, action: 'up' | 'down' | 'indent' | 'outdent'): boolean {
  const found = locateNode(nodes, id)
  if (!found) return false
  const { node, siblings, index, parent } = found
  if (action === 'up' || action === 'down') {
    const next = index + (action === 'up' ? -1 : 1)
    if (next < 0 || next >= siblings.length) return false
    siblings.splice(index, 1)
    siblings.splice(next, 0, node)
  } else if (action === 'indent') {
    const previous = siblings[index - 1]
    if (!previous) return false
    siblings.splice(index, 1)
    previous.children.push(node)
  } else {
    if (!parent) return false
    const parentLocation = locateNode(nodes, parent.id)!
    siblings.splice(index, 1)
    parentLocation.siblings.splice(parentLocation.index + 1, 0, node)
  }
  return true
}

// Validate browser storage once at the boundary; internal editing uses typed nodes.
export function parseTreeNote(text: string): TreeNote {
  const value: unknown = JSON.parse(text)
  const ids = new Set<string>()
  const isNode = (item: unknown): item is KnowledgeNode => {
    if (!item || typeof item !== 'object') return false
    const n = item as Record<string, unknown>
    if (typeof n.id !== 'string' || !n.id || ids.has(n.id) || typeof n.title !== 'string' || typeof n.body !== 'string' || !Array.isArray(n.children)) return false
    ids.add(n.id)
    return n.children.every(isNode)
  }
  if (!value || typeof value !== 'object') throw new Error('Invalid note')
  const n = value as Record<string, unknown>
  if (n.version !== 1 || typeof n.title !== 'string' || !['PENDING', 'REVIEW', 'UNDERSTOOD'].includes(String(n.status)) || !Array.isArray(n.nodes) || !n.nodes.every(isNode)) throw new Error('Invalid note')
  return value as TreeNote
}

export function toMarkdown(note: TreeNote): string {
  const render = (nodes: KnowledgeNode[], depth: number): string => nodes.map(node => {
    const title = depth <= 6 ? `${'#'.repeat(depth)} ${node.title}` : `**${node.title}**`
    return `${title}\n\n${node.body ? node.body + '\n\n' : ''}${render(node.children, depth + 1)}`
  }).join('')
  return `# ${note.title}\n\n${render(note.nodes, 2)}`.trimEnd() + '\n'
}
