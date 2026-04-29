type Child = Node | string | null | undefined;

interface ElementOptions {
  className?: string;
  text?: string;
  attributes?: Record<string, string>;
}

export function element<K extends keyof HTMLElementTagNameMap>(
  tagName: K,
  options: ElementOptions = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tagName);

  if (options.className) {
    node.className = options.className;
  }

  if (options.text !== undefined) {
    node.textContent = options.text;
  }

  if (options.attributes) {
    for (const [key, value] of Object.entries(options.attributes)) {
      node.setAttribute(key, value);
    }
  }

  append(node, ...children);
  return node;
}

export function append(parent: Node, ...children: Child[]): void {
  for (const child of children) {
    if (child === null || child === undefined) {
      continue;
    }

    if (typeof child === 'string') {
      parent.appendChild(document.createTextNode(child));
      continue;
    }

    parent.appendChild(child);
  }
}

export function clear(node: Element): void {
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}
