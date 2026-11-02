/** 一次逻辑抢单固定请求标识与轮次，直到得到可信终态。 */
export interface GrabResult {
  code: string;
  grabbed: boolean;
  candidateRank: number | null;
  requestId: string;
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED' | 'EXPIRED';
  appliedRound: number;
  replayed: boolean;
  retryAfterMillis?: number;
}

/** 后端 long 序列化为字符串；准入错误尚无持久请求，因此状态和轮次可以为空。 */
interface GrabWireResult extends Omit<GrabResult, 'status' | 'appliedRound' | 'candidateRank' | 'retryAfterMillis'> {
  status: GrabResult['status'] | null;
  appliedRound: number | null;
  candidateRank: number | string | null;
  retryAfterMillis?: number | string | null;
}

export interface PendingGrab {
  userId: number;
  errandId: string;
  expectedRound: number;
  requestId: string;
  createdAt: number;
  retryNotBefore?: number;
}

interface Identity { userId: number | null; token: string | null }
interface ClientOptions {
  identity: () => Identity;
  unauthorized: () => void;
}

export class GrabRequestError extends Error {
  constructor(public code: string, message: string, public retryAfterMillis = 0) {
    super(message);
    this.name = 'GrabRequestError';
  }
}

const STORAGE_PREFIX = 'dash:pending-grab:v2:';
const MAX_ATTEMPTS = 3;
const MAX_AUTO_WAIT = 10000;
const REQUEST_TIMEOUT = 8000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function createGrabRequestClient(options: ClientOptions) {
  const inFlight = new Map<string, Promise<GrabResult>>();
  const storageKey = (userId: number, errandId: string) => `${STORAGE_PREFIX}${userId}:${encodeURIComponent(errandId)}`;

  const pending = (errandId: string): PendingGrab | null => {
    const { userId } = options.identity();
    if (userId === null) return null;
    try {
      const value = JSON.parse(sessionStorage.getItem(storageKey(userId, errandId)) || 'null') as PendingGrab | null;
      if (!value || value.userId !== userId || value.errandId !== errandId ||
          !UUID.test(value.requestId) || !Number.isInteger(value.expectedRound) || value.expectedRound < 0 ||
          !Number.isFinite(value.createdAt)) return null;
      return value;
    } catch { return null; }
  };

  const save = (record: PendingGrab) => {
    try { sessionStorage.setItem(storageKey(record.userId, record.errandId), JSON.stringify(record)); }
    catch { throw new GrabRequestError('STORAGE_UNAVAILABLE', '无法保存本次操作，请允许浏览器使用会话存储后重试'); }
  };

  const assertIdentity = (identity: Identity) => {
    const current = options.identity();
    if (identity.userId !== current.userId || identity.token !== current.token) {
      throw new GrabRequestError('IDENTITY_CHANGED', '登录身份已变化，请在当前身份下查看任务');
    }
  };

  const processing = (record: PendingGrab, retryAfterMillis = 1000): GrabResult => ({
    code: 'PROCESSING', grabbed: false, candidateRank: null,
    requestId: record.requestId, status: 'PROCESSING', appliedRound: record.expectedRound,
    replayed: false, retryAfterMillis,
  });

  const call = async (record: PendingGrab, identity: Identity, query: boolean): Promise<GrabResult | null> => {
    assertIdentity(identity);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);
    try {
      const response = await fetch(query
        ? `/api/errands/${encodeURIComponent(record.errandId)}/grab-requests/${record.requestId}`
        : `/api/errands/${encodeURIComponent(record.errandId)}/grab`, {
        method: query ? 'GET' : 'POST',
        headers: {
          Authorization: `Bearer ${identity.token}`,
          ...(!query ? { 'Content-Type': 'application/json', 'X-Request-Id': record.requestId } : {}),
        },
        ...(!query ? { body: JSON.stringify({ expectedRound: record.expectedRound }) } : {}),
        signal: controller.signal,
      });
      assertIdentity(identity);
      if (response.status === 401) {
        options.unauthorized();
        throw new GrabRequestError('UNAUTHORIZED', '会话已过期，请重新登录后查询本次抢单结果');
      }
      const body = await response.json().catch(() => null) as {
        code?: string; message?: string; data?: GrabWireResult;
      } | null;
      assertIdentity(identity);
      const retryHeader = response.headers.get('Retry-After');
      const headerMillis = retryHeader === null ? 0 : /^\d+(?:\.\d+)?$/.test(retryHeader)
        ? Number(retryHeader) * 1000 : Math.max(0, Date.parse(retryHeader) - Date.now());
      const retryMillis = Math.max(Number(body?.data?.retryAfterMillis) || 0, headerMillis || 0);
      // 网关拒绝或依赖故障不能终结客户端的未决请求。
      if (response.status === 429 || response.status >= 500) {
        throw new GrabRequestError(response.status === 429 ? 'RATE_LIMITED' : 'SERVICE_UNAVAILABLE',
          body?.message || '服务繁忙，稍后继续查询本次结果', retryMillis);
      }
      if (query && (response.status === 404 || body?.code === 'REQUEST_NOT_FOUND')) return null;
      const result = body?.data;
      if (result && result.requestId === record.requestId && result.status !== null &&
          ['PROCESSING', 'SUCCEEDED', 'REJECTED', 'EXPIRED'].includes(result.status)) {
        // HTTP 202 只能解释为处理中，即使异常响应误带了成功字段。
        if (response.status === 202) return { ...processing(record, retryMillis || 1000), replayed: result.replayed };
        if (result.status === 'SUCCEEDED' && (!response.ok || result.grabbed !== true || !Number.isInteger(result.appliedRound))) {
          throw new GrabRequestError('UNKNOWN_RESULT', '暂时无法确认结果，请继续查询');
        }
        const rank = result.candidateRank === null ? null : Number(result.candidateRank);
        return { ...result, status: result.status, appliedRound: result.appliedRound ?? record.expectedRound,
          candidateRank: rank !== null && Number.isSafeInteger(rank) ? rank : null, retryAfterMillis: retryMillis };
      }
      if (response.status === 202) return processing(record, retryMillis || 1000);
      if (response.status === 400 || response.status === 409) {
        return { ...processing(record), status: 'REJECTED', code: body?.code || 'REQUEST_REJECTED' };
      }
      if (response.status === 403) throw new GrabRequestError('FORBIDDEN', body?.message || '当前身份无权操作此任务');
      // 非规范成功响应同样属于未知结果，不能误报抢中或生成新请求。
      throw new GrabRequestError('UNKNOWN_RESULT', body?.message || '暂时无法确认抢单结果，请稍后查询', retryMillis);
    } finally { clearTimeout(timer); }
  };

  const execute = async (record: PendingGrab, identity: Identity, restored: boolean): Promise<GrabResult> => {
    let query = restored;
    let latest = processing(record);
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      assertIdentity(identity);
      const waitMillis = Math.max(0, (record.retryNotBefore || 0) - Date.now());
      // 较长的服务端退避交还用户操作，禁止截短 Retry-After 提前重试。
      if (waitMillis > MAX_AUTO_WAIT) return { ...latest, retryAfterMillis: waitMillis };
      if (waitMillis > 0) await new Promise(resolve => setTimeout(resolve, waitMillis));
      assertIdentity(identity);
      try {
        let result = await call(record, identity, query);
        // 查询尚未可见不代表原写请求失败；只能复用同一个 ID 和轮次。
        if (result === null) result = await call(record, identity, false);
        if (result && result.status !== 'PROCESSING') {
          sessionStorage.removeItem(storageKey(record.userId, record.errandId));
          return result;
        }
        latest = result || processing(record);
      } catch (error) {
        if (error instanceof GrabRequestError &&
            ['UNAUTHORIZED', 'FORBIDDEN', 'IDENTITY_CHANGED', 'STORAGE_UNAVAILABLE'].includes(error.code)) throw error;
        latest = processing(record, error instanceof GrabRequestError ? error.retryAfterMillis : 0);
      }
      query = true;
      const delay = Math.max(latest.retryAfterMillis || 0, 500 * 2 ** attempt + Math.floor(Math.random() * 250));
      record = { ...record, retryNotBefore: Date.now() + delay };
      save(record);
      latest = { ...latest, retryAfterMillis: delay };
    }
    return latest;
  };

  const run = (errandId: string, expectedRound?: number, recoverOnly = false): Promise<GrabResult | null> => {
    const identity = options.identity();
    if (identity.userId === null || !identity.token) return Promise.reject(new GrabRequestError('UNAUTHORIZED', '请先登录'));
    const key = storageKey(identity.userId, errandId);
    const existingFlight = inFlight.get(key);
    if (existingFlight) return existingFlight;
    const existing = pending(errandId);
    if (!existing && recoverOnly) return Promise.resolve(null);
    if (!existing && (!Number.isInteger(expectedRound) || expectedRound! < 0)) {
      return Promise.reject(new GrabRequestError('INVALID_ROUND', '任务轮次无效，请刷新任务后重试'));
    }
    const record = existing || {
      userId: identity.userId, errandId, expectedRound: expectedRound!,
      requestId: crypto.randomUUID(), createdAt: Date.now(),
    };
    // 必须先持久化，再发出任何写请求。
    save(record);
    const promise = execute(record, identity, existing !== null).finally(() => { inFlight.delete(key); });
    inFlight.set(key, promise);
    return promise;
  };

  return {
    pending,
    grab: (errandId: string, expectedRound: number) => run(errandId, expectedRound) as Promise<GrabResult>,
    recover: (errandId: string) => run(errandId, undefined, true),
  };
}
