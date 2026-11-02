"""手机导航抽屉焦点回归；需先启动前端，API 与 WebSocket 使用测试替身。

运行：python3 tests/mobile_drawer_smoke.py
每个视口和动画偏好均使用全新会话，覆盖第一次展开的可见性过渡。
"""
import argparse
import json
import traceback
from pathlib import Path

from playwright.sync_api import expect, sync_playwright
from ui_smoke import MockApi


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:5173')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'verification/2026-10-14-mobile-app/focus-report.json')
    args = parser.parse_args()
    report = {
        'mode': '浏览器焦点回归；API 与 WebSocket 为测试替身',
        'cases': [],
        'pageErrors': [],
        'passed': False,
    }
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            for reduced_motion in ['no-preference', 'reduce']:
                for width in [390, 600, 601, 800]:
                    case = {'width': width, 'reducedMotion': reduced_motion, 'checks': [], 'passed': False}
                    report['cases'].append(case)
                    mock = MockApi()
                    context = browser.new_context(viewport={'width': width, 'height': 844}, reduced_motion=reduced_motion, locale='zh-CN')
                    context.route('**/api/**', mock.route)
                    context.route_web_socket('**/ws**', lambda ws: ws.send(json.dumps({'type': 'connected', 'payload': None})))
                    context.add_init_script("localStorage.setItem('dash_token','mock-2001');localStorage.setItem('dash_user','2001')")
                    page = context.new_page()
                    page.on('pageerror', lambda error: report['pageErrors'].append(str(error)))
                    page.goto(f'{args.base_url.rstrip("/")}/#/square')
                    expect(page.locator('.errand-card')).to_have_count(6)
                    page.wait_for_timeout(300)
                    opener = page.get_by_role('button', name='打开导航', exact=True)
                    close = page.get_by_role('button', name='关闭导航', exact=True)
                    logout = page.get_by_role('button', name='退出登录', exact=True)
                    background = page.locator('.notification-button')

                    # 保留原复现顺序与等待时间，避免二次展开掩盖首次移焦失败。
                    opener.click()
                    page.wait_for_timeout(500)
                    case['firstFocus'] = page.evaluate("document.activeElement.getAttribute('aria-label')")
                    assert case['firstFocus'] == '关闭导航', case
                    assert page.evaluate('document.body.style.overflow') == 'hidden'
                    case['checks'].append('首次展开在 500ms 内将焦点移入抽屉并锁定背景滚动')

                    page.keyboard.press('Tab')
                    expect(page.locator('.sidebar .brand')).to_be_focused()
                    page.keyboard.press('Shift+Tab')
                    expect(close).to_be_focused()
                    page.keyboard.press('Shift+Tab')
                    expect(logout).to_be_focused()
                    page.keyboard.press('Tab')
                    expect(close).to_be_focused()
                    case['checks'].append('Tab 与 Shift+Tab 在抽屉内顺序移动且首尾循环')

                    # 模拟外部组件主动抢走焦点，验证下一次 Tab 仍能恢复到抽屉。
                    background.focus()
                    expect(background).to_be_focused()
                    page.keyboard.press('Tab')
                    expect(close).to_be_focused()
                    background.focus()
                    page.keyboard.press('Shift+Tab')
                    expect(logout).to_be_focused()
                    case['checks'].append('焦点意外离开抽屉后，正向与反向 Tab 都能拉回')

                    page.keyboard.press('Escape')
                    expect(opener).to_be_focused()
                    expect(opener).to_have_attribute('aria-expanded', 'false')
                    expect(page.locator('.nav-scrim')).to_have_count(0)
                    expect(page.locator('.sidebar')).to_be_hidden()
                    assert page.evaluate('document.body.style.overflow') != 'hidden'
                    case['checks'].append('Escape 关闭抽屉、恢复入口焦点与背景滚动')
                    assert not mock.missing, mock.missing
                    case['passed'] = True
                    context.close()
            browser.close()
        assert not report['pageErrors'], report['pageErrors']
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        report['traceback'] = traceback.format_exc()
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
