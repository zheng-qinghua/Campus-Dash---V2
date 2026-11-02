"""手机 App 布局与交互验收；需先启动前端，API 使用测试替身。"""
import importlib.util,json
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright,expect
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('smoke',root/'tests/ui_smoke.py'); mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
mock=mod.MockApi(); out = Path(__file__).resolve().parents[1] / 'verification/2026-10-14-mobile-app/account';out.mkdir(parents=True,exist_ok=True)
state={'fail':False,'empty':False}
def route(r):
    path=urlparse(r.request.url).path
    if state['fail'] and path in ['/api/credit/self','/api/credit/ranking','/api/wallet','/api/wallet/ledger']:
        return r.fulfill(status=503,body='temporary failure')
    if path=='/api/wallet/ledger':
        data=[] if state['empty'] else [{'time':f'2026-10-14T04:{i:02}:00Z','direction':'DEBIT' if i%2 else 'CREDIT','amountCents':str(850+i*100),'refType':'ESCROW' if i%2 else 'SETTLE','refId':mod.IDS[0],'bizNo':f'account-check-{i}'} for i in range(10)]
        return r.fulfill(status=200,content_type='application/json',body=json.dumps({'code':'OK','data':data}))
    if path=='/api/credit/self':
        data={'score':82,'windowDays':30,'events':[] if state['empty'] else [{'type':'SETTLE','description':'完成结算' if i%2 else '抢中后超时未确认','delta':2 if i%2 else -5,'refId':mod.IDS[0],'time':f'2026-10-14T02:{i:02}:00Z'} for i in range(8)]}
        return r.fulfill(status=200,content_type='application/json',body=json.dumps({'code':'OK','data':data}))
    return mock.route(r)
report={'checks':[],'sizes':[],'pageErrors':[]}
with sync_playwright() as p:
    b=p.chromium.launch(headless=True)
    ctx=b.new_context(viewport={'width':390,'height':844},device_scale_factor=1,locale='zh-CN')
    ctx.add_init_script("localStorage.setItem('dash_token','mock-2001');localStorage.setItem('dash_user','2001')")
    ctx.route('**/api/**',route);page=ctx.new_page();page.set_default_timeout(6000);page.on('pageerror',lambda e:report['pageErrors'].append(str(e)))
    def goto(route):
        page.goto('http://127.0.0.1:5173/#/'+route);page.wait_for_load_state('networkidle');page.wait_for_timeout(180)
    def size(name):
        metrics=page.evaluate("() => ({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight,pageHeight:document.querySelector('.account-page')?.getBoundingClientRect().height})")
        assert metrics['scrollWidth']<=metrics['width'],metrics
        report['sizes'].append({'name':name,**metrics})
        page.screenshot(path=str(out/(name+'.png')),full_page=False)
    goto('wallet');expect(page.locator('.account-balance-value')).to_have_text('¥268.80')
    expect(page.locator('.account-table tbody tr:visible')).to_have_count(4)
    size('wallet-390')
    page.get_by_role('button',name='查看更多账单').click();expect(page.locator('.account-table tbody tr:visible')).to_have_count(8)
    page.get_by_role('button',name='借记',exact=True).click();expect(page.locator('.account-table tbody tr:visible')).to_have_count(4)
    page.locator('.account-select-label select').select_option('SETTLE');expect(page.get_by_text('没有符合条件的流水')).to_be_visible()
    page.get_by_role('button',name='清除筛选').click();expect(page.locator('.account-table tbody tr:visible')).to_have_count(4)
    page.locator('.account-wallet-help summary').click();expect(page.locator('.account-wallet-help p').first).to_be_visible()
    report['checks'].append('钱包分页、方向/类型筛选、清空筛选、说明展开正常')
    goto('credit');expect(page.locator('.account-credit-event:visible')).to_have_count(4);expect(page.locator('.account-ranking')).not_to_be_visible();size('credit-390')
    page.get_by_role('button',name='校园榜单',exact=True).click();expect(page.locator('.account-ranking')).to_be_visible();expect(page.locator('.account-credit-records')).not_to_be_visible();size('ranking-390')
    page.get_by_role('button',name='计分规则',exact=True).click();expect(page.locator('.account-credit-rules')).to_be_visible();expect(page.locator('.account-credit-layout')).not_to_be_visible();size('rules-390')
    page.get_by_role('button',name='信用记录',exact=True).click();page.get_by_role('button',name='加分记录').click();expect(page.locator('.account-credit-event:visible')).to_have_count(4)
    page.get_by_role('button',name='全部记录').click();page.get_by_role('button',name='查看更多记录').click();expect(page.locator('.account-credit-event:visible')).to_have_count(8)
    report['checks'].append('信用三栏目切换、筛选与更多记录正常')
    for width in [320,1440]:
        page.set_viewport_size({'width':width,'height':844 if width==320 else 1000})
        goto('wallet');size(f'wallet-{width}')
        expect(page.locator('.account-table tbody tr:visible')).to_have_count(4 if width==320 else 10)
        goto('credit');size(f'credit-{width}')
        expect(page.locator('.account-ranking')).not_to_be_visible() if width==320 else expect(page.locator('.account-ranking')).to_be_visible()
    report['checks'].append('320/390无横向溢出，1440桌面仍展示全部记录/榜单/规则')
    page.set_viewport_size({'width':390,'height':844});state['fail']=True
    for route_name in ['wallet','credit']:
        goto(route_name);expect(page.locator('.account-error')).to_be_visible();state['fail']=False;page.get_by_role('button',name='重试',exact=True).click();expect(page.locator('.account-error')).to_have_count(0);state['fail']=True
    report['checks'].append('余额、账单、信用、榜单加载失败均可重试恢复')
    state['fail']=False;state['empty']=True
    goto('wallet');expect(page.get_by_text('你的账单，从第一笔开始')).to_be_visible()
    goto('credit');expect(page.get_by_text('信任，从一次履约开始')).to_be_visible()
    report['checks'].append('空账单与空信用记录状态正常')
    assert not report['pageErrors'],report['pageErrors']
    b.close()
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False,indent=2))
