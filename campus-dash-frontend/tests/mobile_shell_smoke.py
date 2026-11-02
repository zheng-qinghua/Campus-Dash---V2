"""手机 App 布局与交互验收；需先启动前端，API 使用测试替身。"""
import sys,json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
FRONT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(FRONT/'tests'))
from ui_smoke import MockApi
OUT = Path(__file__).resolve().parents[1] / 'verification/2026-10-14-mobile-app/shell'
OUT.mkdir(parents=True,exist_ok=True)
report={'checks':[],'metrics':[],'errors':[]}
with sync_playwright() as p:
 b=p.chromium.launch(headless=True)
 for width,height in [(390,844),(320,740),(1440,1000)]:
  m=MockApi(); c=b.new_context(viewport={'width':width,'height':height},device_scale_factor=2 if width==390 else 1,is_mobile=width<601,has_touch=width<601,locale='zh-CN',timezone_id='Asia/Shanghai',reduced_motion='reduce')
  c.route('**/api/**',m.route)
  c.route_web_socket('**/ws**',lambda ws:ws.send(json.dumps({'type':'connected','payload':None})))
  page=c.new_page();page.on('pageerror',lambda e:report['errors'].append(str(e)))
  def measure(name):
   data=page.evaluate('''() => ({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,navTop:document.querySelector('.mobile-bottom-nav')?.getBoundingClientRect().top})''')
   assert data['scrollWidth']==width,(name,data)
   report['metrics'].append({'page':name,**data})
  def shot(name):
   page.evaluate('document.fonts.ready'); page.evaluate('document.activeElement?.blur(); window.scrollTo(0,0)');page.screenshot(path=str(OUT/(name+'.png')))
  page.goto('http://127.0.0.1:5173/');page.wait_for_load_state('networkidle')
  measure('login')
  if width<=390:
   assert page.locator('.identity-btn').last.bounding_box()['y']+page.locator('.identity-btn').last.bounding_box()['height']<height
   report['checks'].append(f'{width}px 登录四种身份首屏可见')
  if width==390:shot('05-登录页')
  page.locator('.identity-btn').filter(has_text='#2001').click();expect(page.locator('.errand-card')).to_have_count(6)
  for path,name in [('square','01-任务广场'),('publish','02-发布任务'),('wallet','03-我的钱包'),('credit','04-信用中心'),('mine','07-我的任务'),('notifications','08-消息中心')]:
   page.goto('http://127.0.0.1:5173/#/'+path);page.wait_for_load_state('networkidle');measure(path)
   if path=='publish' and width<=600:
    page.get_by_label('任务内容').fill('帮我从菜鸟驿站取一份快递，送到 6 号宿舍楼')
   if width==390:shot(name)
   if path=='publish' and width<=600:
    page.get_by_role('button',name='下一步，设置悬赏').click();page.get_by_label('悬赏金额').fill('8.50');measure('publish-step2')
    if width==390:shot('06-发布任务-设置悬赏')
   if width<=600 and path!='publish':
    expect(page.get_by_role('navigation',name='底部导航')).to_be_visible()
  if width<=600:
   page.goto('http://127.0.0.1:5173/#/square');page.wait_for_load_state('networkidle')
   opener=page.get_by_role('button',name='打开导航');opener.click();expect(page.locator('.sidebar')).to_have_class(re.compile('is-open'))
   page.keyboard.press('Escape');expect(opener).to_be_focused()
   nav=page.get_by_role('navigation',name='底部导航')
   for text,path in [('任务','mine'),('钱包','wallet'),('信用','credit'),('广场','square')]:
    nav.get_by_role('link',name=text,exact=True).click();expect(nav.get_by_role('link',name=text,exact=True)).to_have_attribute('aria-current','page');assert page.url.endswith('/'+path)
   page.locator('.notification-button').click()
   expect(page.locator('.mobile-app-title')).to_have_text('消息中心')
   profile=page.get_by_role('button',name='切换身份与个人菜单');profile.click();expect(page.locator('.sidebar')).to_have_class(re.compile('is-open'));page.keyboard.press('Escape');expect(profile).to_be_focused()
   nav.get_by_role('link',name='发布',exact=True).click();page.get_by_role('button',name='切换身份与个人菜单').click();page.wait_for_timeout(250)
   assert page.evaluate("!!document.elementFromPoint(25,innerHeight-25)?.closest('.sidebar')"),'发布按钮遮挡个人抽屉'
   page.keyboard.press('Escape')
   page.goto('http://127.0.0.1:5173/#/');page.wait_for_load_state('networkidle');expect(page.locator('.mobile-app-title h1')).to_have_text('校园跑腿')
   report['checks'].append(f'{width}px 底栏路由/高亮/头像抽屉/返回焦点/默认路由')
  else:
   expect(page.get_by_role('navigation',name='底部导航')).to_be_hidden();expect(page.locator('.mobile-app-title')).to_be_hidden();report['checks'].append('桌面保持原工作台框架')
  c.close()
 b.close()
assert not report['errors'],report['errors']
(OUT/'app-check-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False,indent=2))
