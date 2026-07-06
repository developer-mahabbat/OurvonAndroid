const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');

const PORT = 4097;
const ZEN_URL = 'opencode.ai';
const ZEN_MODEL = 'deepseek-v4-flash-free';

// --- SSE helpers ---
function sse(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

// --- Zen API call ---
function callZen(messages, onChunk, onDone, onError) {
  const body = JSON.stringify({
    model: ZEN_MODEL,
    messages: messages.map(m => ({
      role: m.role,
      content: m.content || ''
    })),
    stream: true,
    max_tokens: 8192,
    temperature: 0.7,
    tools: [{
      type: 'function',
      function: {
        name: 'read_file',
        description: 'Read a file from the filesystem',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string', description: 'File path' }
          },
          required: ['path']
        }
      }
    }, {
      type: 'function',
      function: {
        name: 'write_file',
        description: 'Write content to a file',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string' },
            content: { type: 'string' }
          },
          required: ['path', 'content']
        }
      }
    }, {
      type: 'function',
      function: {
        name: 'list_files',
        description: 'List files in a directory',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string', description: 'Directory path' }
          },
          required: ['path']
        }
      }
    }, {
      type: 'function',
      function: {
        name: 'bash',
        description: 'Execute a bash command',
        parameters: {
          type: 'object',
          properties: {
            command: { type: 'string', description: 'Command to execute' }
          },
          required: ['command']
        }
      }
    }, {
      type: 'function',
      function: {
        name: 'web_search',
        description: 'Search the web for information',
        parameters: {
          type: 'object',
          properties: {
            query: { type: 'string' }
          },
          required: ['query']
        }
      }
    }]
  });

  const req = https.request({
    hostname: ZEN_URL,
    path: '/zen/v1/chat/completions',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body)
    },
    timeout: 120000
  }, (res) => {
    let buffer = '';
    res.on('data', (chunk) => {
      buffer += chunk.toString();
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || !trimmed.startsWith('data: ')) continue;
        const data = trimmed.slice(6).trim();
        if (data === '[DONE]') { onDone(); return; }
        try {
          const parsed = JSON.parse(data);
          onChunk(parsed);
        } catch (e) {}
      }
    });
    res.on('end', () => {
      if (buffer.trim()) {
        const trimmed = buffer.trim();
        if (trimmed.startsWith('data: ')) {
          const data = trimmed.slice(6).trim();
          if (data === '[DONE]') { onDone(); return; }
          try {
            const parsed = JSON.parse(data);
            onChunk(parsed);
          } catch (e) {}
        }
      }
      onDone();
    });
    res.on('error', onError);
  });
  req.on('error', onError);
  req.write(body);
  req.end();
}

// --- Tool execution ---
function executeTool(name, args) {
  switch (name) {
    case 'read_file': {
      const p = path.resolve(args.path || '');
      if (!fs.existsSync(p)) return { error: `File not found: ${p}` };
      return { content: fs.readFileSync(p, 'utf-8') };
    }
    case 'write_file': {
      const p = path.resolve(args.path || '');
      const dir = path.dirname(p);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(p, args.content || '', 'utf-8');
      return { content: `Written to ${p}` };
    }
    case 'list_files': {
      const p = path.resolve(args.path || '.');
      if (!fs.existsSync(p)) return { error: `Not found: ${p}` };
      const items = fs.readdirSync(p, { withFileTypes: true });
      return { content: items.map(d => `${d.isDirectory() ? 'd' : '-'} ${d.name}`).join('\n') };
    }
    case 'bash': {
      try {
        const out = execSync(args.command || '', { timeout: 30000, encoding: 'utf-8' });
        return { content: out || '(no output)' };
      } catch (e) {
        return { content: e.stdout || '', error: e.stderr || e.message };
      }
    }
    default:
      return { error: `Unknown tool: ${name}` };
  }
}

// --- Main agent loop ---
async function agentLoop(messages, sseRes) {
  let currentMessages = [...messages];
  let turnCount = 0;
  const maxTurns = 10;

  while (turnCount < maxTurns) {
    turnCount++;
    let text = '';
    let toolCalls = [];

    sse(sseRes, 'session.next.prompt.admitted', {});

    await new Promise((resolve, reject) => {
      callZen(currentMessages,
        (parsed) => {
          const choice = parsed.choices && parsed.choices[0];
          if (!choice) return;
          const delta = choice.delta || {};
          if (delta.content) {
            text += delta.content;
            sse(sseRes, 'session.next.text.delta', { text: delta.content });
          }
          if (delta.tool_calls) {
            for (const tc of delta.tool_calls) {
              const idx = tc.index;
              if (!toolCalls[idx]) toolCalls[idx] = { id: tc.id || `call_${idx}`, name: '', args: '' };
              if (tc.function) {
                if (tc.function.name) toolCalls[idx].name += tc.function.name;
                if (tc.function.arguments) toolCalls[idx].args += tc.function.arguments;
              }
            }
          }
        },
        () => resolve(),
        (err) => reject(err)
      );
    });

    if (text) {
      sse(sseRes, 'session.next.text.ended', { text });
      currentMessages.push({ role: 'assistant', content: text });
    }

    if (toolCalls.length === 0) {
      sse(sseRes, 'session.idle', {});
      break;
    }

    for (const tc of toolCalls) {
      if (!tc || !tc.name) continue;
      sse(sseRes, 'session.next.tool.called', { name: tc.name, args: tc.args });
      let parsedArgs = {};
      try { parsedArgs = JSON.parse(tc.args); } catch (e) {}
      const result = executeTool(tc.name, parsedArgs);
      currentMessages.push({ role: 'assistant', content: null, tool_calls: [{ id: tc.id, type: 'function', function: { name: tc.name, arguments: tc.args } }] });
      currentMessages.push({ role: 'tool', tool_call_id: tc.id, content: JSON.stringify(result) });
      sse(sseRes, 'session.next.tool.result', { name: tc.name, result });
      sse(sseRes, 'session.next.step.ended', { name: tc.name });
    }
  }

  sse(sseRes, 'session.idle', {});
}

// ======================
// HTTP Server
// ======================
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const method = req.method;

  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // Parse body
  const parseBody = () => new Promise((resolve, reject) => {
    if (method === 'GET') return resolve({});
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => { try { resolve(JSON.parse(body || '{}')); } catch(e) { resolve({}); } });
  });

  (async () => {
    const body = await parseBody();
    const pathname = url.pathname;

    if (pathname === '/api/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', server: 'nodejs', model: ZEN_MODEL }));
      return;
    }

    if (pathname === '/api/chat') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no'
      });
      const messages = body.messages || [];
      agentLoop(messages, res).catch(err => {
        sse(res, 'error', { message: err.message });
        res.end();
      });
      req.on('close', () => {});
      return;
    }

    if (pathname === '/api/fs/read') {
      try {
        const p = path.resolve(body.path || '');
        if (!fs.existsSync(p)) throw new Error('Not found');
        const content = fs.readFileSync(p, 'utf-8');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ content }));
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
      return;
    }

    if (pathname === '/api/fs/write') {
      try {
        const p = path.resolve(body.path || '');
        const dir = path.dirname(p);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(p, body.content || '', 'utf-8');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
      return;
    }

    if (pathname === '/api/fs/list') {
      try {
        const p = path.resolve(body.path || '.');
        if (!fs.existsSync(p)) throw new Error('Not found');
        const items = fs.readdirSync(p, { withFileTypes: true });
        const files = items.map(d => ({
          name: d.name,
          isDirectory: d.isDirectory(),
          size: d.isDirectory() ? 0 : (fs.statSync(path.join(p, d.name)).size || 0)
        }));
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ files, path: p }));
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
      return;
    }

    if (pathname === '/api/command') {
      try {
        const out = execSync(body.command || '', { timeout: 30000, encoding: 'utf-8' });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ stdout: out, stderr: '' }));
      } catch (e) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ stdout: e.stdout || '', stderr: e.stderr || e.message }));
      }
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  })();
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Ourvon Node.js server listening on port ${PORT}`);
});
