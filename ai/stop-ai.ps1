# AI 서비스(FastAPI) 종료. start-ai 가 남긴 PID 와 포트를 듣는 프로세스를 프로세스 트리째 끝낸다.
# 가상환경 python.exe 는 실제 파이썬을 자식 프로세스로 띄우므로 부모만 끄면 서버가 남는다 — 그래서 taskkill /T 를 쓴다.
# 사용: .\stop-ai.cmd  (또는 powershell -ExecutionPolicy Bypass -File .\stop-ai.ps1 [-Port 8001])
param(
    [int]$Port = 8001
)
Set-Location -LiteralPath $PSScriptRoot

$pidFile = Join-Path $PSScriptRoot ".ai-server.pid"
$targets = @()

if (Test-Path -LiteralPath $pidFile) {
    $savedPid = [int](Get-Content -LiteralPath $pidFile -TotalCount 1)
    if (Get-Process -Id $savedPid -ErrorAction SilentlyContinue) { $targets += $savedPid }
}

# PID 파일이 없거나 직접 띄운 경우: 포트를 듣는 프로세스 중 이 폴더 가상환경의 파이썬만 고른다(남의 프로그램은 건드리지 않는다)
$venvRoot = (Join-Path $PSScriptRoot ".venv").ToLower()
$basePython = $null
$cfg = Join-Path $PSScriptRoot ".venv\pyvenv.cfg"
if (Test-Path -LiteralPath $cfg) {
    $homeLine = Select-String -LiteralPath $cfg -Pattern "^home\s*=\s*(.+)$" | Select-Object -First 1
    if ($homeLine) { $basePython = $homeLine.Matches[0].Groups[1].Value.Trim().ToLower() }
}
Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $($_.OwningProcess)" -ErrorAction SilentlyContinue
    if (-not $owner) { return }
    $cmd = "$($owner.CommandLine)".ToLower()
    $exe = "$($owner.ExecutablePath)".ToLower()
    $isOurs = $cmd.Contains("uvicorn") -and $cmd.Contains("app.main:app") -and
        ($exe.StartsWith($venvRoot) -or ($basePython -and $exe.StartsWith($basePython)))
    if ($isOurs) {
        $targets += $owner.ProcessId
    } else {
        Write-Host "포트 $Port 를 AI 서비스가 아닌 프로세스(PID $($owner.ProcessId), $($owner.Name))가 쓰고 있어 건드리지 않습니다." -ForegroundColor Yellow
    }
}

$targets = $targets | Select-Object -Unique
if (-not $targets) {
    Write-Host "실행 중인 AI 서비스가 없습니다."
    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
    exit 0
}

foreach ($id in $targets) {
    # 부모를 트리째 끄면 자식(실제 서버)도 같이 끝난다 — 이미 끝난 것은 건너뛴다
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        & taskkill /PID $id /T /F 2>&1 | Out-Null
    }
}
Start-Sleep -Seconds 1
Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "포트 $Port 가 아직 열려 있습니다. 작업 관리자에서 확인하세요." -ForegroundColor Red
    exit 1
}
Write-Host "AI 서비스를 종료했습니다." -ForegroundColor Green
