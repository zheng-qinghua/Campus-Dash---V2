#!/usr/bin/env python3
"""在独立 DB/Redis/RocketMQ 中注入依赖及进程故障，验证恢复与资金守恒。

使用已有 dash-mysql 与已打包的 Java JAR；只回收本轮容器和 Java 子进程，
保留验收数据库及日志。业务动作经真实 HTTP/MQ 执行，不改写业务状态和截止时间。
"""
import argparse, concurrent.futures, hashlib, json, os, queue, re, secrets, socket, subprocess, sys, threading, time, traceback, uuid
from urllib.request import Request
from urllib.error import HTTPError
from fault_injection import LostCommitAckProxy, LostHttpResponseProxy
from datetime import datetime
from pathlib import Path
from urllib.request import urlopen
from secret_env import database_environment, isolated_redis_environment, redact_environment_secrets

database_env=database_environment()
application_env=isolated_redis_environment(database_env)

root=Path(__file__).resolve().parents[1]; front=root.parent/'campus-dash-frontend'
run=datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+secrets.token_hex(2)
out=root/'verification/fault-recovery'/run; out.mkdir(parents=True)
db='campus_dash_verify_fault_'+run.replace('-','_')
prefix='dash-fault-'+run.lower(); network=prefix; containers=[]; processes=[]; streams=[]
resource_label='com.campusdash.verification.run'
report={'runId':run,'database':db,'startedAt':datetime.now().isoformat(),'passed':False,'checks':[], 'environment':{'mysql':'8.0.40','redis':'7.4','rocketmq':'5.3.1','confirmSeconds':30,'autoSettleSeconds':45,'scanIntervalMillis':5000,'redisTimeoutMillis':1000,'brokerTransactionCheckMillis':5000,'defaultRunnerRateLimits':True},'logs':{}}
(out/'launcher.py').write_bytes(Path(__file__).read_bytes())
(out/'secret_env.py').write_bytes((root/'scripts/secret_env.py').read_bytes())
(out/'fault_injection.py').write_bytes((root/'scripts/fault_injection.py').read_bytes())

def command(args, **kwargs):
    result=subprocess.run(args,text=True,capture_output=True,**kwargs)
    if result.returncode: raise RuntimeError(redact_environment_secrets(str(args)+': '+result.stderr[-3000:]+result.stdout[-3000:]))
    return result.stdout.strip()
def sql(query, database=db):
    args=['docker','exec','-e','MYSQL_PWD','-i','dash-mysql','mysql','-uroot','--default-character-set=utf8mb4','-N','-B']
    if database: args.append(database)
    return command(args,input="SET time_zone='+08:00';\n"+query,timeout=30,env=database_env)
def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0));return s.getsockname()[1]
def until(predicate, seconds=90):
    stop=time.monotonic()+seconds
    while time.monotonic()<stop:
        for p in processes:
            if p.poll() is not None: raise RuntimeError('Java process stopped: '+str(p.pid))
        try:
            if predicate(): return
        except (OSError, ValueError): pass
        time.sleep(.25)
    raise TimeoutError('readiness timed out')
def listening(p):
    with socket.create_connection(('127.0.0.1',p),timeout=1):return True
def check(name, valid, evidence):
    report['checks'].append({'name':name,'passed':bool(valid),'evidence':evidence})
    print(('PASS ' if valid else 'FAIL ')+name,flush=True)
    if not valid: raise AssertionError(name+': '+str(evidence))
def launch(name, args):
    log=out/(name+'.log');stream=log.open('w');streams.append(stream)
    p=subprocess.Popen(args,stdout=stream,stderr=subprocess.STDOUT,env=application_env);processes.append(p)
    report['logs'][name]=str(log);return p,log


def cleanup_command(args, timeout=20):
    """清理命令单独记录结果，某一步失败不能阻断其余本轮资源的回收。"""
    try:
        result=subprocess.run(args,text=True,capture_output=True,timeout=timeout)
        return {'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr}
    except subprocess.TimeoutExpired as error:
        def decoded(value):
            return value.decode(errors='replace') if isinstance(value,bytes) else (value or '')
        return {'exitCode':None,'stdout':decoded(error.stdout),
                'stderr':decoded(error.stderr)+f'\n清理命令超过 {timeout} 秒'}
    except Exception as error:
        return {'exitCode':None,'stdout':'','stderr':str(error)}


def inspect_resource(kind, name):
    args=['docker','inspect','--type','container',name] if kind=='container' else ['docker','network','inspect',name]
    result=cleanup_command(args)
    if result['exitCode']==0:
        try:
            item=json.loads(result['stdout'])[0]
            labels=(item.get('Config',{}).get('Labels') if kind=='container' else item.get('Labels')) or {}
            return {'exists':True,'ownerRunId':labels.get(resource_label),'error':None}
        except (ValueError,IndexError,KeyError,TypeError,AttributeError) as error:
            return {'exists':None,'ownerRunId':None,'error':'无法解析资源归属：'+str(error)}
    # daemon 失联/权限错误不算已删除；只有明确的“不存在”响应才确认资源消失。
    missing=bool(re.search(r'no such (?:object|container|network)|network .+ not found',
                           result['stderr']+'\n'+result['stdout'],re.IGNORECASE))
    return {'exists':False if missing else None,'ownerRunId':None,
            'error':None if missing else result['stderr'] or '资源检查失败'}


def cleanup_docker_resource(kind, name):
    entry={'name':name,'kind':kind,'before':inspect_resource(kind,name),'removed':False}
    before=entry['before']
    if before['exists'] is False:
        entry.update(removed=True,alreadyAbsent=True)
        return entry
    if before['exists'] is not True or before['ownerRunId']!=run:
        entry['error']=before['error'] or '资源归属不属于本轮，拒绝删除'
        return entry
    if kind=='container':
        logs=cleanup_command(['docker','logs',name])
        try:
            (out/(name+'.log')).write_text(logs['stdout']+logs['stderr'])
        except Exception as error:
            entry['logWriteError']=str(error)
        entry['logsExitCode']=logs['exitCode']
        if not name.endswith('-redis'):
            copied=cleanup_command(['docker','cp',name+':/home/rocketmq/logs/rocketmqlogs',str(out/(name+'-logs'))])
            entry['brokerLogsExitCode']=copied['exitCode']
        removal=cleanup_command(['docker','rm','-f',name])
    else:
        removal=cleanup_command(['docker','network','rm',name])
    entry['removal']=removal
    entry['after']=inspect_resource(kind,name)
    # 删除返回值和最终不存在的检查都必须通过，不能只打印“清理完成”。
    entry['removed']=removal['exitCode']==0 and entry['after']['exists'] is False
    if not entry['removed']:
        entry['error']=removal['stderr'] or entry['after']['error'] or '资源删除后仍存在'
    return entry


def cleanup_resources():
    cleanup={'java':[],'containers':[],'network':None,'errors':[],'passed':False}
    for process in processes:
        entry={'pid':process.pid,'stopped':False}
        try:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            entry['stopped']=process.poll() is not None
            entry['exitCode']=process.poll()
        except Exception as error:
            entry['error']=str(error)
        if not entry['stopped']:cleanup['errors'].append('Java 进程未停止：'+str(process.pid))
        cleanup['java'].append(entry)
    for stream in streams:
        try:stream.close()
        except Exception as error:cleanup['errors'].append('日志关闭失败：'+str(error))
    for container in reversed(containers):
        entry=cleanup_docker_resource('container',container)
        cleanup['containers'].append(entry)
        if not entry['removed']:cleanup['errors'].append(container+'：'+entry['error'])
    cleanup['network']=cleanup_docker_resource('network',network)
    if not cleanup['network']['removed']:cleanup['errors'].append(network+'：'+cleanup['network']['error'])
    cleanup['passed']=not cleanup['errors']
    return cleanup

parser=argparse.ArgumentParser(description='独立环境真实故障注入：只操作本轮资源，保留数据库和证据')
parser.add_argument('--baseline-probe',action='store_true',help='使用旧JAR记录待修缺陷，预期非0退出')
args=parser.parse_args()
report.update(requests=[],events=[],tasks={},mqSends=[],baselineProbe=args.baseline_probe)
tokens={};active={};generations={'api':0,'worker':0};locks=[];proxies=[]
executor=concurrent.futures.ThreadPoolExecutor(max_workers=24)

def event(name, **detail):
    item={'at':datetime.now().isoformat(),'name':name,**detail}
    report['events'].append(item);print(json.dumps(item,ensure_ascii=False),flush=True)

def wait_for(name, predicate, seconds=90):
    deadline=time.monotonic()+seconds
    last=None
    while time.monotonic()<deadline:
        try:
            last=predicate()
            if last:return last
        except (OSError,ValueError) as error:last=str(error)
        time.sleep(.3)
    raise TimeoutError(name+': '+str(last))

def request(method,path,actor=None,data=None,request_id=None,base=None,timeout=100):
    headers={'Content-Type':'application/json'}
    if actor is not None:headers['Authorization']='Bearer '+tokens[actor]
    if request_id:headers['X-Request-Id']=request_id
    req=Request((base or api_base)+path, data=json.dumps(data).encode() if data is not None else (b'' if method=='POST' else None),headers=headers,method=method)
    start=time.monotonic()
    try:
        try:response=urlopen(req,timeout=timeout)
        except HTTPError as error:response=error
        with response:
            raw=response.read();status=response.status;retry=response.headers.get('Retry-After')
        try:body=json.loads(raw)
        except ValueError:body={'code':'EMPTY_OR_INVALID_BODY'}
        item={'method':method,'path':path,'actor':actor,'requestId':request_id,'http':status,'code':body.get('code'),'retryAfter':retry,'elapsedMs':round((time.monotonic()-start)*1000)}
        if not path.startswith('/api/auth/'):item['data']=body.get('data')
        report['requests'].append(item)
        return status,body,retry
    except (OSError,TimeoutError) as error:
        report['requests'].append({'method':method,'path':path,'actor':actor,'requestId':request_id,'transportError':type(error).__name__})
        return 0,{'code':'TRANSPORT_ERROR','message':str(error)},None

def ok(method,path,actor,data=None):
    status,body,_=request(method,path,actor,data)
    assert status==200 and body.get('code')=='OK',(path,status,body)
    return body['data']

def login():
    for actor in (11001,12001,12002,12003):
        def attempt():
            status,body,_=request('POST','/api/auth/login',data={'userId':actor})
            if status==200 and body.get('code')=='OK':tokens[actor]=body['data']['token'];return True
            return False
        wait_for('Redis恢复后可登录',attempt,30)

def start_service(name,scan=5000):
    generations[name]+=1
    extra=['--spring.profiles.active=local',f"--server.port={ports['api']}",'--server.address=127.0.0.1','--dash.worker-id=41',f'--dash.auth.session-namespace={run}'] if name=='api' else ['--dash.worker-id=42','--logging.level.com.campusdash.worker.TimeoutTransferConsumer=DEBUG']
    jdbc_port=mysql_proxy.port if name=='api' else 3307
    jdbc=f'--spring.datasource.url=jdbc:mysql://127.0.0.1:{jdbc_port}/{db}?useSSL=false&connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true'
    process,log=launch(f'{name}-{generations[name]}',[*java,str(jars[name]),*shared,jdbc,f'--dash.timeout.scan-interval-ms={scan}',f'--dash.settle.scan-interval-ms={scan}',*extra])
    active[name]=(process,log)
    def ready():
        if process.poll() is not None:raise RuntimeError(name+'启动退出 '+str(process.poll()))
        if name=='api':
            try:
                with urlopen(api_base+'/api/health',timeout=2) as r:return json.load(r).get('code')=='OK'
            except OSError:return False
        return 'Started WorkerApplication' in log.read_text()
    wait_for(name+'就绪',ready,100);event('服务启动',service=name,pid=process.pid,log=str(log),scanMillis=scan)

def kill_service(name):
    process,_=active.pop(name);process.kill();process.wait(timeout=10)
    event('SIGKILL服务',service=name,pid=process.pid,exitCode=process.returncode)

def container_action(action,suffix):
    name=prefix+'-'+suffix
    inspected=inspect_resource('container',name)
    assert inspected['ownerRunId']==run,(name,inspected)
    command(['docker',action,name],timeout=30)
    event('容器故障操作',action=action,container=name)

def redis(*parts):return command(['docker','exec',prefix+'-redis','redis-cli','--raw',*map(str,parts)],timeout=5)
def redis_ready():
    try:return redis('PING')=='PONG'
    except RuntimeError:return False

def state(ident):
    row=sql(f'SELECT status,round,version,slot_taken,COALESCE(grabber_id,-1) FROM errand WHERE id={int(ident)}').split('\t')
    return {'status':row[0],'round':int(row[1]),'version':int(row[2]),'slot':int(row[3]),'owner':int(row[4])}
def count(query):return int(sql(query))
def wait_status(ident,status,seconds=100):return wait_for(f'{ident}->{status}',lambda:state(ident)['status']==status,seconds)
def publish(label,cents=1000):
    ident=ok('POST','/api/errands',11001,{'title':'故障验收 '+run+' '+label,'rewardCents':cents,'slotTotal':1,'type':'DELIVERY'})['errandId']
    assert isinstance(ident,str) and int(ident)>2**53
    report['tasks'][label]={'id':ident,'reward':cents,'expected':'SETTLED'}
    sql(f"INSERT INTO bench_run_item(run_id,entity_type,entity_id) VALUES('{run}','ERRAND',{int(ident)});")
    return ident

def grab(ident,actor,request_id=None,round_=None):
    request_id=request_id or str(uuid.uuid4());round_=state(ident)['round'] if round_ is None else round_
    for _ in range(8):
        status,body,retry=request('POST',f'/api/errands/{ident}/grab',actor,{'expectedRound':round_},request_id)
        if status==429:
            assert retry and int(retry)>0 and body['code']=='GRAB_RATE_LIMITED'
            time.sleep(int(retry));continue
        return status,body,request_id
    raise AssertionError('限流退避未恢复')
def win(ident,actor=12001):
    status,body,rid=grab(ident,actor)
    assert status==200 and body['data']['status']=='SUCCEEDED',(status,body)
    return rid

def action(ident,name,actor):
    data={'expectedRound':state(ident)['round']} if name=='confirm' else None
    return ok('POST',f'/api/errands/{ident}/{name}',actor,data)
def delivered(ident,actor=12001):
    rid=win(ident,actor)
    for name in ('confirm','pickup','deliver'):action(ident,name,actor)
    return rid

def cancel(ident):
    result=action(ident,'cancel',11001)
    assert result['result'] in ('REFUNDED','ALREADY_REFUNDED'),result
    for task in report['tasks'].values():
        if task['id']==ident:task['expected']='CANCELLED'

def ledger_snapshot(ident):
    return sql(f"SELECT biz_no,account_id,direction,amount FROM wallet_ledger WHERE ref_id={int(ident)} ORDER BY biz_no,account_id,direction")
def worker_logs():return '\n'.join(path.read_text(errors='replace') for path in out.glob('worker-*.log'))
def send_messages(topic,key,payload,count_=5,tag=None,mode='delay',delay=1000):
    args=[sys.executable,str(root/'scripts/publish-fault-messages.py'),'--endpoints',f"127.0.0.1:{ports['mq']}",'--topic',topic,'--key',key,'--payload',json.dumps(payload,separators=(',',':')),'--count',str(count_),'--mode',mode]
    if mode=='delay':args+=['--delay-ms',str(delay)]
    if tag:args+=['--tag',tag]
    result=command(args,timeout=100)
    filename=out/f'mq-send-{len(report["mqSends"])+1}.json';filename.write_text(result)
    receipt=json.loads(result);report['mqSends'].append({'topic':topic,'key':key,'count':count_,'evidence':str(filename),'receipt':receipt})
    event('重复消息已实际投递',topic=topic,key=key,count=count_)

def hold_order(ident):
    args=['docker','exec','-e','MYSQL_PWD',prefix_mysql,'mysql','-uroot','--unbuffered','-N','-B',db,'-e',f"START TRANSACTION; SELECT id FROM errand WHERE id={int(ident)} FOR UPDATE; SELECT CONCAT('LOCKED:',CONNECTION_ID()); SELECT SLEEP(180); ROLLBACK;"]
    proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=database_env)
    messages=queue.Queue()
    def read():
        for line in proc.stdout:messages.put(line.strip())
    threading.Thread(target=read,daemon=True).start()
    end=time.monotonic()+15
    while time.monotonic()<end:
        line=messages.get(timeout=15)
        if line.startswith('LOCKED:'):
            connection=int(line.split(':')[1]);locks.append((proc,connection));event('仅持有订单行锁',errandId=ident,connectionId=connection);return connection
    raise TimeoutError('订单锁未获取')
def release_lock(connection):
    sql(f'KILL CONNECTION {connection};');event('释放本轮行锁连接',connectionId=connection)

def repeat_settle_race(ident):
    gate=threading.Barrier(12)
    def attempt(_):gate.wait();return request('POST',f'/api/errands/{ident}/settle',11001)
    outcomes=list(executor.map(attempt,range(12)))
    values=[(b.get('data') or {}).get('result') for _,b,_ in outcomes]
    assert all(s==200 and b.get('code')=='OK' for s,b,_ in outcomes),outcomes
    check('并发重复结算至多一次实际入账 '+ident,values.count('SETTLED')<=1,values)


def baseline_probe():
    ident=publish('Redis鉴权故障基线')
    container_action('kill','redis')
    status,body,retry=request('POST',f'/api/errands/{ident}/grab',12001,{'expectedRound':0},str(uuid.uuid4()))
    report['baselineRedis']={'http':status,'body':body,'retryAfter':retry,'state':state(ident)}
    container_action('start','redis');wait_for('Redis重启',redis_ready,20);login();cancel(ident)
    auto=publish('提前自动结算消息基线');delivered(auto)
    send_messages('errand-auto-settle',f'autosettle:{auto}',{'errandId':int(auto)},3)
    wait_status(auto,'SETTLED',25)
    row=sql(f"SELECT TIMESTAMPDIFF(MICROSECOND,DATE_ADD(e.delivered_at,INTERVAL 45 SECOND),s.created_at) FROM errand e JOIN errand_status_log s ON s.errand_id=e.id AND s.to_status='SETTLED' WHERE e.id={int(auto)}")
    report['baselineEarlyAuto']={'deltaMicros':int(row),'state':state(auto),'consumerLines':[x for x in worker_logs().splitlines() if auto in x and 'AutoSettleConsumer' in x]}
    event('旧JAR缺陷已真实重现',redisHttp=status,autoSettleBeforeDeadline=int(row)<0)
    raise AssertionError('基线缺陷已重现：Redis鉴权500和自动结算消息提前结算，等待修复后完整复跑')


def full_scenarios():
    # 只占用本轮 Redis：无写入 Lua 超过执行阈值，真实命中 BUSY，随后 SCRIPT KILL 恢复。
    original_limit=redis('CONFIG','GET','lua-time-limit').splitlines()[-1]
    redis('CONFIG','SET','lua-time-limit','100')
    busy_script=subprocess.Popen(['docker','exec',prefix+'-redis','redis-cli','--raw','EVAL',
                                  'while true do end','0'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        wait_for('Redis进入真实BUSY',lambda:'BUSY' in redis('PING'),10)
        event('Redis只读脚本触发BUSY')
        status,body,retry=request('GET','/api/wallet',12001)
        check('Redis BUSY鉴权明确退避',status==503 and body.get('code')=='DEPENDENCY_UNAVAILABLE'
              and retry is not None and int(retry)>0,{'http':status,'body':body,'retryAfter':retry})
    finally:
        redis('SCRIPT','KILL')
        busy_script.communicate(timeout=10)
        redis('CONFIG','SET','lua-time-limit',original_limit)
    status,body,_=request('GET','/api/wallet',12001)
    check('Redis BUSY恢复后原Session继续可用',status==200 and body.get('code')=='OK',{'http':status,'body':body})

    # 先验证提前和重复消息均不能越过数据库截止时间。
    auto=publish('提前及重复自动结算');delivered(auto)
    send_messages('errand-auto-settle',f'autosettle:{auto}',{'errandId':int(auto)},5)
    wait_for('提前自动消息已消费',lambda:sum(f'errandId={auto} result=SKIPPED' in x and 'AutoSettleConsumer' in x for x in worker_logs().splitlines())>=5,25)
    check('提前自动结算消息不动资金',state(auto)['status']=='DELIVERED' and count(f"SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='settle:{auto}'")==0,state(auto))
    wait_status(auto,'SETTLED',70);before=ledger_snapshot(auto)
    send_messages('errand-auto-settle',f'autosettle:{auto}',{'errandId':int(auto)},10)
    wait_for('自动结算重复消费完成',lambda:sum(f'errandId={auto} result=SKIPPED' in x and 'AutoSettleConsumer' in x for x in worker_logs().splitlines())>=15,30)
    repeat_settle_race(auto)
    check('重复自动消息及并发手动重试不重复入账',ledger_snapshot(auto)==before,before)
    amount=950
    send_messages('errand-fund-event',f'settle:{auto}',{'bizNo':f'settle:{auto}','type':'SETTLED','errandId':int(auto),'publisherId':11001,'runnerId':12001,'amountCents':amount,'commissionCents':50},10,tag='SETTLED',mode='transaction')
    wait_for('资金事件重复实际消费',lambda:sum(f'msgKey=settle:{auto} ' in x and '资金事件已消费' in x for x in worker_logs().splitlines())>=11,45)
    check('重复资金事件只落两条通知且账本不变',count(f"SELECT COUNT(*) FROM notification WHERE msg_key IN ('settle:{auto}:pub','settle:{auto}:runner')")==2 and ledger_snapshot(auto)==before,{'ledger':before})

    # 保留已确认订单用于验证 Redis 数据丢失不会丢失持久赢家。
    stable=publish('Redis丢失后已确认赢家');stable_id=win(stable);action(stable,'confirm',12001)
    releasing=publish('Redis停机期间到期回池');win(releasing)
    retry_task=publish('Redis恢复后原UUID重试');retry_id=str(uuid.uuid4())
    old_token=tokens[12001]
    container_action('kill','redis')
    response=request('POST',f'/api/errands/{retry_task}/grab',12001,{'expectedRound':0},retry_id)
    status,body,retry=response
    check('Redis不可用时鉴权关闭并明确退避',status==503 and body.get('code')=='DEPENDENCY_UNAVAILABLE' and retry is not None and int(retry)>0,{'http':status,'body':body,'retryAfter':retry})
    check('Redis故障拒绝请求未占订单',state(retry_task)['status']=='PUBLISHED' and count(f"SELECT COUNT(*) FROM grab_request WHERE request_id='{retry_id}'")==0,state(retry_task))
    wait_status(releasing,'PUBLISHED',100)
    check('Redis停机期间DB兜底释放名额',state(releasing)['round']==1 and state(releasing)['slot']==0,state(releasing))
    container_action('start','redis');wait_for('Redis恢复',redis_ready,30)
    wait_for('API连接恢复',lambda:request('GET','/api/wallet',12001)[0] in (401,200),30)
    expired_status=request('GET','/api/wallet',12001)[0]
    check('Redis清空后旧Session失效而非冒用',expired_status==401,expired_status)
    login()
    # 新发布会先创建只含新订单的 Bloom；此时旧订单详情仍必须以数据库为准。
    fresh=publish('Redis清空后新单与旧单详情')
    old_detail=ok('GET',f'/api/errands/{stable}',12001)
    mine=ok('GET','/api/errands/mine?role=GRABBED_BY_ME',12001)
    check('Redis部分Bloom不能隐藏旧订单',old_detail['id']==stable and old_detail['status']=='ACCEPTED'
          and old_detail['round']==state(stable)['round'] and str(old_detail['grabberId'])=='12001'
          and 'PICKUP' in old_detail['availableActions'] and any(item['id']==stable for item in mine),
          {'detail':old_detail,'mineIds':[item['id'] for item in mine]})
    # 模拟读请求在发布提交后才迟到回填负缓存；真实详情存在性不可由它否决。
    stale_empty=json.dumps({'exp':int(time.time()*1000)+60000,'empty':True,'data':None},separators=(',',':'))
    for shard in range(4):redis('SET',f'errand:detail:{stable}:{shard}',stale_empty,'EX','60')
    event('注入迟到负缓存',errandId=stable,shards=4)
    visible=ok('GET',f'/api/errands/{stable}',12001)
    check('迟到负缓存不遮挡已提交订单',visible['id']==stable and visible['status']=='ACCEPTED'
          and 'PICKUP' in visible['availableActions'],visible)
    cancel(fresh)
    status,result,_=request('GET',f'/api/errands/{stable}/grab-requests/{stable_id}',12001)
    check('Redis重启后原请求结果可从DB恢复',status==200 and result.get('data',{}).get('status')=='SUCCEEDED',result)
    loser_status,loser,_=grab(stable,12002)
    check('Redis投影丢失不导致重复接单',loser_status==409 and not loser['data']['grabbed'] and state(stable)['owner']==12001,loser)
    wait_for('Redis已确认投影自动恢复',lambda:redis('HGET',f'dash:grab:v2:{{{stable}}}:state','status')=='ACCEPTED',90)
    check('Redis重建保留已确认赢家',redis('HGET',f'dash:grab:v2:{{{stable}}}:state','ownerRunnerId')=='12001',state(stable))
    for name in ('pickup','deliver'):action(stable,name,12001)
    action(stable,'settle',11001);cancel(releasing)
    status,result,_=grab(retry_task,12001,retry_id,0)
    check('依赖恢复后使用原UUID可正常抢单',status==200 and result['data']['status']=='SUCCEEDED',result)
    for name in ('confirm','pickup','deliver'):action(retry_task,name,12001)
    action(retry_task,'settle',11001)

    # 真进程 SIGKILL：用只读行锁固定在持久登记、Redis占位之后、DB业务提交之前。
    crashed=publish('API占位后提交前宕机');crash_id=str(uuid.uuid4());connection=hold_order(crashed)
    future=executor.submit(request,'POST',f'/api/errands/{crashed}/grab',12001,{'expectedRound':0},crash_id)
    wait_for('PROCESSING和Redis待提交占位同时存在',lambda:count(f"SELECT COUNT(*) FROM grab_request WHERE request_id='{crash_id}' AND status='PROCESSING'")==1 and bool(redis('HGET',f'dash:grab:v2:{{{crashed}}}:state','reservationToken')),15)
    pending=sql(f"SELECT id,reservation_token,lease_until FROM grab_request WHERE request_id='{crash_id}'").split('\t')
    event('确认宕机窗口',errandId=crashed,requestId=crash_id,pending=pending)
    kill_service('api');release_lock(connection);future.result(timeout=10)
    start_service('api');login()
    wait_for('租约到期请求自动EXPIRED',lambda:sql(f"SELECT status FROM grab_request WHERE request_id='{crash_id}'")=='EXPIRED',60)
    wait_for('Redis精确占位撤销',lambda:not redis('HGET',f'dash:grab:v2:{{{crashed}}}:state','reservationToken'),50)
    status,replayed,_=grab(crashed,12001,crash_id,0)
    check('宕机原UUID不会复活',status==409 and replayed['data']['status']=='EXPIRED',replayed)
    check('提交前宕机没有成功占用事实',count(f'SELECT COUNT(*) FROM grab_record WHERE errand_id={crashed}')==0,state(crashed))
    delivered(crashed,12002);action(crashed,'settle',11001)
    check('租约恢复后新请求恰好一个接单事实',count(f'SELECT COUNT(*) FROM grab_record WHERE errand_id={crashed}')==1,state(crashed))

    lost=publish('API提交后响应丢失与重启');lost_id=str(uuid.uuid4());http_proxy=LostHttpResponseProxy(api_base);proxies.append(http_proxy)
    future=executor.submit(request,'POST',f'/api/errands/{lost}/grab',12001,{'expectedRound':0},lost_id,f'http://127.0.0.1:{http_proxy.port}')
    assert http_proxy.received.wait(25),'上游成功响应未到代理'
    check('提交后响应在代理被截断',http_proxy.result['http']==200 and json.loads(http_proxy.result['body'])['data']['status']=='SUCCEEDED',http_proxy.result)
    kill_service('api');http_proxy.release.set();future.result(timeout=10);start_service('api');login()
    status,replayed,_=grab(lost,12001,lost_id,0)
    check('提交后宕机重放原UUID仍是同一赢家',status==200 and replayed['data']['replayed'] and count(f'SELECT COUNT(*) FROM grab_record WHERE errand_id={lost}')==1,replayed)
    latest=state(lost);lost_runner=12001
    if latest['status']=='PUBLISHED':
        # 历史成功不代表重启后仍持有过期占位；按新轮次显式重新抢单。
        event('响应恢复时旧占位已合法到期',errandId=lost,state=latest)
        win(lost,12002);lost_runner=12002
    else:
        assert latest['status']=='LOCKED' and latest['owner']==12001,latest
    for name in ('confirm','pickup','deliver'):action(lost,name,lost_runner)
    action(lost,'settle',11001)

    # 数据库真正提交，但代理丢弃 COMMIT OK；半消息应由真实 Broker 回查恢复。
    ack=publish('数据库提交ACK丢失');delivered(ack);mysql_proxy.arm('settle:'+ack)
    status,body,_=request('POST',f'/api/errands/{ack}/settle',11001)
    check('真实MySQL COMMIT成功ACK被截断',mysql_proxy.dropped.is_set() and state(ack)['status']=='SETTLED',{'http':status,'body':body,'proxy':mysql_proxy.observed})
    wait_for('资金半消息经真实Broker回查后被消费',lambda:count(f"SELECT COUNT(*) FROM notification WHERE msg_key IN ('settle:{ack}:pub','settle:{ack}:runner')")==2,150)
    # API 与 worker 都注册资金事务 checker，Broker 可将回查交给任一在线生产者。
    check_lines=[{'log':path.name,'line':line}
                 for pattern in ('api-*.log','worker-*.log') for path in out.glob(pattern)
                 for line in path.read_text(errors='replace').splitlines()
                 if f'bizNo=settle:{ack} ' in line and 'RocketMqFundEventAdapter' in line
                 and '事务消息回查' in line and '流水存在=true' in line]
    check('提交不确定资金事件未被误回滚',bool(check_lines),{'errandId':ack,'primaryChecks':check_lines})
    repeat_settle_race(ack)

    # Broker真正停机，使用DB兜底回池；到期结算因半消息不可用暂缓，恢复后继续。
    mq_auto=publish('Broker故障送达结算恢复');win(mq_auto)
    for name in ('confirm','pickup'):action(mq_auto,name,12001)
    mq_timeout=publish('Broker故障本地消息补发')
    container_action('kill','broker')
    deliver_future=executor.submit(action,mq_auto,'deliver',12001)
    grab_future=executor.submit(win,mq_timeout,12002)
    wait_status(mq_auto,'DELIVERED',30)
    wait_for('停Broker后超时消息可靠落库',lambda:count(f"SELECT COUNT(*) FROM local_message WHERE msg_key='timeout:{mq_timeout}:0' AND status='PENDING'")==1,30)
    check('Broker不可用时送达消息保持PENDING',sql(f"SELECT status FROM local_message WHERE msg_key='autosettle:{mq_auto}'")=='PENDING',mq_auto)
    wait_for('Broker停机期间DB兜底回池',lambda:state(mq_timeout)['status']=='PUBLISHED' and state(mq_timeout)['round']==1 and state(mq_timeout)['slot']==0 and state(mq_timeout)['owner']==-1,100)
    wait_for('自动结算窗口已到',lambda:count(f'SELECT COUNT(*) FROM errand WHERE id={mq_auto} AND NOW(3)>=DATE_ADD(delivered_at,INTERVAL 45 SECOND)')==1,70)
    check('MQ不可用时资金不先扣后丢消息',state(mq_auto)['status']=='DELIVERED' and count(f"SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='settle:{mq_auto}'")==0,state(mq_auto))
    container_action('start','broker');wait_for('Broker恢复端口',lambda:listening(ports['mq']),90)
    deliver_future.result(timeout=90);grab_future.result(timeout=90)
    wait_status(mq_auto,'SETTLED',180)
    wait_for('Broker恢复本地消息补发SENT',lambda:count(f"SELECT COUNT(*) FROM local_message WHERE msg_key IN ('timeout:{mq_timeout}:0','autosettle:{mq_auto}') AND status='SENT'")==2,150)
    check('故障消息自动补发且不进DEAD',count(f"SELECT COUNT(*) FROM local_message WHERE msg_key IN ('timeout:{mq_timeout}:0','autosettle:{mq_auto}') AND status='DEAD'")==0,sql(f"SELECT msg_key,status,retry_count FROM local_message WHERE msg_key IN ('timeout:{mq_timeout}:0','autosettle:{mq_auto}')"))
    check('Broker恢复旧超时消息不再推进订单',state(mq_timeout)['round']==1 and state(mq_timeout)['slot']==0,state(mq_timeout));cancel(mq_timeout)

    # worker停止越过两个截止时间，再真正重启恢复积压。
    kill_service('worker')
    worker_timeout=publish('Worker宕机超时释放');win(worker_timeout,12002)
    worker_auto=publish('Worker宕机自动结算');delivered(worker_auto,12001)
    wait_for('Worker停机越过两个截止时间',lambda:count(f"SELECT COUNT(*) FROM errand WHERE (id={worker_timeout} AND NOW(3)>confirm_deadline) OR (id={worker_auto} AND NOW(3)>DATE_ADD(delivered_at,INTERVAL 45 SECOND))")==2,70)
    check('Worker停机期间到期任务可持久恢复',state(worker_timeout)['status']=='LOCKED' and state(worker_auto)['status']=='DELIVERED',{'timeout':state(worker_timeout),'auto':state(worker_auto)})
    start_service('worker')
    wait_status(worker_timeout,'PUBLISHED',90);wait_status(worker_auto,'SETTLED',90)
    check('Worker重启积压任务均自动恢复',state(worker_timeout)['round']==1 and state(worker_timeout)['slot']==0,{'timeout':state(worker_timeout),'auto':state(worker_auto)})
    # 重复旧轮次投递，在新一轮已确认后也不能释放现任接单人。
    current=win(worker_timeout,12003);action(worker_timeout,'confirm',12003)
    send_messages('errand-confirm-timeout',f'timeout:{worker_timeout}:0',{'errandId':int(worker_timeout),'round':0},10)
    wait_for('旧轮次消息实际重复消费',lambda:sum(worker_timeout in x and 'outcome=SKIPPED' in x for x in worker_logs().splitlines())>=10,45)
    check('重复旧轮次消息不释放新接单人',state(worker_timeout)['status']=='ACCEPTED' and state(worker_timeout)['owner']==12003 and state(worker_timeout)['round']==1,state(worker_timeout))
    for name in ('pickup','deliver'):action(worker_timeout,name,12003)
    action(worker_timeout,'settle',11001)


def final_invariants():
    states=[]
    for label,task in report['tasks'].items():
        ident=int(task['id']);expected=task['expected'];actual=state(ident)
        escrow=sql(f'SELECT status FROM escrow_order WHERE errand_id={ident}')
        states.append({'name':label,'errandId':str(ident),'state':actual,'escrow':escrow})
        assert actual['status']==expected and escrow==('RELEASED' if expected=='SETTLED' else 'REFUNDED'),states[-1]
        if expected=='SETTLED':
            assert count(f"SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='settle:{ident}' AND direction='CREDIT' AND user_id>0")==1,ident
            assert count(f"SELECT COUNT(*) FROM credit_event WHERE biz_no='settle:{ident}'")==1,ident
            reward=task['reward'];commission=reward*5//100
            actual_ledger=sql(f"SELECT a.owner_type,a.owner_id,l.direction,l.amount FROM wallet_ledger l JOIN wallet_account a ON a.id=l.account_id WHERE l.biz_no='settle:{ident}' ORDER BY a.owner_type")
            expected_ledger=f"COMMISSION\t-2\tCREDIT\t{commission}\nESCROW\t-1\tDEBIT\t{reward}\nUSER\t{actual['owner']}\tCREDIT\t{reward-commission}"
            assert actual_ledger==expected_ledger,{'id':ident,'actual':actual_ledger,'expected':expected_ledger}
            task['verifiedSettlementLedger']=actual_ledger
        expected_notifications=(f'settle:{ident}:pub\t11001\nsettle:{ident}:runner\t{actual["owner"]}'
                                if expected=='SETTLED' else f'refund:{ident}:pub\t11001')
        kind='settle' if expected=='SETTLED' else 'refund'
        notification_sql=f"SELECT msg_key,user_id FROM notification WHERE msg_key LIKE '{kind}:{ident}:%' ORDER BY msg_key,user_id"
        wait_for('最终资金通知消费 '+str(ident),lambda:sql(notification_sql)==expected_notifications,90)
        task['verifiedNotifications']=expected_notifications
    check('全部故障订单、托管及资金通知到达预期终态',True,states)
    check('最终没有PROCESSING请求',count("SELECT COUNT(*) FROM grab_request WHERE status='PROCESSING'")==0,sql('SELECT status,COUNT(*) FROM grab_request GROUP BY status'))
    check('每单每轮最多一个成功接单事实',count("SELECT COUNT(*) FROM (SELECT errand_id,round FROM grab_record GROUP BY errand_id,round HAVING COUNT(*)>1) v")==0,True)
    check('账户总额守恒',count('SELECT SUM(available+frozen) FROM wallet_account')==baseline,baseline)
    check('逐业务借贷平衡',count("SELECT COUNT(*) FROM (SELECT biz_no FROM wallet_ledger GROUP BY biz_no HAVING SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END)<>0) v")==0,True)
    check('托管没有残留资金',count("SELECT SUM(available+frozen) FROM wallet_account WHERE owner_type='ESCROW'")==0,True)
    inferred={row.split('\t')[0]:int(row.split('\t')[1]) for row in sql("SELECT a.id,a.available+a.frozen-COALESCE(l.net,0) FROM wallet_account a LEFT JOIN (SELECT account_id,SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END) net FROM wallet_ledger GROUP BY account_id) l ON l.account_id=a.id").splitlines()}
    check('逐账户快照与流水一致',all(value==initial_accounts.get(key,0) for key,value in inferred.items()),inferred)
    wait_for('所有修复任务完成',lambda:count("SELECT COUNT(*) FROM grab_repair_task WHERE status<>'DONE'")==0,150)
    wait_for('所有本地消息已实际发送',lambda:count("SELECT COUNT(*) FROM local_message WHERE status<>'SENT'")==0,150)
    check('无残留修复任务或死信',count("SELECT COUNT(*) FROM grab_repair_task WHERE status<>'DONE'")==0 and count("SELECT COUNT(*) FROM local_message WHERE status='DEAD'")==0,True)
    (out/'final-orders.tsv').write_text(sql('SELECT id,status,round,version,slot_taken,grabber_id FROM errand ORDER BY id'))
    (out/'final-ledger.tsv').write_text(sql('SELECT biz_no,account_id,direction,amount,ref_id FROM wallet_ledger ORDER BY biz_no,account_id'))
    (out/'final-messages.tsv').write_text(sql('SELECT msg_key,status,retry_count FROM local_message ORDER BY id'))

try:
    prefix_mysql='dash-mysql'
    ports={'redis':port(),'mq':port(),'api':port()};report['ports']=ports
    api_base=f"http://127.0.0.1:{ports['api']}"
    mysql_proxy=LostCommitAckProxy();proxies.append(mysql_proxy);report['mysqlProxyPort']=mysql_proxy.port
    command(['docker','network','create','--label',resource_label+'='+run,network]);report['network']=network
    config=out/'broker.conf';config.write_text('brokerClusterName=DefaultCluster\nbrokerName=fault-broker\nbrokerId=0\ntransactionCheckInterval=5000\ntransactionTimeOut=6000\n')
    for name,image,cmd in [('redis','redis:7.4-alpine',['redis-server','--save','','--appendonly','no']),('namesrv','apache/rocketmq:5.3.1',['sh','mqnamesrv']),('broker','apache/rocketmq:5.3.1',['sh','mqbroker','-n','rmqnamesrv:9876','-c','/tmp/fault-broker.conf','--enable-proxy'])]:
        container=prefix+'-'+name;containers.append(container)
        extra=['-p',f"127.0.0.1:{ports['redis']}:6379"] if name=='redis' else ['-e','JAVA_OPT_EXT=-server -Xms128m -Xmx384m -Xmn64m -XX:ActiveProcessorCount=4']
        if name=='namesrv':extra+=['--network-alias','rmqnamesrv']
        if name=='broker':extra+=['-p',f"127.0.0.1:{ports['mq']}:8081",'-v',str(config)+':/tmp/fault-broker.conf:ro']
        command(['docker','run','-d','--name',container,'--label',resource_label+'='+run,'--network',network,*extra,image,*cmd])
    wait_for('MQ与Redis启动',lambda:listening(ports['mq']) and listening(ports['redis']),100)
    init=command(['bash',str(root/'docker/init-mq.sh')],env={**os.environ,'BROKER_CONTAINER':prefix+'-broker','NAMESRV':'rmqnamesrv:9876','CLUSTER':'DefaultCluster'},timeout=180)
    (out/'mq-init.log').write_text(init)
    sql('CREATE DATABASE '+db+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',None);sql((root/'docker/init.sql').read_text())
    sql("INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version) VALUES(11001,11001,'USER',100000,0,0),(12001,12001,'USER',0,0,0),(12002,12002,'USER',0,0,0),(12003,12003,'USER',0,0,0);")
    sql(f"INSERT INTO bench_run(run_id,kind,scenario,started_at,status,env_note) VALUES('{run}','E2E','FAULT-RECOVERY',NOW(3),'RUNNING','真实停Redis/Broker、SIGKILL服务、丢弃COMMIT ACK和重复MQ投递');")
    baseline=count('SELECT SUM(available+frozen) FROM wallet_account');report['baselineTotalCents']=baseline
    initial_accounts={row.split('\t')[0]:int(row.split('\t')[1]) for row in sql('SELECT id,available+frozen FROM wallet_account').splitlines()}
    shared=['--spring.data.redis.host=127.0.0.1',f"--spring.data.redis.port={ports['redis']}",'--spring.data.redis.timeout=1000ms',f"--dash.mq.endpoints=127.0.0.1:{ports['mq']}",'--dash.mq.enabled=true','--dash.timeout.confirm-seconds=30','--dash.settle.auto-settle-seconds=45','--dash.ws.enabled=false','--logging.charset.console=UTF-8']
    jars={name:root/f'dash-{module}/target/dash-{module}-1.0.0-SNAPSHOT.jar' for name,module in [('api','bootstrap'),('worker','worker')]}
    report['jarSha256']={k:hashlib.sha256(p.read_bytes()).hexdigest() for k,p in jars.items()}
    java=['java','-Duser.timezone=Asia/Shanghai','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xms128m','-Xmx384m','-XX:ActiveProcessorCount=4','-jar']
    start_service('api');start_service('worker');login()
    if args.baseline_probe:baseline_probe()
    else:full_scenarios();final_invariants();report['passed']=True
except BaseException as error:
    report['failure']=redact_environment_secrets(error);report['traceback']=redact_environment_secrets(traceback.format_exc());print(report['traceback'],flush=True)
finally:
    for proc,connection in locks:
        if proc.poll() is None:
            try:sql(f'KILL CONNECTION {connection};')
            except Exception:pass
            try:proc.wait(timeout=5)
            except subprocess.TimeoutExpired:proc.kill()
    for proxy in proxies:proxy.close()
    executor.shutdown(wait=False,cancel_futures=True)
    report['businessPassed']=bool(report['passed'])
    report['cleanup']=cleanup_resources()
    report['passed']=report['businessPassed'] and report['cleanup']['passed']
    report['finishedAt']=datetime.now().isoformat()
    try:sql(f"UPDATE bench_run SET status='{'PASS' if report['passed'] else 'FAIL'}',finished_at=NOW(3) WHERE run_id='{run}';")
    except Exception:pass
    (out/'run-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('REPORT',out/'run-report.json',flush=True)
sys.exit(0 if report['passed'] else 1)
