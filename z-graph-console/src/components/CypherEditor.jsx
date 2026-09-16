import React, { useRef, useCallback, useState, useEffect } from 'react';

/**
 * Cypher 语法高亮编辑器 — textarea + <pre> overlay + 自动补全。
 * textarea 透明处理输入,<pre> 渲染高亮文本,下拉菜单提供补全建议。
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

// 自动补全建议列表
const SUGGESTIONS = [
  // 关键字
  { text: 'MATCH', desc: '模式匹配', category: 'keyword' },
  { text: 'RETURN', desc: '返回结果', category: 'keyword' },
  { text: 'CREATE', desc: '创建节点/边', category: 'keyword' },
  { text: 'DELETE', desc: '删除节点/边', category: 'keyword' },
  { text: 'DETACH DELETE', desc: '删除节点及关联边', category: 'keyword' },
  { text: 'MERGE', desc: '查找或创建', category: 'keyword' },
  { text: 'WHERE', desc: '过滤条件', category: 'keyword' },
  { text: 'SET', desc: '设置属性', category: 'keyword' },
  { text: 'ORDER BY', desc: '排序', category: 'keyword' },
  { text: 'LIMIT', desc: '限制数量', category: 'keyword' },
  { text: 'SKIP', desc: '跳过记录', category: 'keyword' },
  { text: 'WITH', desc: '传递中间结果', category: 'keyword' },
  { text: 'OPTIONAL MATCH', desc: '可选匹配', category: 'keyword' },
  { text: 'UNWIND', desc: '展开列表', category: 'keyword' },
  { text: 'DISTINCT', desc: '去重', category: 'keyword' },
  { text: 'AS', desc: '别名', category: 'keyword' },
  { text: 'AND', desc: '逻辑与', category: 'keyword' },
  { text: 'OR', desc: '逻辑或', category: 'keyword' },
  { text: 'NOT', desc: '逻辑非', category: 'keyword' },
  { text: 'IS NULL', desc: '判空', category: 'keyword' },
  { text: 'IS NOT NULL', desc: '非空判断', category: 'keyword' },
  { text: 'EXISTS', desc: '存在性检查', category: 'keyword' },
  { text: 'CONTAINS', desc: '包含匹配', category: 'keyword' },
  { text: 'STARTS WITH', desc: '前缀匹配', category: 'keyword' },
  { text: 'ENDS WITH', desc: '后缀匹配', category: 'keyword' },
  // 内置过程
  { text: 'CALL db.version()', desc: '数据库版本', category: 'builtin' },
  { text: 'CALL db.branches()', desc: '分支列表', category: 'builtin' },
  { text: 'CALL db.commits()', desc: '提交历史', category: 'builtin' },
  { text: 'CALL db.stats()', desc: '统计信息', category: 'builtin' },
  { text: 'CALL db.tags()', desc: '标签列表', category: 'builtin' },
  { text: 'CALL db.edges()', desc: '边类型列表', category: 'builtin' },
  { text: 'CALL db.indexes()', desc: '索引列表', category: 'builtin' },
  { text: "CALL db.head('main')", desc: '当前 head', category: 'builtin' },
  { text: 'SHOW TAGS', desc: '显示所有标签', category: 'builtin' },
  { text: 'SHOW EDGES', desc: '显示所有边类型', category: 'builtin' },
  { text: 'SHOW INDEXES', desc: '显示所有索引', category: 'builtin' },
  // 聚合函数
  { text: 'count(*)', desc: '计数', category: 'function' },
  { text: 'count(n)', desc: '节点计数', category: 'function' },
  { text: 'sum(n.age)', desc: '求和', category: 'function' },
  { text: 'avg(n.age)', desc: '平均值', category: 'function' },
  { text: 'min(n.age)', desc: '最小值', category: 'function' },
  { text: 'max(n.age)', desc: '最大值', category: 'function' },
  { text: 'collect(n.name)', desc: '收集为列表', category: 'function' },
  // 常用模式
  { text: 'MATCH (n) RETURN n LIMIT 10', desc: '查询所有节点', category: 'pattern' },
  { text: "MATCH (n:Person) RETURN n", desc: '按标签查询', category: 'pattern' },
  { text: "MATCH (n:Person {name: 'Alice'}) RETURN n", desc: '按属性查询', category: 'pattern' },
  { text: "MATCH (a)-[r]->(b) RETURN a, r, b LIMIT 10", desc: '查询关系', category: 'pattern' },
  { text: "CREATE (n:Person {name: 'Alice', age: 30})", desc: '创建节点', category: 'pattern' },
  { text: "CREATE (a)-[:KNOWS]->(b)", desc: '创建关系', category: 'pattern' },
  { text: "MERGE (n:Person {name: 'Alice'})", desc: ' Upsert 节点', category: 'pattern' },
  // DDL
  { text: 'CREATE TAG Person (name STRING, age INT)', desc: '创建标签', category: 'ddl' },
  { text: 'CREATE EDGE KNOWS ()', desc: '创建边类型', category: 'ddl' },
  { text: 'CREATE TAG INDEX idx_name ON Person(name)', desc: '创建索引', category: 'ddl' },
];

const CATEGORY_COLORS = {
  keyword: '#c084fc',
  builtin: '#67e8f9',
  function: '#93c5fd',
  pattern: '#86efac',
  ddl: '#fbbf24'
};

export default function CypherEditor({ value, onChange, onKeyDown, rows = 10 }) {
  const preRef = useRef(null);
  const textareaRef = useRef(null);
  const [suggestions, setSuggestions] = useState([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [cursorPos, setCursorPos] = useState({ top: 0, left: 0 });

  const handleScroll = useCallback(() => {
    if (preRef.current && textareaRef.current) {
      preRef.current.scrollTop = textareaRef.current.scrollTop;
      preRef.current.scrollLeft = textareaRef.current.scrollLeft;
    }
  }, []);

  // 获取当前光标位置的单词
  function getCurrentWord(text, pos) {
    const before = text.substring(0, pos);
    const match = before.match(/[a-zA-Z_*.]+$/);
    return match ? match[0] : '';
  }

  // 计算光标位置（用于下拉定位）
  function calculateCursorPos(textarea, pos) {
    const text = textarea.value.substring(0, pos);
    const lines = text.split('\n');
    const currentLine = lines.length - 1;
    const currentCol = lines[lines.length - 1].length;
    const lineHeight = 22; // 13px * 1.5 line-height
    const charWidth = 7.8; // approx monospace char width at 13px
    return {
      top: (currentLine + 1) * lineHeight - textarea.scrollTop + 4,
      left: Math.min(currentCol * charWidth + 10, textarea.clientWidth - 200)
    };
  }

  const handleInput = useCallback((e) => {
    const newValue = e.target.value;
    onChange(newValue);
    const pos = e.target.selectionStart;
    const word = getCurrentWord(newValue, pos);

    if (word.length >= 1) {
      const filtered = SUGGESTIONS.filter(s =>
        s.text.toLowerCase().startsWith(word.toLowerCase()) ||
        s.desc.includes(word)
      ).slice(0, 12);

      if (filtered.length > 0) {
        setSuggestions(filtered);
        setSelectedIndex(0);
        setShowSuggestions(true);
        setCursorPos(calculateCursorPos(e.target, pos));
      } else {
        setShowSuggestions(false);
      }
    } else {
      setShowSuggestions(false);
    }
  }, [onChange]);

  const handleKeyDown = useCallback((e) => {
    if (showSuggestions && suggestions.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(i => (i + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(i => (i - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Tab' || e.key === 'Enter') {
        e.preventDefault();
        applySuggestion(suggestions[selectedIndex]);
        return;
      }
      if (e.key === 'Escape') {
        setShowSuggestions(false);
        return;
      }
    }
    if (onKeyDown) onKeyDown(e);
  }, [showSuggestions, suggestions, selectedIndex, onKeyDown]);

  function applySuggestion(suggestion) {
    const ta = textareaRef.current;
    if (!ta) return;
    const pos = ta.selectionStart;
    const text = ta.value;
    const word = getCurrentWord(text, pos);
    const before = text.substring(0, pos - word.length);
    const after = text.substring(pos);
    const newValue = before + suggestion.text + after;
    onChange(newValue);
    setShowSuggestions(false);
    // 设置光标位置到补全文本之后
    setTimeout(() => {
      const newPos = pos - word.length + suggestion.text.length;
      ta.setSelectionRange(newPos, newPos);
      ta.focus();
    }, 0);
  }

  // 点击外部关闭建议
  useEffect(() => {
    if (!showSuggestions) return;
    function handleClick(e) {
      if (textareaRef.current && !textareaRef.current.contains(e.target)) {
        setShowSuggestions(false);
      }
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showSuggestions]);

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
        onChange={handleInput}
        onScroll={handleScroll}
        onKeyDown={handleKeyDown}
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

      {/* 自动补全下拉菜单 */}
      {showSuggestions && suggestions.length > 0 && (
        <div
          className="cypher-autocomplete"
          style={{
            position: 'absolute',
            top: cursorPos.top,
            left: cursorPos.left,
            zIndex: 100,
            background: '#1e293b',
            border: '1px solid #334155',
            borderRadius: 6,
            boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
            maxHeight: 240,
            overflowY: 'auto',
            minWidth: 260,
          }}
        >
          {suggestions.map((s, i) => (
            <div
              key={i}
              className={`autocomplete-item ${i === selectedIndex ? 'selected' : ''}`}
              style={{
                padding: '6px 10px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                background: i === selectedIndex ? '#334155' : 'transparent',
                borderBottom: i < suggestions.length - 1 ? '1px solid #1e293b' : 'none',
              }}
              onMouseEnter={() => setSelectedIndex(i)}
              onMouseDown={e => { e.preventDefault(); applySuggestion(s); }}
            >
              <span style={{
                fontSize: 10, fontWeight: 600, padding: '1px 4px',
                borderRadius: 3, background: CATEGORY_COLORS[s.category] + '20',
                color: CATEGORY_COLORS[s.category], minWidth: 48, textAlign: 'center'
              }}>
                {s.category}
              </span>
              <span className="mono" style={{ fontSize: 12, color: '#e2e8f0', flex: 1 }}>
                {s.text}
              </span>
              <span style={{ fontSize: 11, color: '#64748b', whiteSpace: 'nowrap' }}>
                {s.desc}
              </span>
            </div>
          ))}
          <div style={{ padding: '4px 10px', fontSize: 10, color: '#475569', borderTop: '1px solid #334155' }}>
            Tab/Enter 选择 · ↑↓ 导航 · Esc 关闭
          </div>
        </div>
      )}
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
