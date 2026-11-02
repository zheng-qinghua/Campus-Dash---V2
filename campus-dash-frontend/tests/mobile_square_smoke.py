"""手机 App 布局与交互验收；需先启动前端，API 使用测试替身。"""
import sys, json
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from ui_smoke import MockApi
from playwright.sync_api import sync_playwright, expect
out = Path(__file__).resolve().parents[1] / 'verification/2026-10-14-mobile-app/square'
out.mkdir(parents=True,exist_ok=True)
result = {'checks': [], 'metrics': []}
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width':390,'height':844}, device_scale_factor=1, locale='zh-CN')
    mock = MockApi()
    context.route('**/api/**', mock.route)
    context.add_init_script("localStorage.setItem('dash_token','mock-2001');localStorage.setItem('dash_user','2001')")
    page = context.new_page()
    page.goto('http://127.0.0.1:5173/#/square')
    expect(page.locator('.errand-card')).to_have_count(6)
    for width in [390, 320, 600, 1440]:
        page.set_viewport_size({'width':width,'height':844})
        page.evaluate('scrollTo(0,0)')
        page.wait_for_timeout(150)
        metric = page.locator('.square-page').evaluate('''root => ({width:innerWidth,documentWidth:document.documentElement.scrollWidth, cards:[...root.querySelectorAll('.errand-card')].slice(0,3).map(card=>{const r=card.getBoundingClientRect();return{top:r.top,height:r.height,bottom:r.bottom,title:card.querySelector('.card-title').textContent,buttonHeight:card.querySelector('button')?.getBoundingClientRect().height}}), platformVisible:getComputedStyle(root.querySelector('.market-aside')).display!=='none'})''')
        result['metrics'].append(metric)
        assert metric['documentWidth'] <= width, metric
        if width <= 600:
            assert metric['cards'][0]['top'] <= 305, metric
            assert all(card['buttonHeight'] >= 44 for card in metric['cards']), metric
            assert not metric['platformVisible'], metric
        else:
            assert metric['platformVisible'], metric
        page.screenshot(path=str(out/f'square-{width}.png'))
    page.set_viewport_size({'width':390,'height':844})
    page.get_by_role('button',name='了解平台',exact=False).click()
    expect(page.locator('.overview-panel')).to_be_visible()
    page.get_by_role('button',name='打开并发演示',exact=True).click()
    expect(page.locator('.lab-panel')).to_be_visible()
    page.get_by_role('button',name='了解平台',exact=False).click()
    expect(page.locator('.lab-panel')).to_have_count(0)
    result['checks'].append('手机折叠平台保留统计、说明、并发演示且可收起')
    page.get_by_role('button',name='帮我买',exact=True).click()
    expect(page.locator('.errand-card')).to_have_count(2)
    page.get_by_role('button',name='全部任务',exact=True).click()
    page.get_by_role('textbox',name='搜索任务',exact=True).fill('矿泉水')
    expect(page.locator('.errand-card')).to_have_count(1)
    page.get_by_role('button',name='清除搜索',exact=True).click()
    expect(page.locator('.errand-card')).to_have_count(6)
    result['checks'].append('手机分类与搜索过滤正常')
    page.get_by_role('combobox',name='任务排序',exact=True).select_option('reward')
    expect(page.locator('.card-reward').first).to_contain_text('20.00')
    page.get_by_role('button',name='刷新任务',exact=True).click()
    expect(page.locator('.errand-card')).to_have_count(6)
    result['checks'].append('手机金额排序与刷新正常')
    result['missingMockRoutes'] = mock.missing
    browser.close()
(out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
