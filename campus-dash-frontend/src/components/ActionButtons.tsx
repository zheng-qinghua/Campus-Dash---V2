import { useEffect, useRef, useState } from 'react';
import { api, ACTION_TEXT, getUserId } from '../api';
import type { ErrandCard, GrabResult } from '../api';
import Icon from './Icon';
interface Props { card: ErrandCard; onChanged: () => void; compact?: boolean }
/** 业务操作全部由后端 availableActions 决定，前端只负责呈现和反馈。 */
export default function ActionButtons({ card, onChanged, compact }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const lock = useRef(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [pending, setPending] = useState(() => Boolean(api.pendingGrab(card.id)));
  const [refreshedCard, setRefreshedCard] = useState<ErrandCard | null>(null);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const mounted = useRef(true);
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const currentCard = refreshedCard || card;
  const userId = getUserId();
  useEffect(() => { setRefreshedCard(null); }, [card]);
  useEffect(() => {
    mounted.current = true;
    if (api.pendingGrab(card.id)) void run('RECOVER_GRAB');
    return () => { mounted.current = false; };
  }, [card.id, userId]);

  const refresh = async () => {
    try {
      const latest = await api.detail(card.id);
      if (!mounted.current || getUserId() !== userId) return null;
      setRefreshedCard(latest); setNeedsRefresh(false);
      return latest;
    } catch {
      if (mounted.current && getUserId() === userId) setNeedsRefresh(true);
      return null;
    }
  };

  const grabMessage = (result: GrabResult, latest: ErrandCard | null) => {
    if (result.status === 'PROCESSING') return { text: '抢单结果仍在确认中，请稍后查询。本次操作已保留。', error: false };
    if (result.status === 'SUCCEEDED') {
      if (!latest) return { text: '本次抢单已成功，暂时无法刷新最新状态，请刷新任务后再操作。', error: false };
      if (latest.round === result.appliedRound && latest.grabberId === String(userId) && latest.status === 'LOCKED') {
        return { text: '抢单成功，请及时确认接单。', error: false };
      }
      return { text: '本次抢单曾成功，任务进度已更新，请以当前状态为准。', error: false };
    }
    const text: Record<string, string> = {
      SLOT_FULL: '当前名额已被占用，请查看最新任务状态。',
      ROUND_MISMATCH: '接单轮次已变化，请查看最新任务后重新操作。',
      STALE_ROUND: '接单轮次已变化，请查看最新任务后重新操作。',
      REQUEST_CONFLICT: '本次操作与原请求不一致，请查看最新任务后重新操作。',
      SELF_GRAB: '不能抢自己发布的任务。',
    };
    return { text: result.status === 'EXPIRED' ? '本次抢单未完成且已过期，可查看最新任务后重新操作。' :
      text[result.code] || '本次未能抢中，请查看最新任务状态后再操作。', error: true };
  };

  const run = async (action: string) => {
    if (lock.current) return;
    lock.current = true; setBusy(action); setMessage(null);
    try {
      let text = `${ACTION_TEXT[action]}成功`;
      let error = false;
      if (action === 'GRAB' || action === 'RECOVER_GRAB') {
        const result = action === 'GRAB' ? await api.grab(card.id, currentCard.round) : await api.recoverGrab(card.id);
        if (!mounted.current || getUserId() !== userId) return;
        setPending(Boolean(api.pendingGrab(card.id)));
        if (!result) return;
        const latest = result.status === 'PROCESSING' ? null : await refresh();
        if (!mounted.current || getUserId() !== userId) return;
        const feedback = grabMessage(result, latest);
        text = feedback.text; error = feedback.error;
        setMessage(feedback);
        if (result.status === 'PROCESSING') return;
      }
      else if (action === 'ARBITRATE') { location.hash = `#/detail/${card.id}`; return; }
      else {
        const result = await api.action(card.id, action.toLowerCase() as 'confirm', currentCard.round);
        if (action === 'CANCEL' || action === 'SETTLE') {
          const outcome = typeof result === 'object' && result !== null && 'result' in result ? result.result : undefined;
          const expected = action === 'CANCEL' ? 'REFUNDED' : 'SETTLED';
          if (outcome === `ALREADY_${expected}`) {
            text = action === 'CANCEL' ? '该任务已取消并退款，请查看最新状态。' : '该任务已完成结算，请查看最新状态。';
          } else if (outcome !== expected) {
            text = outcome === 'CONFLICT' ? '任务状态已发生变化，请查看最新记录后重试。' : '暂时无法确认处理结果，请查看最新任务状态后再操作。';
            error = true;
          }
        }
      }
      if (!mounted.current || getUserId() !== userId) return;
      setMessage({ text, error });
      window.dispatchEvent(new CustomEvent('dash:notice', { detail: { text, error } }));
      window.dispatchEvent(new Event('dash:changed')); changed.current();
    } catch (e) {
      if (mounted.current && getUserId() === userId) {
        setPending(Boolean(api.pendingGrab(card.id)));
        setMessage({ text: e instanceof Error ? e.message : '网络连接失败，请重试', error: true });
      }
    } finally {
      lock.current = false;
      if (mounted.current) { setBusy(null); setConfirming(null); }
    }
  };
  if (currentCard.availableActions.length === 0 && !message && !pending) return null;
  return <div className={`actions ${compact ? 'compact' : ''}`}>
    {currentCard.availableActions.filter(a => !(a === 'GRAB' && pending)).map(a => <button key={a} disabled={busy !== null || needsRefresh || pending} onClick={() => ['CANCEL', 'SETTLE', 'DISPUTE'].includes(a) ? setConfirming(a) : void run(a)} className={`btn btn-${a.toLowerCase()}`}>{busy === a ? '处理中…' : ACTION_TEXT[a] ?? a}{a === 'GRAB' && <Icon name="arrow-right" size={14}/>}</button>)}
    {pending && <button className="btn btn-grab" disabled={busy !== null} onClick={() => void run('RECOVER_GRAB')}>{busy ? '正在确认结果…' : '查询抢单结果'}</button>}
    {needsRefresh && <button className="btn" disabled={busy !== null} onClick={() => void refresh()}>刷新任务状态</button>}
    {confirming && <div className="action-confirm"><p>{confirming === 'SETTLE' ? '确认任务已经完成？悬赏将结算给跑腿同学。' : confirming === 'CANCEL' ? '确认取消任务？托管的悬赏将退回钱包。' : '确认对本次任务发起争议？后续由平台协助处理。'}</p><button className="btn btn-primary" disabled={busy !== null} onClick={() => void run(confirming)}>确认操作</button><button className="btn" disabled={busy !== null} onClick={() => setConfirming(null)}>暂不操作</button></div>}
    {message && <span className={`action-msg ${message.error ? 'is-error' : ''}`} role="status">{message.text}</span>}
  </div>;
}
