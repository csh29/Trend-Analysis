# AI 서비스(FastAPI) 재기동 — stop-ai.ps1 로 끄고 start-ai.ps1 로 다시 띄운다. 프롬프트(prompts/*.md)·코드를 고친 뒤에 쓴다.
# 사용: .\restart-ai.cmd  (또는 powershell -ExecutionPolicy Bypass -File .\restart-ai.ps1 [-Port 8001])
param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8001,
    [int]$WaitSeconds = 60
)
Set-Location -LiteralPath $PSScriptRoot

& (Join-Path $PSScriptRoot "stop-ai.ps1") -Port $Port
# 종료 성공은 포트가 비었는지로 본다 — stop-ai.ps1 은 정상 종료 때 exit 를 쓰지 않아 $LASTEXITCODE 에 taskkill 값이 남는다
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    # 포트가 남아 있으면 새로 띄워도 포트 충돌로 죽는다
    Write-Host "AI 서비스를 끄지 못해 다시 띄우지 않습니다." -ForegroundColor Red
    exit 1
}

& (Join-Path $PSScriptRoot "start-ai.ps1") -BindHost $BindHost -Port $Port -WaitSeconds $WaitSeconds
exit $LASTEXITCODE
