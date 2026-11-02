import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';
import ts from 'typescript';

// 直接编译被测模块，避免为了协议测试引入额外运行框架。
const source = await readFile(new URL('../src/grabRequests.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } });
const grabModuleUrl = `data:text/javascript;base64,${Buffer.from(compiled.outputText).toString('base64')}`;
const { createGrabRequestClient } = await import(grabModuleUrl);

function setup(t, handler) {
  const values = new Map();
  const storage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: storage });
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1800000000000 });
  let identity = { userId: 2001, token: 'runner-a' };
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    const call = { url, ...init };
    calls.push(call);
    return handler(call, calls.length);
  });
  const options = {
    identity: () => identity,
    unauthorized: () => { identity = { userId: null, token: null }; },
  };
  return {
    client: createGrabRequestClient(options), reload: () => createGrabRequestClient(options),
    values, calls, storage,
    switchIdentity: (userId, token) => { identity = { userId, token }; },
  };
}

function response(call, status = 'SUCCEEDED', httpStatus = 200, headers = {}, overrides = {}) {
  const requestId = call.headers['X-Request-Id'] || call.url.split('/').at(-1);
  return new Response(JSON.stringify({
    code: status === 'SUCCEEDED' ? 'OK' : status,
    message: status,
    data: { requestId, code: status === 'SUCCEEDED' ? 'OK' : status, status,
      grabbed: status === 'SUCCEEDED', appliedRound: 2, replayed: call.method === 'GET',
      candidateRank: null, ...overrides },
  }), { status: httpStatus, headers });
}

async function complete(t, promise) {
  let done = false;
  promise.then(() => { done = true; }, () => { done = true; });
  for (let index = 0; index < 20 && !done; index++) {
    await setImmediate();
    if (!done) t.mock.timers.tick(10001);
  }
  assert.equal(done, true, '有限重试必须结束');
  return promise;
}

test('抢单在写请求前保存 UUID 与轮次，同任务并发调用只执行一次', async t => {
  let client;
  const state = setup(t, call => {
    const saved = client.pending('order-1');
    assert.equal(saved.requestId, call.headers['X-Request-Id']);
    assert.equal(saved.expectedRound, 2);
    assert.deepEqual(JSON.parse(call.body), { expectedRound: 2 });
    return response(call);
  });
  client = state.client;
  const first = client.grab('order-1', 2);
  const second = client.grab('order-1', 99);
  assert.strictEqual(first, second);
  assert.equal((await complete(t, first)).status, 'SUCCEEDED');
  assert.equal(state.calls.length, 1);
  assert.equal(client.pending('order-1'), null);
});

test('网络响应丢失后先查询，查询不可见时复用原请求 ID 和轮次', async t => {
  const state = setup(t, (call, count) => {
    if (count === 1) throw new TypeError('connection reset');
    if (count === 2) return new Response(JSON.stringify({ code: 'REQUEST_NOT_FOUND' }), { status: 404 });
    return response(call);
  });
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.status, 'SUCCEEDED');
  assert.deepEqual(state.calls.map(call => call.method), ['POST', 'GET', 'POST']);
  assert.equal(state.calls[0].headers['X-Request-Id'], state.calls[2].headers['X-Request-Id']);
  assert.equal(state.calls[0].body, state.calls[2].body);
});

test('202 仅查询原请求，不误报成功或反复提交抢单', async t => {
  const state = setup(t, (call, count) => count === 1
    ? response(call, 'SUCCEEDED', 202, { 'Retry-After': '1' }) : response(call));
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.status, 'SUCCEEDED');
  assert.equal(result.replayed, true);
  assert.deepEqual(state.calls.map(call => call.method), ['POST', 'GET']);
});

test('持续 PROCESSING 最多三轮自动请求，保留 ID 交还用户查询', async t => {
  const state = setup(t, call => response(call, 'PROCESSING', 202));
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.status, 'PROCESSING');
  assert.equal(state.calls.length, 3);
  assert.equal(state.client.pending('order-1').requestId, result.requestId);
});

test('刷新后先恢复原请求，传入新轮次也不能改写原轮次', async t => {
  let phase = 'pending';
  const state = setup(t, call => phase === 'pending' ? response(call, 'PROCESSING', 202) : response(call));
  const first = await complete(t, state.client.grab('order-1', 2));
  phase = 'done';
  const restoredClient = state.reload();
  const result = await complete(t, restoredClient.grab('order-1', 8));
  assert.equal(result.requestId, first.requestId);
  assert.equal(state.calls[3].method, 'GET');
  assert.equal(result.status, 'SUCCEEDED');
  assert.equal(restoredClient.pending('order-1'), null);
});

test('429 遵守较长 Retry-After，期间手动点击也不提前发请求', async t => {
  const state = setup(t, call => response(call, 'PROCESSING', 429, { 'Retry-After': '60' }));
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.status, 'PROCESSING');
  assert.equal(state.calls.length, 1);
  assert.ok(result.retryAfterMillis >= 60000);
  await state.client.recover('order-1');
  assert.equal(state.calls.length, 1);
});

test('503 保留请求，随后查询成功结果', async t => {
  const state = setup(t, (call, count) => count === 1
    ? new Response('temporarily unavailable', { status: 503 }) : response(call));
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.status, 'SUCCEEDED');
  assert.deepEqual(state.calls.map(call => call.method), ['POST', 'GET']);
});

test('兼容后端 long 字符串与准入冲突的空状态，不向界面泄漏 wire 类型', async t => {
  const state = setup(t, (call, count) => count === 1
    ? response(call, 'PROCESSING', 202, {}, { retryAfterMillis: '1000' })
    : response(call, 'SUCCEEDED', 200, {}, { retryAfterMillis: '0', candidateRank: null }));
  const result = await complete(t, state.client.grab('order-1', 2));
  assert.equal(result.retryAfterMillis, 0);
  assert.equal(result.candidateRank, null);
  assert.equal(typeof result.appliedRound, 'number');
  state.calls.length = 0;
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    code: 'REQUEST_CONFLICT', data: { status: null, appliedRound: null, retryAfterMillis: '0' },
  }), { status: 409 }));
  const conflict = await state.client.grab('order-2', 2);
  assert.equal(conflict.status, 'REJECTED');
  assert.equal(conflict.code, 'REQUEST_CONFLICT');
  assert.equal(state.client.pending('order-2'), null);
});

test('切换账号隔离存储，切回原账号才能恢复原请求', async t => {
  const state = setup(t, call => response(call, 'PROCESSING', 202));
  const first = await complete(t, state.client.grab('order-1', 2));
  state.switchIdentity(2002, 'runner-b');
  assert.equal(state.client.pending('order-1'), null);
  assert.equal(await state.client.recover('order-1'), null);
  const second = await complete(t, state.client.grab('order-1', 2));
  assert.notEqual(second.requestId, first.requestId);
  assert.equal(state.calls[3].headers.Authorization, 'Bearer runner-b');
  state.switchIdentity(2001, 'runner-a');
  assert.equal(state.client.pending('order-1').requestId, first.requestId);
});

test('请求在途切换身份后不提交新身份请求，也不展示旧身份成功', async t => {
  let release;
  const state = setup(t, call => new Promise(resolve => { release = () => resolve(response(call)); }));
  const promise = state.client.grab('order-1', 2);
  state.switchIdentity(2002, 'runner-b');
  release();
  await assert.rejects(promise, error => error.code === 'IDENTITY_CHANGED');
  assert.equal(state.calls.length, 1);
  state.switchIdentity(2001, 'runner-a');
  assert.ok(state.client.pending('order-1'));
});

test('终态拒绝后由用户新操作生成新 ID', async t => {
  const state = setup(t, (call, count) => count === 1 ? response(call, 'REJECTED', 409, {}, { code: 'SLOT_FULL' }) : response(call));
  const rejected = await complete(t, state.client.grab('order-1', 2));
  assert.equal(rejected.status, 'REJECTED');
  assert.equal(state.client.pending('order-1'), null);
  const retry = await complete(t, state.client.grab('order-1', 3));
  assert.notEqual(retry.requestId, rejected.requestId);
  assert.deepEqual(JSON.parse(state.calls[1].body), { expectedRound: 3 });
});

test('过期终态清理未决记录，恢复不存在的请求不发网络请求', async t => {
  const state = setup(t, call => response(call, 'EXPIRED', 409));
  assert.equal((await complete(t, state.client.grab('order-1', 2))).status, 'EXPIRED');
  assert.equal(await state.client.recover('order-1'), null);
  assert.equal(state.calls.length, 1);
});

test('401 停止自动重试，重新登录原账号可恢复未决 ID', async t => {
  const state = setup(t, () => new Response('{}', { status: 401 }));
  await assert.rejects(state.client.grab('order-1', 2), error => error.code === 'UNAUTHORIZED');
  assert.equal(state.calls.length, 1);
  state.switchIdentity(2001, 'new-token');
  assert.ok(state.client.pending('order-1'));
});

test('损坏或不属于当前身份的存储记录不会被使用', async t => {
  const state = setup(t, call => response(call));
  state.storage.setItem('dash:pending-grab:v2:2001:order-1', JSON.stringify({
    userId: 2002, errandId: 'order-1', expectedRound: 7,
    requestId: '00000000-0000-0000-0000-000000000001', createdAt: Date.now(),
  }));
  await complete(t, state.client.grab('order-1', 2));
  assert.deepEqual(JSON.parse(state.calls[0].body), { expectedRound: 2 });
});

test('无法持久化请求时不发出抢单写请求', async t => {
  const state = setup(t, call => response(call));
  state.storage.setItem = () => { throw new Error('storage denied'); };
  assert.throws(() => state.client.grab('order-1', 2), error => error.code === 'STORAGE_UNAVAILABLE');
  assert.equal(state.calls.length, 0);
});

test('旧版 api.grab 调用等待详情期间换号，不得以新账号提交原操作', async t => {
  const localValues = new Map([['dash_token', 'runner-a'], ['dash_user', '2001']]);
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: key => localValues.get(key) ?? null,
    setItem: (key, value) => localValues.set(key, value),
    removeItem: key => localValues.delete(key),
  } });
  const apiSource = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
  const apiCompiled = ts.transpileModule(apiSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } });
  const apiModule = await import(`data:text/javascript;base64,${Buffer.from(apiCompiled.outputText.replace('./grabRequests', grabModuleUrl)).toString('base64')}`);
  let release;
  const state = setup(t, () => new Promise(resolve => { release = () => resolve(new Response(JSON.stringify({
    code: 'OK', data: { round: 2 },
  }))); }));
  const operation = apiModule.api.grab('order-1');
  apiModule.restoreIdentity('runner-b', 2002);
  release();
  await assert.rejects(operation, error => error.code === 'IDENTITY_CHANGED');
  assert.deepEqual(state.calls.map(call => call.method), ['GET']);
  assert.equal(state.values.size, 0);
});

test('确认接单固定提交点击时轮次，不自行读取新轮次或接受缺失轮次', async t => {
  const localValues = new Map([['dash_token', 'runner-a'], ['dash_user', '2001']]);
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: key => localValues.get(key) ?? null,
    setItem: (key, value) => localValues.set(key, value),
    removeItem: key => localValues.delete(key),
  } });
  const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
  const compiledApi = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } });
  const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiledApi.outputText.replace('./grabRequests', grabModuleUrl)).toString('base64')}`;
  const { api, restoreIdentity } = await import(moduleUrl);
  restoreIdentity('runner-a', 2001);
  const state = setup(t, () => new Response(JSON.stringify({ code: 'OK', data: null })));
  await assert.rejects(api.action('order-1', 'confirm'), error => error.code === 'INVALID_ROUND');
  await assert.rejects(api.action('order-1', 'confirm', -1), error => error.code === 'INVALID_ROUND');
  assert.equal(state.calls.length, 0);
  await api.action('order-1', 'confirm', 2);
  assert.equal(state.calls.length, 1);
  assert.equal(state.calls[0].method, 'POST');
  assert.equal(state.calls[0].url, '/api/errands/order-1/confirm');
  assert.deepEqual(JSON.parse(state.calls[0].body), { expectedRound: 2 });
});
