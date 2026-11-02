import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { api, TYPE_ICON, TYPE_TEXT, yuan } from '../api';
import Icon from '../components/Icon';
import './taskPages.css';
import './mobilePublish.css';

const CATEGORIES = [
  { type: 'DELIVERY', description: '快递、餐食与日常物品', example: '帮我从三食堂带一份晚饭到 6 号楼' },
  { type: 'BUY', description: '顺路带回你需要的好物', example: '帮我从校园超市买两瓶矿泉水' },
  { type: 'QUEUE', description: '替你等一会儿，省下时间', example: '帮我在图书馆打印室排队取资料' },
  { type: 'OTHER', description: '校园里的其他小小帮忙', example: '帮我把借阅的书送还图书馆' },
];

function initialValues() {
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const requestedType = params.get('type') || 'DELIVERY';
  const type = CATEGORIES.some((item) => item.type === requestedType) ? requestedType : 'DELIVERY';
  return { type, title: params.get('template') || '' };
}

/** 使用十进制拆分，避免浮点数把分值转换出误差。 */
function parseReward(value: string): number | null {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value.trim())) return null;
  const [whole, decimal = ''] = value.trim().split('.');
  const cents = Number(whole) * 100 + Number(decimal.padEnd(2, '0'));
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null;
}

function useMobilePublish() {
  const [mobile, setMobile] = useState(() => window.matchMedia('(max-width: 600px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 600px)');
    const update = () => setMobile(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  return mobile;
}

export default function Publish() {
  const mobile = useMobilePublish();
  const [step, setStep] = useState<1 | 2>(1);
  const [initial] = useState(initialValues);
  const [title, setTitle] = useState(initial.title);
  const [reward, setReward] = useState('15');
  const [type, setType] = useState(initial.type);
  const [balance, setBalance] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [published, setPublished] = useState<{ id: string; title: string; rewardCents: number } | null>(null);
  const submitting = useRef(false);
  const rewardCents = parseReward(reward);
  const category = CATEGORIES.find((item) => item.type === type)!;

  useEffect(() => {
    let active = true;
    api.wallet().then((wallet) => { if (active) setBalance(wallet.availableCents); }).catch(() => {});
    return () => { active = false; };
  }, []);

  const validateTitle = () => {
    if (!title.trim()) { setError('请描述一下你需要的帮助。'); return false; }
    if (title.trim().length > 64) { setError('请将任务内容控制在 64 字以内，方便同学快速了解需求。'); return false; }
    return true;
  };

  const changeStep = (nextStep: 1 | 2) => {
    setError(null);
    setStep(nextStep);
    window.scrollTo({ top: 0, behavior: 'instant' });
  };

  const nextStep = (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (validateTitle()) changeStep(2);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting.current) return;
    setError(null);
    if (!validateTitle()) return;
    if (rewardCents === null) { setError('请输入大于 0 的金额，最多保留两位小数。'); return; }
    submitting.current = true;
    setBusy(true);
    try {
      const result = await api.publish({ title: title.trim(), rewardCents, slotTotal: 1, type });
      setPublished({ id: result.errandId, title: title.trim(), rewardCents });
      window.dispatchEvent(new Event('dash:changed'));
      api.wallet().then((wallet) => setBalance(wallet.availableCents)).catch(() => {});
    } catch (err) {
      setError(err instanceof Error ? err.message : '暂时没有发布成功，请稍后再试。');
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  };

  const publishAgain = () => {
    setPublished(null);
    setTitle('');
    changeStep(1);
  };

  if (mobile && published) return (
    <div className="mobile-publish mobile-publish-done">
      <section className="mobile-publish-result" aria-live="polite">
        <div className="mobile-publish-result-icon"><Icon name="check" size={34} /></div>
        <h1>发布成功</h1>
        <p>等待顺路的同学接单</p>
        <div className="mobile-publish-receipt">
          <span className="mobile-publish-receipt-label"><Icon name={TYPE_ICON[type]} size={18} />{TYPE_TEXT[type]}</span>
          <h2>{published.title}</h2>
          <div><span>已托管悬赏</span><strong>{yuan(published.rewardCents)}</strong></div>
        </div>
        <p className="mobile-publish-result-note"><Icon name="shield" size={15} />确认完成后，悬赏才会结算给对方</p>
      </section>
      <div className="mobile-publish-bottom mobile-publish-done-actions">
        <a className="btn btn-primary" href={`#/detail/${published.id}`}>查看任务进度<Icon name="arrow-right" size={18} /></a>
        <button className="mobile-publish-text-button" type="button" onClick={publishAgain}>再发一个任务</button>
      </div>
    </div>
  );

  if (mobile) return (
    <div className="mobile-publish">
      <div className="mobile-publish-progress" aria-label={`发布任务，第 ${step} 步，共 2 步`}>
        <div className={`mobile-publish-progress-item ${step === 1 ? 'is-current' : 'is-complete'}`}><span>{step === 2 ? <Icon name="check" size={12} /> : '1'}</span>填写需求</div>
        <div className="mobile-publish-progress-line" />
        <div className={`mobile-publish-progress-item ${step === 2 ? 'is-current' : ''}`}><span>2</span>设置悬赏</div>
        <small>{step}/2</small>
      </div>
      <form onSubmit={step === 1 ? nextStep : submit} noValidate>
        <fieldset disabled={busy}>
          {step === 1 ? <>
            <div className="mobile-publish-intro"><h1>需要同学帮点什么？</h1><p>把小事交给顺路的人</p></div>
            <section className="mobile-publish-card mobile-publish-category">
              <h2>任务类型</h2>
              <div className="mobile-publish-types" role="group" aria-label="任务类型">
                {CATEGORIES.map((item) => <button type="button" key={item.type} aria-pressed={type === item.type} className={type === item.type ? 'is-selected' : ''} onClick={() => { setType(item.type); setError(null); }}><span><Icon name={TYPE_ICON[item.type]} size={24} /></span><strong>{TYPE_TEXT[item.type]}</strong>{type === item.type && <i><Icon name="check" size={10} /></i>}</button>)}
              </div>
            </section>
            <section className="mobile-publish-card mobile-publish-description">
              <label htmlFor="task-title">任务内容<span>写清物品、地点和注意事项</span></label>
              <textarea id="task-title" value={title} rows={4} maxLength={64} placeholder={`例如：${category.example}`} onChange={(event) => { setTitle(event.target.value); setError(null); }} aria-describedby="task-title-hint" aria-invalid={error && !title.trim() ? true : undefined} />
              <div id="task-title-hint" className="mobile-publish-count">{title.length}<span>/ 64 字</span></div>
            </section>
          </> : <>
            <div className="mobile-publish-intro"><h1>给这份帮助，一份悬赏</h1><p>金额由你定，完成后再结算</p></div>
            <section className="mobile-publish-card mobile-publish-reward">
              <label htmlFor="task-reward">悬赏金额<span>人民币</span></label>
              <div className="mobile-publish-amount"><span>¥</span><input id="task-reward" type="text" inputMode="decimal" value={reward} onChange={(event) => { setReward(event.target.value); setError(null); }} autoComplete="off" aria-describedby="task-reward-hint" /><small>元</small></div>
              <div className="mobile-publish-presets">{[5, 10, 15, 20, 30].map((amount) => <button type="button" className={rewardCents === amount * 100 ? 'is-selected' : ''} key={amount} onClick={() => { setReward(String(amount)); setError(null); }}>¥{amount}</button>)}</div>
              <div className="mobile-publish-balance" id="task-reward-hint"><span><Icon name="wallet" size={15} />{balance === null ? '发布时将校验钱包余额' : `可用余额 ${yuan(balance)}`}</span><a href="#/wallet">钱包<Icon name="arrow-right" size={14} /></a></div>
            </section>
            <section className="mobile-publish-card mobile-publish-summary">
              <div><span><Icon name={TYPE_ICON[type]} size={17} />{TYPE_TEXT[type]}</span><button type="button" onClick={() => changeStep(1)}>修改</button></div>
              <p>{title.trim()}</p>
              <span className="mobile-publish-summary-meta"><Icon name="user" size={14} />1 位同学协助</span>
            </section>
            <p className="mobile-publish-escrow-note"><Icon name="shield" size={15} />发布后悬赏由平台托管，确认完成后结算。</p>
          </>}
        </fieldset>
        {error && <div className="mobile-publish-error" role="alert"><Icon name="info" size={16} />{error}</div>}
        <div className="mobile-publish-bottom">
          {step === 1 ? <button className="btn btn-primary" type="submit">下一步，设置悬赏<Icon name="arrow-right" size={18} /></button> : <div className="mobile-publish-submit-row"><button className="mobile-publish-back" type="button" onClick={() => changeStep(1)} disabled={busy}><Icon name="arrow-left" size={17} />上一步</button><button className="btn btn-primary" type="submit" disabled={busy}>{busy ? '正在发布…' : '发布并托管'}{!busy && <Icon name="arrow-right" size={18} />}</button></div>}
        </div>
      </form>
    </div>
  );

  if (published) return (
    <div className="task-publish-page">
      <div className="page-head"><div><span className="eyebrow">CREATE AN ERRAND</span><h1>发布任务</h1><p>让校园里的顺路，成为你的便利。</p></div></div>
      <section className="panel task-publish-success" aria-live="polite">
        <div className="task-success-icon"><Icon name="check" size={30} /></div>
        <span className="eyebrow">READY TO GO</span>
        <h2>任务已发布，等一位顺路的同学</h2>
        <p>{published.title}</p>
        <div className="task-success-reward"><span>已托管悬赏</span><strong>{yuan(published.rewardCents)}</strong></div>
        <p className="muted">完成后确认结算，悬赏会转入跑腿同学的钱包。</p>
        <div className="task-success-actions"><a className="btn btn-primary" href={`#/detail/${published.id}`}>查看任务进度 <Icon name="arrow-right" size={16} /></a><button className="btn btn-ghost" onClick={publishAgain}>再发一个任务</button></div>
        <span className="task-order-id">任务编号 {published.id}</span>
      </section>
    </div>
  );

  return (
    <div className="task-publish-page">
      <div className="page-head"><div><span className="eyebrow">CREATE AN ERRAND</span><h1>小事交给同学，大事留给自己</h1><p>说清需求，设好悬赏。让顺路的同学来帮你。</p></div><a className="btn btn-ghost" href="#/square"><Icon name="arrow-left" size={16} /> 返回广场</a></div>
      <div className="task-publish-layout">
        <form className="panel task-compose" onSubmit={submit} noValidate>
          <fieldset disabled={busy}>
            <div className="task-form-section">
              <div className="task-section-heading"><span className="task-step-number">01</span><div><h2>需要什么帮助？</h2><p>选一个类型，让合适的同学更容易找到你。</p></div></div>
              <div className="task-type-grid" role="group" aria-label="任务类型">
                {CATEGORIES.map((item) => <button type="button" key={item.type} aria-pressed={type === item.type} className={`task-type-option ${type === item.type ? 'is-selected' : ''}`} onClick={() => { setType(item.type); setError(null); }}><span className="task-type-symbol"><Icon name={TYPE_ICON[item.type]} size={21} /></span><span><strong>{TYPE_TEXT[item.type]}</strong><small>{item.description}</small></span><span className="task-selection-dot">{type === item.type && <Icon name="check" size={11} />}</span></button>)}
              </div>
            </div>
            <div className="task-form-section">
              <div className="task-section-heading"><span className="task-step-number">02</span><div><h2>把需求说清楚</h2><p>物品、地点和注意事项，都可以写在这里。</p></div></div>
              <label className="task-field-label" htmlFor="task-title">任务内容 <span>必填</span></label>
              <textarea id="task-title" value={title} rows={4} maxLength={64} placeholder={`例如：${category.example}`} onChange={(event) => { setTitle(event.target.value); setError(null); }} aria-describedby="task-title-hint" />
              <div className="task-field-foot"><span id="task-title-hint">清晰的描述，能让接单更顺利。</span><span>{title.length} / 64 字</span></div>
            </div>
            <div className="task-form-section task-reward-section">
              <div className="task-section-heading"><span className="task-step-number">03</span><div><h2>给这份帮助一份悬赏</h2><p>按任务难度设置，发布时将从钱包托管。</p></div></div>
              <label className="task-field-label" htmlFor="task-reward">悬赏金额 <span>人民币</span></label>
              <div className="task-reward-input"><span>¥</span><input id="task-reward" type="text" inputMode="decimal" value={reward} onChange={(event) => { setReward(event.target.value); setError(null); }} autoComplete="off" aria-describedby="task-reward-hint" /><span>元</span></div>
              <div className="task-price-presets">{[5, 10, 15, 20, 30].map((amount) => <button type="button" className={rewardCents === amount * 100 ? 'is-selected' : ''} key={amount} onClick={() => { setReward(String(amount)); setError(null); }}>¥{amount}</button>)}</div>
              <div className="task-balance-line" id="task-reward-hint"><span><Icon name="wallet" size={15} /> {balance === null ? '发布时将校验钱包余额' : `钱包可用 ${yuan(balance)}`}</span><a href="#/wallet">查看钱包 <Icon name="arrow-right" size={13} /></a></div>
            </div>
          </fieldset>
          {error && <div className="banner warn" role="alert">{error}</div>}
          <div className="task-compose-footer"><span><Icon name="shield" size={17} /> 悬赏托管，确认完成后结算</span><button className="btn btn-primary task-submit" type="submit" disabled={busy}>{busy ? '正在发布…' : '发布并托管'}{!busy && <Icon name="arrow-right" size={17} />}</button></div>
        </form>
        <aside className="task-publish-aside">
          <section className="panel task-preview"><div className="task-aside-heading"><span>任务预览</span><span className="task-live-tag">实时预览</span></div><div className="task-preview-symbol"><Icon name={TYPE_ICON[type]} size={28} /></div><span className="task-category-label">{TYPE_TEXT[type]}</span><h2 className={!title.trim() ? 'task-placeholder' : ''}>{title.trim() || '你的下一件小事，从这里开始'}</h2><div className="task-preview-meta"><span><Icon name="user" size={14} /> 1 位同学协助</span><span>发布后等待接单</span></div><div className="task-preview-reward"><span>任务悬赏</span><strong>{rewardCents === null ? '待填写' : yuan(rewardCents)}</strong></div></section>
          <section className="task-publish-guide"><div className="task-guide-title"><Icon name="shield" size={18} /><h3>每一步，都有记录</h3></div><ol><li><span>1</span><div><strong>发布并托管</strong><p>悬赏由平台托管，等待同学接单。</p></div></li><li><span>2</span><div><strong>跟进任务进度</strong><p>接单、取货、送达，动态随时可查。</p></div></li><li><span>3</span><div><strong>完成后确认结算</strong><p>确认帮助已完成，再把悬赏交给对方。</p></div></li></ol><a href="#/mine">查看我发布的任务 <Icon name="arrow-right" size={14} /></a></section>
        </aside>
      </div>
    </div>
  );
}
