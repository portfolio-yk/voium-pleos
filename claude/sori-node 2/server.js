import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(ROOT, 'public');
const TOKEN_ENDPOINT = 'https://mp.speechmatics.com/v1/api-keys?type=rt';
const LEGACY_TOKEN_ENDPOINT = 'https://mp.speechmatics.com/v1/api_keys?type=rt';
const STATIC_FILES = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/speechmatics.js', ['speechmatics.js', 'text/javascript; charset=utf-8']],
  ['/transcript.js', ['transcript.js', 'text/javascript; charset=utf-8']],
  ['/conversation.js', ['conversation.js', 'text/javascript; charset=utf-8']],
  ['/speaker-theme.js', ['speaker-theme.js', 'text/javascript; charset=utf-8']],
  ['/audio-processor.js', ['audio-processor.js', 'text/javascript; charset=utf-8']],
  ['/favicon.svg', ['favicon.svg', 'image/svg+xml']],
]);
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'microphone=(self), camera=(), geolocation=()',
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' wss://*.speechmatics.com; img-src 'self' data:; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};

function send(response, status, body, contentType, extraHeaders = {}, head = false) {
  const bytes = Buffer.from(body);
  response.writeHead(status, {
    ...SECURITY_HEADERS,
    'Content-Type': contentType,
    'Content-Length': bytes.length,
    'Cache-Control': 'no-store',
    ...extraHeaders,
  });
  response.end(head ? undefined : bytes);
}

function json(response, status, body, extraHeaders = {}) {
  send(response, status, JSON.stringify(body), 'application/json; charset=utf-8', extraHeaders);
}

// A local-only listener still needs Host validation: a malicious domain can
// resolve to 127.0.0.1 and otherwise reach the user's configured API key.
function getLocalOrigin(request) {
  const host = request.headers.host;
  if (typeof host !== 'string') return null;
  const match = /^(localhost|127\.0\.0\.1|\[::1\])(?::([0-9]{1,5}))?$/i.exec(host);
  if (!match) return null;
  const port = match[2] === undefined ? 80 : Number(match[2]);
  if (port !== request.socket.localPort) return null;
  return new URL(`http://${host.toLowerCase()}`).origin;
}

function sameOrigin(request, localOrigin) {
  if (request.headers['sec-fetch-site'] === 'cross-site') return false;
  const origin = request.headers.origin;
  // Command-line clients may omit Origin. Browser requests with JSON need a
  // preflight across origins; this server deliberately provides no CORS grant.
  if (origin === undefined) return true;
  if (typeof origin !== 'string') return false;
  try {
    return new URL(origin).origin === localOrigin && origin === new URL(origin).origin;
  } catch {
    return false;
  }
}

function consumeJson(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > 1024) {
        const error = new Error('Request body too large');
        error.statusCode = 413;
        reject(error);
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      if (size > 1024) return;
      const body = Buffer.concat(chunks).toString('utf8');
      if (body.trim()) {
        try {
          JSON.parse(body);
        } catch {
          const error = new Error('Invalid JSON');
          error.statusCode = 400;
          reject(error);
          return;
        }
      }
      resolve();
    });
    request.on('error', reject);
    request.on('aborted', () => reject(new Error('Request aborted')));
  });
}

async function mintToken(response, apiKey, fetchImpl) {
  if (!apiKey) {
    json(response, 503, { error: '서버의 .env 파일에 Speechmatics API 키를 설정한 뒤 서버를 다시 시작해 주세요.' });
    return;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);
  try {
    const options = {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ ttl: 60 }),
      cache: 'no-store',
      signal: controller.signal,
    };
    let upstream = await fetchImpl(TOKEN_ENDPOINT, options);
    // Older Speechmatics deployments use the underscore route. Never retry
    // failed credentials or rate limits, only an absent endpoint.
    if (upstream.status === 404) {
      await upstream.body?.cancel();
      upstream = await fetchImpl(LEGACY_TOKEN_ENDPOINT, options);
    }
    if (!upstream.ok) {
      await upstream.body?.cancel();
      if (upstream.status === 401 || upstream.status === 403) {
        json(response, 502, { error: 'Speechmatics 인증에 실패했어요. .env의 API 키와 실시간 인식 이용 권한을 확인해 주세요.' });
      } else if (upstream.status === 429) {
        json(response, 503, { error: 'Speechmatics 연결 요청이 많아요. 잠시 후 다시 시도해 주세요.' });
      } else {
        json(response, 502, { error: 'Speechmatics에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.' });
      }
      return;
    }
    const data = await upstream.json();
    if (typeof data?.key_value !== 'string' || !data.key_value.trim()) {
      json(response, 502, { error: 'Speechmatics 연결 토큰을 받지 못했어요. 잠시 후 다시 시도해 주세요.' });
      return;
    }
    json(response, 200, { token: data.key_value });
  } catch {
    // Do not pass upstream messages through: they can contain credentials or
    // private account details.
    json(response, 502, { error: '연결 요청에 응답이 없어요. 네트워크 상태를 확인하고 다시 시도해 주세요.' });
  } finally {
    clearTimeout(timeout);
  }
}

export function createServer({ apiKey = process.env.SPEECHMATICS_API_KEY ?? '', fetchImpl = fetch } = {}) {
  const key = typeof apiKey === 'string' ? apiKey.trim() : '';
  return http.createServer(async (request, response) => {
    try {
      const localOrigin = getLocalOrigin(request);
      if (!localOrigin) {
        json(response, 403, { error: '로컬 주소로 접속해 주세요.' });
        return;
      }
      let pathname;
      try {
        pathname = decodeURIComponent(new URL(request.url, localOrigin).pathname);
      } catch {
        json(response, 400, { error: '올바르지 않은 요청 주소예요.' });
        return;
      }

      if (pathname === '/api/token') {
        if (!sameOrigin(request, localOrigin)) {
          json(response, 403, { error: '이 웹사이트에서만 연결을 시작할 수 있어요.' });
          return;
        }
        if (request.method === 'GET') {
          json(response, 200, { configured: Boolean(key) });
        } else if (request.method === 'POST') {
          if (request.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
            json(response, 415, { error: 'JSON 형식으로 연결을 요청해 주세요.' });
            return;
          }
          try {
            await consumeJson(request);
          } catch (error) {
            json(response, error.statusCode ?? 400, { error: '올바르지 않은 연결 요청이에요.' });
            return;
          }
          await mintToken(response, key, fetchImpl);
        } else {
          json(response, 405, { error: '지원하지 않는 요청 방식이에요.' }, { Allow: 'GET, POST' });
        }
        return;
      }

      if (request.method !== 'GET' && request.method !== 'HEAD') {
        send(response, 405, 'Method not allowed', 'text/plain; charset=utf-8', { Allow: 'GET, HEAD' });
        return;
      }
      // Serve only known public assets. Neither server code, .env files nor
      // future files accidentally placed beside the app can become public.
      const asset = STATIC_FILES.get(pathname);
      if (!asset) {
        send(response, 404, 'Not found', 'text/plain; charset=utf-8', {}, request.method === 'HEAD');
        return;
      }
      try {
        const content = await readFile(path.join(PUBLIC, asset[0]));
        send(response, 200, content, asset[1], {}, request.method === 'HEAD');
      } catch (error) {
        send(response, error.code === 'ENOENT' ? 404 : 500, 'File unavailable', 'text/plain; charset=utf-8', {}, request.method === 'HEAD');
      }
    } catch {
      if (!response.headersSent) json(response, 500, { error: '서버에서 요청을 처리하지 못했어요. 다시 시도해 주세요.' });
      else response.end();
    }
  });
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    process.loadEnvFile(path.join(ROOT, '.env'));
  } catch (error) {
    if (error.code !== 'ENOENT') {
      console.error('.env 파일을 읽지 못했어요. 파일 형식을 확인해 주세요.');
      process.exit(1);
    }
  }
  const port = Number(process.env.PORT || 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('PORT는 1부터 65535 사이의 숫자로 설정해 주세요.');
    process.exit(1);
  }
  const server = createServer();
  server.on('error', (error) => {
    console.error(error.code === 'EADDRINUSE'
      ? `${port}번 포트가 사용 중이에요. .env의 PORT 값을 변경해 주세요.`
      : '서버를 시작하지 못했어요. 포트 설정을 확인해 주세요.');
    process.exitCode = 1;
  });
  server.listen(port, '127.0.0.1', () => {
    console.log(`소리 · 실시간 자막: http://127.0.0.1:${port}`);
    console.log('종료: Ctrl+C');
  });
}
