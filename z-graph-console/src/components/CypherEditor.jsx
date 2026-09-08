import React, { useRef, useCallback } from 'react';

/**
 * Cypher 语法高亮编辑器 — textarea + <pre> overlay 方案。
 * textarea 透明处理输入,<pre> 渲染高亮文本。
 */

const KEYWORDS = new Set([
  'MATCH', 'RETURN', 'CREATE', 'DELETE', 'DETACH', 'SET', 'MERGE', 'WHERE',
  'AND', 'OR', 'NOT', 'IN', 'AS', 'ORDER', 'BY', 'ASC', 'DESC', 'LIMIT',
  'SKIP', 'WITH', 'UNWIND', 'OPTIONAL', 'CALL', 'YIELD', 'SHOW', 'TAGS',
  'EDGES', 'INDEXES', 'EXPLAIN', 'PROFILE', 'DISTINCT', 'COUNT', 'SUM',
  'AVG', 'MIN', 'MAX', 'COLLECT', 'LENGTH', 'SIZE', 'TO_INT', 'TO_FLOAT',
  'ToString', 'HAS', 'STARTS', 'ENDS', 'CONTAINS', 'IS', 'NULL', 'TRUE',
  'FALSE', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'FOREACH', 'REMOVE',
  'ADD', 'DROP', 'REBUILD', 'ALTER', 'IF', 'NOT', 'EXISTS'
]);

const BUILTINS = new Set([
  'db.version', 'db.branches', 'db.commits', 'db.head', 'db.stats',
  'db.tags', 'db.edges', 'db.indexes'
]);

export default function CypherEditor({ value, onChange, onKeyDown, rows = 10 }) {
  const preRef = useRef(null);
  const textareaRef = useRef(null);

  const handleScroll = useCallback(() => {
    if (preRef.current && textareaRef.current) {
      preRef.current.scrollTop = textareaRef.current.scrollTop;
      preRef.current.scrollLeft = textareaRef.current.scrollLeft;
    }
  }, []);

  const highlighted = highlightCypher(value || '');

  return (
    <div className="cypher-editor-wrap" style={{ position: 'relative' }}>
      <pre
        ref={preRef}
        className="cypher-editor-highlight"
        aria-hidden="true"
        style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          margin: 0, padding: '8px 10px',
          border: '1px solid #334155', borderRadius: 6,
          background: '#1e293b', color: '#e2e8f0',
          fontFamily: "'SFMono-Regular', Menlo, Consolas, monospace",
          fontSize: 13, lineHeight: 1.5,
          overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all',
          pointerEvents: 'none', zIndex: 1
        }}
        dangerouslySetInnerHTML={{ __html: highlighted }}
      />
      <textarea
        ref={textareaRef}
        value={value}
        onChange={e => onChange(e.target.value)}
        onScroll={handleScroll}
        onKeyDown={onKeyDown}
        spellCheck={false}
        rows={rows}
        placeholder="MATCH (n:Person) RETURN n LIMIT 10"
        style={{
          width: '100%', marginTop: 12,
          background: 'transparent', color: 'transparent', caretColor: '#e2e8f0',
          border: '1px solid #334155', borderRadius: 6,
          padding: '8px 10px',
          fontFamily: "'SFMono-Regular', Menlo, Consolas, monospace",
          fontSize: 13, lineHeight: 1.5,
          resize: 'vertical', position: 'relative', zIndex: 2
        }}
      />
    </div>
  );
}

/**
 * 将 Cypher 文本转为带 <span class="..."> 的高亮 HTML。
 */
function highlightCypher(text) {
  const result = [];
  let i = 0;
  while (i < text.length) {
    // 单行注释
    if (text[i] === '-' && text[i + 1] === '-') {
      const end = text.indexOf('\n', i);
      const comment = end === -1 ? text.substring(i) : text.substring(i, end);
      result.push(`<span class="hl-comment">${escapeHtml(comment)}</span>`);
      i = end === -1 ? text.length : end;
      continue;
    }
    // 字符串 (单引号)
    if (text[i] === "'") {
      let j = i + 1;
      while (j < text.length && text[j] !== "'") {
        if (text[j] === '\\') j++;
        j++;
      }
      j = Math.min(j + 1, text.length);
      result.push(`<span class="hl-string">${escapeHtml(text.substring(i, j))}</span>`);
      i = j;
      continue;
    }
    // 字符串 (双引号)
    if (text[i] === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"') {
        if (text[j] === '\\') j++;
        j++;
      }
      j = Math.min(j + 1, text.length);
      result.push(`<span class="hl-string">${escapeHtml(text.substring(i, j))}</span>`);
      i = j;
      continue;
    }
    // 数字
    if (/[0-9]/.test(text[i]) && (i === 0 || /[\s,({\-:[=<>!]/.test(text[i - 1]))) {
      let j = i;
      while (j < text.length && /[0-9.]/.test(text[j])) j++;
      result.push(`<span class="hl-number">${escapeHtml(text.substring(i, j))}</span>`);
      i = j;
      continue;
    }
    // 标识符/关键字
    if (/[a-zA-Z_]/.test(text[i])) {
      let j = i;
      while (j < text.length && /[a-zA-Z0-9_]/.test(text[j])) j++;
      const word = text.substring(i, j);
      const upper = word.toUpperCase();
      if (KEYWORDS.has(upper)) {
        result.push(`<span class="hl-keyword">${escapeHtml(word)}</span>`);
      } else if (BUILTINS.has(upper) || BUILTINS.has(word.toLowerCase())) {
        result.push(`<span class="hl-builtin">${escapeHtml(word)}</span>`);
      } else if (j < text.length && text[j] === '(') {
        result.push(`<span class="hl-function">${escapeHtml(word)}</span>`);
      } else if (upper === 'ASC' || upper === 'DESC') {
        result.push(`<span class="hl-keyword">${escapeHtml(word)}</span>`);
      } else {
        result.push(escapeHtml(word));
      }
      i = j;
      continue;
    }
    // 操作符
    if ('-><-[]{}(),:.;=<>!+'.includes(text[i])) {
      result.push(`<span class="hl-op">${escapeHtml(text[i])}</span>`);
      i++;
      continue;
    }
    // 其他
    result.push(escapeHtml(text[i]));
    i++;
  }
  return result.join('');
}

function escapeHtml(ch) {
  if (ch === '&') return '&amp;';
  if (ch === '<') return '&lt;';
  if (ch === '>') return '&gt;';
  if (ch === '"') return '&quot;';
  return ch;
}
