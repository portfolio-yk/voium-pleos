import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createServer } from '../server.js';

async function withServer(options, run) {
  const server = createServer(options);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    await run(origin);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
}

function requestRaw(origin, requestPath, headers = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request(origin, { path: requestPath, headers }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body }));
    });
    request.on('error', reject);
    request.end();
  });
}

const postOptions = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' };

test('serves public HTML, JavaScript modules and CSS with correct MIME and security headers', async () => {
  await withServer({ apiKey: '' }, async (origin) => {
    for (const [url, mime] of [
      ['/', 'text/html'],
      ['/styles.css', 'text/css'],
      ['/app.js', 'text/javascript'],
      ['/speechmatics.js', 'text/javascript'],
      ['/transcript.js', 'text/javascript'],
      ['/conversation.js', 'text/javascript'],
      ['/speaker-theme.js', 'text/javascript'],
      ['/audio-processor.js', 'text/javascript'],
      ['/favicon.svg', 'image/svg+xml'],
    ]) {
      const response = await fetch(origin + url);
      assert.equal(response.status, 200, url);
      assert.ok(response.headers.get('content-type').startsWith(mime), url);
      assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.match(response.headers.get('content-security-policy'), /script-src 'self'/);
      assert.ok((await response.text()).length > 0, url);
    }
    const head = await fetch(origin + '/', { method: 'HEAD' });
    assert.equal(head.status, 200);
    assert.equal(await head.text(), '');
    assert.ok(Number(head.headers.get('content-length')) > 0);
  });
});

test('only explicitly public assets are exposed, including encoded traversal paths', async () => {
  await withServer({ apiKey: 'PRIVATE_SERVER_KEY' }, async (origin) => {
    for (const requestPath of ['/.env', '/server.js', '/package.json', '/tests/server.test.js', '/../.env', '/%2e%2e/.env', '/%2e%2e%2f.env', '/public/../.env']) {
      const response = await requestRaw(origin, requestPath);
      assert.equal(response.status, 404, requestPath);
      assert.ok(!response.body.includes('PRIVATE_SERVER_KEY'));
    }
    assert.equal((await requestRaw(origin, '/%E0%A4%A')).status, 400);
  });
});

test('configuration exposes only whether a server API key exists', async () => {
  for (const [apiKey, configured] of [['', false], ['  SECRET_SERVER_KEY  ', true]]) {
    await withServer({ apiKey }, async (origin) => {
      const response = await fetch(origin + '/api/token');
      assert.equal(response.status, 200);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.deepEqual(await response.json(), { configured });
    });
  }
});

test('mints a short-lived token server-side without exposing the API key', async () => {
  const calls = [];
  await withServer({
    apiKey: '  PRIVATE_KEY  ',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return Response.json({ key_value: 'temporary-token', ignored: 'PRIVATE_KEY' });
    },
  }, async (origin) => {
    const response = await fetch(origin + '/api/token', {
      ...postOptions,
      headers: { ...postOptions.headers, Origin: origin },
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { token: 'temporary-token' });
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://mp.speechmatics.com/v1/api-keys?type=rt');
  assert.equal(calls[0].options.headers.Authorization, 'Bearer PRIVATE_KEY');
  assert.deepEqual(JSON.parse(calls[0].options.body), { ttl: 60 });
  assert.ok(calls[0].options.signal instanceof AbortSignal);
});

test('404 falls back to the legacy token endpoint once; other upstream failures do not retry', async () => {
  const urls = [];
  await withServer({ apiKey: 'key', fetchImpl: async (url) => {
    urls.push(url);
    return urls.length === 1 ? new Response('', { status: 404 }) : Response.json({ key_value: 'fallback' });
  } }, async (origin) => {
    const response = await fetch(origin + '/api/token', postOptions);
    assert.deepEqual(await response.json(), { token: 'fallback' });
  });
  assert.deepEqual(urls, [
    'https://mp.speechmatics.com/v1/api-keys?type=rt',
    'https://mp.speechmatics.com/v1/api_keys?type=rt',
  ]);

  for (const [upstreamStatus, expectedStatus] of [[401, 502], [403, 502], [429, 503], [500, 502]]) {
    let calls = 0;
    await withServer({ apiKey: 'key', fetchImpl: async () => {
      calls++;
      return new Response('SECRET_UPSTREAM_DETAILS', { status: upstreamStatus });
    } }, async (origin) => {
      const response = await fetch(origin + '/api/token', postOptions);
      assert.equal(response.status, expectedStatus);
      assert.ok(!(await response.text()).includes('SECRET_UPSTREAM_DETAILS'));
    });
    assert.equal(calls, 1);
  }
});

test('missing credentials, malformed token responses and network failures return safe errors', async () => {
  let calls = 0;
  await withServer({ apiKey: '', fetchImpl: async () => { calls++; } }, async (origin) => {
    assert.equal((await fetch(origin + '/api/token', postOptions)).status, 503);
  });
  assert.equal(calls, 0);
  for (const fetchImpl of [
    async () => Response.json({ not_a_token: 'SECRET' }),
    async () => new Response('not json'),
    async () => { throw new Error('SECRET_NETWORK_DETAIL'); },
  ]) {
    await withServer({ apiKey: 'key', fetchImpl }, async (origin) => {
      const response = await fetch(origin + '/api/token', postOptions);
      assert.equal(response.status, 502);
      assert.ok(!(await response.text()).includes('SECRET'));
    });
  }
});

test('blocks cross-origin token requests, DNS rebinding hosts and cross-site fetch metadata', async () => {
  let calls = 0;
  await withServer({ apiKey: 'key', fetchImpl: async () => {
    calls++;
    return Response.json({ key_value: 'token' });
  } }, async (origin) => {
    for (const headers of [
      { Origin: 'https://attacker.example' },
      { Origin: 'null' },
      { Origin: origin + '/path' },
      { 'Sec-Fetch-Site': 'cross-site' },
    ]) {
      const response = await fetch(origin + '/api/token', {
        ...postOptions,
        headers: { ...postOptions.headers, ...headers },
      });
      assert.equal(response.status, 403, JSON.stringify(headers));
      assert.ok(!(await response.text()).includes('token'));
    }
    // fetch can normalize forbidden browser headers such as Host. Use a raw
    // HTTP client to exercise DNS rebinding validation on the actual wire.
    for (const host of ['attacker.example', '127.0.0.1:1', '127.0.0.1.attacker.example']) {
      const response = await requestRaw(origin, '/api/token', { Host: host });
      assert.equal(response.status, 403, host);
      assert.ok(!response.body.includes('key'));
    }
  });
  assert.equal(calls, 0);
});

test('validates methods, JSON content type, malformed JSON and body size before upstream access', async () => {
  let calls = 0;
  await withServer({ apiKey: 'key', fetchImpl: async () => {
    calls++;
    return Response.json({ key_value: 'token' });
  } }, async (origin) => {
    const method = await fetch(origin + '/api/token', { method: 'OPTIONS' });
    assert.equal(method.status, 405);
    assert.equal(method.headers.get('allow'), 'GET, POST');
    assert.equal(method.headers.get('access-control-allow-origin'), null);
    assert.equal((await fetch(origin + '/api/token', { method: 'POST', body: '{}' })).status, 415);
    assert.equal((await fetch(origin + '/api/token', { ...postOptions, body: '{bad' })).status, 400);
    assert.equal((await fetch(origin + '/api/token', { ...postOptions, body: ' '.repeat(1025) })).status, 413);
    assert.equal((await fetch(origin + '/', { method: 'POST' })).status, 405);
  });
  assert.equal(calls, 0);
});
