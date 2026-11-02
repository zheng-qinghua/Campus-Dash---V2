# CampusDash 开发环境启动脚本（Windows PowerShell 7）
# 功能：环境检查 -> 注入 MySQL 口令 -> 启动中间件容器 -> 初始化 RocketMQ
# 用法：在项目根目录执行  .\start-dev.ps1
# 完成后请按脚本末尾提示，分别在三个独立终端启动 后端 / worker / 前端。

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

Write-Host ''
Write-Host '===== CampusDash 开发环境启动（第一步：环境与中间件） =====' -ForegroundColor Cyan
Write-Host ''

# ---------- 1. 环境检查 ----------
$missing = @()
foreach ($cmd in 'java', 'mvn', 'docker', 'node', 'npm') {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { $missing += $cmd }
}
if ($missing.Count -gt 0) {
    Write-Host ("缺少组件：{0}" -f ($missing -join ', ')) -ForegroundColor Red
    Write-Host '请先安装：JDK 21、Maven 3.9+、Docker Desktop、Node 18+，并确保已加入 PATH。' -ForegroundColor Yellow
    exit 1
}
Write-Host '[OK] 环境组件齐全（java / mvn / docker / node / npm）' -ForegroundColor Green

# ---------- 2. Docker 是否运行 ----------
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker 未运行，请先启动 Docker Desktop，再重试本脚本。' -ForegroundColor Red
    exit 1
}
Write-Host '[OK] Docker 运行中' -ForegroundColor Green

# ---------- 3. 注入 MySQL 口令 ----------
if (-not $env:SPRING_DATASOURCE_PASSWORD) {
    Write-Host '首次启动请输入一个 MySQL root 密码（应用 / worker / 测试 / 容器共用，请记住）。' -ForegroundColor Yellow
    Write-Host '若之前已经启动过本项目的容器，请输入当时设置的密码（已有数据卷不会自动改密）。' -ForegroundColor Yellow
    $sec = Read-Host 'MySQL root 密码' -AsSecureString
    if ($null -eq $sec -or $sec.Length -eq 0) { Write-Host '密码不能为空。' -ForegroundColor Red; exit 1 }
    $env:SPRING_DATASOURCE_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
Write-Host '[OK] SPRING_DATASOURCE_PASSWORD 已注入当前终端' -ForegroundColor Green

# ---------- 4. 启动中间件（MySQL 3307 / Redis 6380 / RocketMQ 9876+10911+8081） ----------
Write-Host '启动 MySQL + Redis + RocketMQ 容器（首次会拉取镜像，可能需要几分钟）...' -ForegroundColor Cyan
Push-Location (Join-Path $ProjectRoot 'campus-dash-backend\docker')
try {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败，请查看上方错误。' }
    Write-Host '[OK] 中间件容器已启动' -ForegroundColor Green
} finally {
    Pop-Location
}

# ---------- 5. 等待 MySQL / Redis 就绪 ----------
Write-Host '等待中间件就绪（约 20 秒）...' -ForegroundColor Cyan
Start-Sleep -Seconds 20

# ---------- 6. 初始化 RocketMQ（topic 与消费组） ----------
Write-Host '初始化 RocketMQ topic 与消费组...' -ForegroundColor Cyan
$bashCmd = Get-Command bash -ErrorAction SilentlyContinue
if ($bashCmd) {
    Push-Location (Join-Path $ProjectRoot 'campus-dash-backend\docker')
    try {
        bash ./init-mq.sh
        if ($LASTEXITCODE -ne 0) { Write-Host 'init-mq.sh 执行失败，请检查上方日志。' -ForegroundColor Yellow }
    } finally {
        Pop-Location
    }
} else {
    Write-Host '未检测到 bash（未安装 Git Bash / WSL）。请手动执行以下命令初始化 RocketMQ：' -ForegroundColor Yellow
    Write-Host ''
    $admin = "docker exec dash-rmqbroker sh -c 'cd /home/rocketmq/rocketmq-*/bin && sh mqadmin"
    Write-Host "$admin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t errand-confirm-timeout -a +message.type=DELAY'"
    Write-Host "$admin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t errand-auto-settle -a +message.type=DELAY'"
    Write-Host "$admin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t errand-cache-evict -a +message.type=DELAY'"
    Write-Host "$admin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t errand-fund-event -a +message.type=TRANSACTION'"
    Write-Host "$admin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g dash-timeout-consumer'"
    Write-Host "$admin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g dash-autosettle-consumer'"
    Write-Host "$admin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g dash-fund-event-consumer'"
    Write-Host "$admin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g dash-cache-evict-consumer'"
}

Write-Host ''
Write-Host '===== 第一步完成 =====' -ForegroundColor Cyan
Write-Host '接下来请在三个独立终端分别执行（每个终端都要先设置密码变量）：' -ForegroundColor Green
Write-Host ''
Write-Host '【终端 1 - 后端 API（8080）】' -ForegroundColor White
Write-Host '  cd C:\Users\20243\Desktop\后端项目\campus-dash-backend'
Write-Host '  $env:SPRING_DATASOURCE_PASSWORD = "你的密码"'
Write-Host '  mvn -pl dash-bootstrap spring-boot:run -Dspring-boot.run.profiles=local'
Write-Host ''
Write-Host '【终端 2 - worker（超时流转 / 自动结算 / 对账）】' -ForegroundColor White
Write-Host '  cd C:\Users\20243\Desktop\后端项目\campus-dash-backend'
Write-Host '  $env:SPRING_DATASOURCE_PASSWORD = "你的密码"'
Write-Host '  mvn install -DskipTests'
Write-Host '  java -jar dash-worker\target\dash-worker-1.0.0-SNAPSHOT.jar'
Write-Host ''
Write-Host '【终端 3 - 前端（5173）】' -ForegroundColor White
Write-Host '  cd C:\Users\20243\Desktop\后端项目\campus-dash-frontend'
Write-Host '  npm install'
Write-Host '  npm run dev'
Write-Host ''
Write-Host '浏览器打开 http://localhost:5173 ，选择演示身份登录：' -ForegroundColor Green
Write-Host '  发单人 1001 / 跑腿 2001、2002 / 仲裁员 9001'
Write-Host ''
Write-Host '健康检查：http://localhost:8080/api/health （应返回 JSON）' -ForegroundColor Cyan
