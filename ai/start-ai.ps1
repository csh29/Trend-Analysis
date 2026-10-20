# AI 서비스(FastAPI) 기동. 백그라운드로 띄우고 /health 가 응답할 때까지 기다린다.
# 사용: .\start-ai.cmd  (또는 powershell -ExecutionPolicy Bypass -File .\start-ai.ps1 [-Port 8001])
# 로그는 logs\ai-server.log(표준출력)·logs\ai-server.err.log(오류·uvicorn 로그), 종료는 stop-ai.cmd.
param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8001,
    [int]$WaitSeconds = 60
)
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

$pidFile = Join-Path $PSScriptRoot ".ai-server.pid"
$python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
$healthUrl = "http://${BindHost}:${Port}/health"

function Test-Health {
    try {
        (Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200
    } catch {
        $false
    }
}

# 이미 떠 있으면 다시 띄우지 않는다(포트 충돌로 바로 죽는다)
if (Test-Health) {
    Write-Host "AI 서비스가 이미 실행 중입니다: $healthUrl"
    exit 0
}
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    Write-Host "포트 $Port 를 다른 프로세스(PID $($listener.OwningProcess))가 쓰고 있습니다. 먼저 정리하세요." -ForegroundColor Red
    exit 1
}

# 처음이면 가상환경을 만들고 패키지를 설치한다(이 PC의 python 은 스토어 스텁이라 py 로 만든다)
if (-not (Test-Path -LiteralPath $python)) {
    Write-Host "가상환경(.venv)이 없어 새로 만들고 패키지를 설치합니다. 몇 분 걸립니다..."
    & py -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw "가상환경 생성 실패" }
    & $python -m pip install --disable-pip-version-check -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "패키지 설치 실패" }
}

$logDir = Join-Path $PSScriptRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$outLog = Join-Path $logDir "ai-server.log"
$errLog = Join-Path $logDir "ai-server.err.log"

# 리다이렉트는 파일을 새로 쓰므로, 이전 기동의 로그(토큰 사용량 등)를 날짜를 붙여 보관한다. 최근 20개만 둔다
$archiveDir = Join-Path $logDir "archive"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
foreach ($log in @($outLog, $errLog)) {
    if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -gt 0) {
        New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
        $name = [IO.Path]::GetFileNameWithoutExtension($log) + ".$stamp.log"
        Move-Item -LiteralPath $log -Destination (Join-Path $archiveDir $name) -Force
    }
}
Get-ChildItem -LiteralPath $archiveDir -Filter "*.log" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -Skip 20 | Remove-Item -Force

$env:PYTHONIOENCODING = "utf-8"  # 한글 로그가 시스템 인코딩(MS949)으로 깨지지 않게
# 워커는 1개(--workers 없음) — 내장형 Chroma 폴더는 한 프로세스만 열어야 안전하다
$proc = Start-Process -FilePath $python `
    -ArgumentList "-m", "uvicorn", "main:app", "--host", $BindHost, "--port", $Port `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Set-Content -LiteralPath $pidFile -Value $proc.Id -Encoding ascii

Write-Host "AI 서비스 기동 중(PID $($proc.Id))..."
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    if (Test-Health) {
        Write-Host "AI 서비스가 떴습니다: $healthUrl" -ForegroundColor Green
        exit 0
    }
    if ($proc.HasExited) { break }
    Start-Sleep -Seconds 1
}

Write-Host "AI 서비스가 응답하지 않습니다. 로그를 확인하세요: $errLog" -ForegroundColor Red
Get-Content -LiteralPath $errLog -Tail 20 -ErrorAction SilentlyContinue
exit 1
