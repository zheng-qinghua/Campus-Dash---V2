"""抢单幂等恢复的浏览器验收；仅 mock API，不写后端数据。运行前启动 Vite。"""
import json
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect
from ui_smoke import BASE, IDS, MockApi


class GrabApi(MockApi):
    def __init__(self):
        super().__init__()
        self.mode = 'processing'
        self.grab_calls = []

    def route(self, route):
        req = route.request
        path = urlparse(req.url).path
        match = re.fullmatch(r'/api/errands/(\d+)/(grab|grab-requests/([a-f0-9-]+))', path)
        if not match:
            return super().route(route)
        ident, action, request_id = match.groups()
        request_id = request_id or req.headers['x-request-id']
        user = req.headers['authorization'].removeprefix('Bearer mock-')
        self.grab_calls.append({'method': req.method, 'requestId': request_id, 'userId': user,
                                'body': req.post_data_json if req.method == 'POST' else None})
        if req.method == 'POST':
            assert req.post_data_json == {'expectedRound': 0}
        status = 'PROCESSING' if self.mode == 'processing' else 'SUCCEEDED'
        route.fulfill(status=202 if status == 'PROCESSING' else 200,
                      content_type='application/json',
                      body=json.dumps({'code': 'OK', 'message': '', 'data': {
                          'code': status, 'status': status, 'grabbed': status == 'SUCCEEDED',
                          'requestId': request_id, 'appliedRound': 0,
                          'replayed': req.method == 'GET', 'candidateRank': None,
                      }}))


def main():
    results = {'mode': 'API mock 浏览器验收；不代表真实后端联调', 'checks': [], 'pageErrors': []}
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        mock = GrabApi()
        context.route('**/api/**', mock.route)
        page = context.new_page()
        page.set_default_timeout(12000)
        page.on('pageerror', lambda error: results['pageErrors'].append(str(error)))
        page.goto(BASE)
        page.locator('.identity-btn').filter(has_text='#2001').click()
        page.goto(f'{BASE}/#/detail/{IDS[0]}')
        page.get_by_role('button', name='抢单', exact=True).click()
        query_button = page.get_by_role('button', name='查询抢单结果', exact=True)
        expect(query_button).to_be_enabled()
        assert len(mock.grab_calls) == 3
        assert len({item['requestId'] for item in mock.grab_calls}) == 1
        expect(page.get_by_role('button', name='确认接单', exact=True)).to_have_count(0)
        results['checks'].append('处理中不误报成功，最多三轮自动请求且固定 ID')

        original_request_id = mock.grab_calls[0]['requestId']
        page.get_by_label('切换演示身份', exact=True).select_option('2002')
        expect(page.get_by_role('button', name='抢单', exact=True)).to_be_visible()
        expect(query_button).to_have_count(0)
        assert len(mock.grab_calls) == 3
        results['checks'].append('切换账号不读取或恢复其他跑腿的未决请求')

        mock.mode = 'success'
        mock.cards[0].update(status='LOCKED', grabberId='2001', slotTaken=1)
        page.get_by_label('切换演示身份', exact=True).select_option('2001')
        expect(page.get_by_role('button', name='确认接单', exact=True)).to_be_enabled()
        assert mock.grab_calls[-1]['requestId'] == original_request_id
        assert mock.grab_calls[-1]['method'] == 'GET'
        assert page.evaluate("Object.keys(sessionStorage).filter(k => k.startsWith('dash:pending-grab:')).length") == 0
        results['checks'].append('切回原账号查询成功并刷新详情，恢复当前确认接单操作')

        # 模拟刷新前留下的旧轮次成功请求，当前任务已再次回池。
        mock.cards[0].update(status='PUBLISHED', grabberId='-1', slotTaken=0, round=1)
        page.evaluate("""record => sessionStorage.setItem(
            `dash:pending-grab:v2:2001:${record.errandId}`, JSON.stringify(record))""",
                      {'userId': 2001, 'errandId': IDS[0], 'expectedRound': 0,
                       'requestId': original_request_id, 'createdAt': 1800000000000})
        page.reload()
        expect(page.locator('.action-msg')).to_contain_text('本次抢单曾成功')
        expect(page.get_by_role('button', name='确认接单', exact=True)).to_have_count(0)
        expect(page.get_by_role('button', name='抢单', exact=True)).to_be_enabled()
        assert mock.grab_calls[-1]['method'] == 'GET'
        assert mock.grab_calls[-1]['requestId'] == original_request_id
        results['checks'].append('刷新恢复历史成功后以最新轮次/actions 呈现，不误认为当前占有')
        assert not results['pageErrors'], results['pageErrors']
        browser.close()
    output = Path(__file__).resolve().parents[1] / 'verification' / '2026-10-20-grab-reliability'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'ui-report.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
