#!/usr/bin/env python3
"""创建独立 DB/Redis/RocketMQ，运行真实 API/MQ 验收并保留报告。

使用已有 dash-mysql 与已打包的 Java JAR；新增容器和 Java 子进程在结束后回收，
验收数据库及日志保留。业务动作全部由 live_api_smoke.py 经 HTTP 执行。
"""
import hashlib, json, os, re, secrets, socket, subprocess, sys, time, traceback
from datetime import datetime
from pathlib import Path
from urllib.request import urlopen
from secret_env import database_environment, isolated_redis_environment, redact_environment_secrets

database_env=database_environment()
application_env=isolated_redis_environment(database_env)

root=Path(__file__).resolve().parents[1]; front=root.parent/'campus-dash-frontend'
run=datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+secrets.token_hex(2)
out=root/'verification/live-api-mq'/run; out.mkdir(parents=True)
db='campus_dash_verify_live_'+run.replace('-','_')
prefix='dash-live-'+run.lower(); network=prefix; containers=[]; processes=[]; streams=[]
resource_label='com.campusdash.verification.run'
report={'runId':run,'database':db,'startedAt':datetime.now().isoformat(),'passed':False,'checks':[], 'environment':{'mysql':'8.0.40','redis':'7.4','rocketmq':'5.3.1','confirmSeconds':30,'autoSettleSeconds':45,'scanIntervalMillis':600000,'defaultRunnerRateLimits':True},'logs':{}}
(out/'launcher.py').write_bytes(Path(__file__).read_bytes())
(out/'secret_env.py').write_bytes((root/'scripts/secret_env.py').read_bytes())
(out/'live_api_smoke.py').write_bytes((front/'tests/live_api_smoke.py').read_bytes())

def command(args, **kwargs):
    result=subprocess.run(args,text=True,capture_output=True,**kwargs)
    if result.returncode: raise RuntimeError(redact_environment_secrets(str(args)+': '+result.stderr[-3000:]+result.stdout[-3000:]))
    return result.stdout.strip()
def sql(query, database=db):
    args=['docker','exec','-e','MYSQL_PWD','-i','dash-mysql','mysql','-uroot','--default-character-set=utf8mb4','-N','-B']
    if database: args.append(database)
    return command(args,input=query,timeout=30,env=database_env)
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

try:
    ports={'redis':port(),'mq':port(),'api':port()};report['ports']=ports
    command(['docker','network','create','--label',resource_label+'='+run,network]);report['network']=network
    for name,image,args in [
      ('redis','redis:7.4-alpine',['redis-server','--maxmemory','128mb','--maxmemory-policy','noeviction']),
      ('namesrv','apache/rocketmq:5.3.1',['sh','mqnamesrv']),
      ('broker','apache/rocketmq:5.3.1',['sh','mqbroker','-n','rmqnamesrv:9876','--enable-proxy'])]:
        container=prefix+'-'+name;extra=[]
        if name=='redis':extra=['-p',f"127.0.0.1:{ports['redis']}:6379"]
        if name=='namesrv':extra=['--network-alias','rmqnamesrv','-e','JAVA_OPT_EXT=-server -Xms128m -Xmx256m -Xmn64m -XX:ActiveProcessorCount=4']
        if name=='broker':extra=['-p',f"127.0.0.1:{ports['mq']}:8081",'-e','BROKER_IP1=127.0.0.1','-e','JAVA_OPT_EXT=-server -Xms256m -Xmx512m -Xmn128m -XX:ActiveProcessorCount=4']
        # docker run 可能已创建容器却在启动阶段返回失败，先登记名称，收尾再按 label 验证归属。
        containers.append(container)
        command(['docker','run','-d','--name',container,'--label',resource_label+'='+run,'--network',network,*extra,image,*args])
    until(lambda:listening(ports['mq']) and listening(ports['redis']))
    init=command(['bash',str(root/'docker/init-mq.sh')],env={**os.environ,'BROKER_CONTAINER':prefix+'-broker','NAMESRV':'rmqnamesrv:9876','CLUSTER':'DefaultCluster',
        'TOPIC':'errand-confirm-timeout','TOPIC_AUTO_SETTLE':'errand-auto-settle','TOPIC_FUND_EVENT':'errand-fund-event','TOPIC_CACHE_EVICT':'errand-cache-evict','GROUP':'dash-timeout-consumer'},timeout=180)
    (out/'mq-init.log').write_text(init)
    sql('CREATE DATABASE '+db+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',None)
    sql((root/'docker/init.sql').read_text())
    sql("INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version) VALUES(11001,11001,'USER',100000,0,0),(12001,12001,'USER',0,0,0),(12002,12002,'USER',0,0,0);")
    sql(f"INSERT INTO bench_run(run_id,kind,scenario,started_at,status,env_note) VALUES('{run}','E2E','LIVE-API-MQ',NOW(3),'RUNNING','隔离DB/Redis/RocketMQ；真实HTTP；默认跑腿限流');")
    baseline=int(sql('SELECT SUM(available+frozen) FROM wallet_account'));report['baselineTotalCents']=baseline
    (out/'baseline-accounts.tsv').write_text(sql('SELECT id,owner_id,owner_type,available,frozen FROM wallet_account ORDER BY id'))
    initial_accounts={row.split('\t')[0]:int(row.split('\t')[1]) for row in sql('SELECT id,available+frozen FROM wallet_account').splitlines()}
    shared=[f"--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/{db}?useSSL=false&connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true",'--spring.data.redis.host=127.0.0.1',f"--spring.data.redis.port={ports['redis']}",f"--dash.mq.endpoints=127.0.0.1:{ports['mq']}",'--dash.mq.enabled=true','--dash.timeout.confirm-seconds=30','--dash.settle.auto-settle-seconds=45','--dash.timeout.scan-interval-ms=600000','--dash.settle.scan-interval-ms=600000','--dash.ws.enabled=false','--logging.charset.console=UTF-8']
    jars={name:root/f'dash-{module}/target/dash-{module}-1.0.0-SNAPSHOT.jar' for name,module in [('api','bootstrap'),('worker','worker')]}
    report['jarSha256']={k:hashlib.sha256(p.read_bytes()).hexdigest() for k,p in jars.items()}
    java=['java','-Duser.timezone=Asia/Shanghai','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xms128m','-Xmx384m','-XX:ActiveProcessorCount=4','-jar']
    api,api_log=launch('api',[*java,str(jars['api']),*shared,'--spring.profiles.active=local',f"--server.port={ports['api']}",'--server.address=127.0.0.1','--dash.worker-id=41',f'--dash.auth.session-namespace={run}'])
    worker,worker_log=launch('worker',[*java,str(jars['worker']),*shared,'--dash.worker-id=42','--logging.level.com.campusdash.worker.TimeoutTransferConsumer=DEBUG'])
    report['javaPids']=[p.pid for p in processes]
    def ready():
        with urlopen(f"http://127.0.0.1:{ports['api']}/api/health",timeout=2) as r: body=json.load(r)
        return body.get('code')=='OK' and 'Started WorkerApplication' in worker_log.read_text()
    until(ready)
    report['readyAt']=datetime.now().isoformat();print('READY',db,out,flush=True)
    args=[sys.executable,str(front/'tests/live_api_smoke.py'),'--isolated-backend-ready','--base',f"http://127.0.0.1:{ports['api']}",'--worker-log',str(worker_log),'--output',str(out/'api-results')]
    report['scriptCommand']=args
    with (out/'script.log').open('w') as script_log:
        result=subprocess.run(args,text=True,stdout=script_log,stderr=subprocess.STDOUT,timeout=600)
    report['scriptExitCode']=result.returncode
    evidence=json.loads((out/'api-results/api-report.json').read_text())
    report['apiChecks']=len(evidence['checks']);report['apiPassed']=evidence['passed']
    sql(f"INSERT INTO bench_run_item(run_id,entity_type,entity_id) SELECT '{run}','ERRAND',id FROM errand WHERE publisher_id=11001;")
    report['taskIds']=[v['id'] for v in evidence['tasks'].values()]
    check('真实API/MQ脚本完整通过',result.returncode==0 and evidence['passed'],evidence.get('failure',report['apiChecks']))
    check('三个自动流程都有MQ消费者证据',len(evidence['mqEvidence'])==3,evidence['mqEvidence'])
    check('本轮没有兜底扫描代替MQ',len(evidence['scannerLogLines'])==0,evidence['scannerLogLines'])
    expected_final_states={
        '20请求竞争单名额':('SETTLED','RELEASED'),
        '20请求重复退款':('CANCELLED','REFUNDED'),
        '仲裁支持跑腿':('SETTLED','RELEASED'),
        'MQ超时转候选':('SETTLED','RELEASED'),
        'MQ候选空回退':('CANCELLED','REFUNDED'),
        'MQ送达自动结算':('SETTLED','RELEASED'),
    }
    final_states=[]
    for label,expected in expected_final_states.items():
        ident=int(evidence['tasks'][label]['id'])
        actual=sql(f"SELECT r.status,COALESCE(e.status,'MISSING') FROM errand r LEFT JOIN escrow_order e ON e.campus_id=r.campus_id AND e.errand_id=r.id WHERE r.id={ident}").split('\t')
        final_states.append({'name':label,'errandId':str(ident),'expected':list(expected),'actual':actual})
    check('本轮六个订单及托管均达到预期终态',set(evidence['tasks'])==set(expected_final_states)
          and all(item['actual']==item['expected'] for item in final_states),final_states)
    processing=int(sql("SELECT COUNT(*) FROM grab_request WHERE status='PROCESSING' AND errand_id IN ("+
                       ','.join(str(int(ident)) for ident in report['taskIds'])+")"))
    check('本轮无未决抢单请求',processing==0,processing)
    timing=[]
    for label,target,key_prefix in [('MQ超时转候选','LOCKED','timeout'),('MQ候选空回退','PUBLISHED','timeout'),('MQ送达自动结算','SETTLED','autosettle')]:
        ident=int(evidence['tasks'][label]['id'])
        msg_key=f'timeout:{ident}:0' if key_prefix=='timeout' else f'autosettle:{ident}'
        from_status='LOCKED' if key_prefix=='timeout' else 'DELIVERED'
        row=sql(f"SELECT m.deliver_at,s.created_at,TIMESTAMPDIFF(MICROSECOND,m.deliver_at,s.created_at) FROM local_message m JOIN errand_status_log s ON s.errand_id={ident} AND s.from_status='{from_status}' AND s.to_status='{target}' WHERE m.msg_key='{msg_key}' ORDER BY s.id LIMIT 1").split('\t')
        check(label+'未提前执行',len(row)==3 and int(row[2])>=0,row)
        timing.append({'name':label,'errandId':str(ident),'scheduledAt':row[0],'appliedAt':row[1],'latenessMicros':int(row[2])})
    report['mqTiming']=timing
    final=int(sql('SELECT SUM(available+frozen) FROM wallet_account'))
    check('账户总额守恒',final==baseline,{'before':baseline,'after':final})
    bad=int(sql("SELECT COUNT(*) FROM (SELECT biz_no FROM wallet_ledger GROUP BY biz_no HAVING SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END)<>0) v"))
    check('所有业务借贷平衡',bad==0,bad)
    held=int(sql("SELECT (SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account WHERE owner_type='ESCROW')-(SELECT COALESCE(SUM(amount),0) FROM escrow_order WHERE status='HELD')"))
    check('托管余额与HELD一致',held==0,held)
    bad=int(sql("SELECT COUNT(*) FROM escrow_order e JOIN errand r ON r.id=e.errand_id WHERE (r.status='SETTLED' AND e.status<>'RELEASED') OR (r.status IN ('CANCELLED','REFUNDED') AND e.status<>'REFUNDED') OR (r.status IN ('PUBLISHED','LOCKED','ACCEPTED','PICKED_UP','DELIVERED','DISPUTED') AND e.status<>'HELD')"))
    check('订单终态与托管终态一致',bad==0,bad)
    inferred={row.split('\t')[0]:int(row.split('\t')[1]) for row in sql("SELECT a.id,a.available+a.frozen-COALESCE(l.net,0) FROM wallet_account a LEFT JOIN (SELECT account_id,SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END) net FROM wallet_ledger GROUP BY account_id) l ON l.account_id=a.id").splitlines()}
    check('逐账户快照与流水净额一致',all(value==initial_accounts.get(key,0) for key,value in inferred.items()),inferred)
    bad=int(sql("SELECT COUNT(*) FROM (SELECT errand_id,round FROM grab_record WHERE result='GRABBED' GROUP BY errand_id,round HAVING COUNT(*)>1) v"))
    check('每订单每轮最多一个占用事实',bad==0,bad)
    invariant=front/'verification/2026-10-14/fund-invariants.sql'
    (out/'fund-invariants.tsv').write_text(sql(invariant.read_text()))
    report['passed']=True
except BaseException as e:
    report['failure']=redact_environment_secrets(e);report['traceback']=redact_environment_secrets(traceback.format_exc());print(report['traceback'],flush=True)
finally:
    report['businessPassed']=bool(report['passed'])
    report['cleanup']=cleanup_resources()
    report['processesStopped']=all(item['stopped'] for item in report['cleanup']['java'])
    report['containersStopped']=all(item['removed'] for item in report['cleanup']['containers'])
    report['networkRemoved']=report['cleanup']['network']['removed']
    report['passed']=report['businessPassed'] and report['cleanup']['passed']
    if report['businessPassed'] and not report['cleanup']['passed']:
        report['failure']='业务验收通过，但资源清理失败：'+'；'.join(report['cleanup']['errors'])
    report['finishedAt']=datetime.now().isoformat()
    try:sql(f"UPDATE bench_run SET status='{'PASS' if report['passed'] else 'FAIL'}',finished_at=NOW(3) WHERE run_id='{run}';")
    except Exception:pass
    (out/'run-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('REPORT',out/'run-report.json',flush=True)
sys.exit(0 if report['passed'] else 1)
