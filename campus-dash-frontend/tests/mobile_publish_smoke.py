"""手机 App 布局与交互验收；需先启动前端，API 使用测试替身。"""
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
FRONT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(FRONT / 'tests'))
from ui_smoke import MockApi
OUT = Path(__file__).resolve().parents[1] / 'verification/2026-10-14-mobile-app/publish'
OUT.mkdir(parents=True,exist_ok=True)
results = {'checks': [], 'metrics': [], 'errors': []}
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    for width, height in [(390, 844), (320, 720), (600, 844)]:
        mock = MockApi()
        ctx = browser.new_context(viewport={'width': width, 'height': height}, locale='zh-CN')
        ctx.add_init_script("localStorage.setItem('dash_token', 'mock-1001'); localStorage.setItem('dash_user', '1001');")
        ctx.route('**/api/**', mock.route)
        page = ctx.new_page()
        page.on('pageerror', lambda e: results['errors'].append(str(e)))
        page.goto('http://127.0.0.1:5173/#/publish')
        expect(page.locator('.mobile-publish')).to_be_visible()
        page.get_by_role('button', name='下一步，设置悬赏').click()
        expect(page.get_by_role('alert')).to_contain_text('请描述一下')
        assert not mock.publishes
        page.get_by_label('任务内容').fill('  帮我从三食堂带一份晚饭到 6 号楼  ')
        page.get_by_role('button', name='帮我买', exact=True).click()
        page.screenshot(path=str(OUT / f'publish-step1-{width}.png'))
        results['metrics'].append({'width': width, 'step': 1, **page.evaluate('''() => ({height:document.documentElement.scrollHeight, viewport:innerHeight, scrollWidth:document.documentElement.scrollWidth, mainBottom:document.querySelector('.mobile-publish-description').getBoundingClientRect().bottom, ctaTop:document.querySelector('.mobile-publish-bottom').getBoundingClientRect().top})''')})
        page.get_by_role('button', name='下一步，设置悬赏').click()
        expect(page.get_by_label('悬赏金额')).to_be_visible()
        expect(page.locator('.mobile-publish-summary')).to_contain_text('帮我从三食堂')
        page.get_by_label('悬赏金额').fill('12.345')
        page.get_by_role('button', name='发布并托管').click()
        expect(page.get_by_role('alert')).to_contain_text('最多保留两位小数')
        assert not mock.publishes
        page.get_by_label('悬赏金额').fill('12.34')
        page.get_by_role('button', name='上一步', exact=True).click()
        expect(page.get_by_label('任务内容')).to_have_value('  帮我从三食堂带一份晚饭到 6 号楼  ')
        expect(page.get_by_role('button', name='帮我买', exact=True)).to_have_attribute('aria-pressed', 'true')
        page.get_by_role('button', name='下一步，设置悬赏').click()
        expect(page.get_by_label('悬赏金额')).to_have_value('12.34')
        page.evaluate('document.activeElement.blur()')
        page.screenshot(path=str(OUT / f'publish-step2-{width}.png'))
        results['metrics'].append({'width': width, 'step': 2, **page.evaluate('''() => ({height:document.documentElement.scrollHeight, viewport:innerHeight, scrollWidth:document.documentElement.scrollWidth, mainBottom:document.querySelector('.mobile-publish-escrow-note').getBoundingClientRect().bottom, ctaTop:document.querySelector('.mobile-publish-bottom').getBoundingClientRect().top})''')})
        page.locator('form').evaluate('form => {form.requestSubmit();form.requestSubmit();}')
        expect(page.get_by_role('heading', name='发布成功', exact=True)).to_be_visible()
        assert len(mock.publishes) == 1, mock.publishes
        assert mock.publishes[0] == {'title': '帮我从三食堂带一份晚饭到 6 号楼', 'rewardCents': 1234, 'slotTotal': 1, 'type': 'BUY'}, mock.publishes
        page.screenshot(path=str(OUT / f'publish-success-{width}.png'))
        page.get_by_role('button', name='再发一个任务').click()
        expect(page.get_by_label('任务内容')).to_have_value('')
        expect(page.get_by_role('button', name='下一步，设置悬赏')).to_be_visible()
        results['checks'].append(f'{width}px: 内容校验、两步往返保留数据、小数金额、防重复、成功后重发通过')
        ctx.close()
    ctx = browser.new_context(viewport={'width':1440,'height':1000})
    ctx.add_init_script("localStorage.setItem('dash_token', 'mock-1001'); localStorage.setItem('dash_user', '1001');")
    ctx.route('**/api/**', MockApi().route)
    page = ctx.new_page()
    page.goto('http://127.0.0.1:5173/#/publish')
    expect(page.locator('.task-compose')).to_be_visible()
    expect(page.get_by_label('任务内容')).to_be_visible()
    expect(page.get_by_label('悬赏金额')).to_be_visible()
    assert page.locator('.mobile-publish').count() == 0
    results['checks'].append('1440px: 桌面完整表单保持')
    browser.close()
assert not results['errors'], results['errors']
assert all(m['scrollWidth'] == m['width'] for m in results['metrics']), results['metrics']
assert all(m['mainBottom'] < m['ctaTop'] for m in results['metrics']), results['metrics']
(OUT / 'report.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps(results, ensure_ascii=False, indent=2))
