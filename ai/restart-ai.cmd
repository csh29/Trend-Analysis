@echo off
rem AI 서비스 재기동(더블클릭 가능). 실행 정책을 우회하고 restart-ai.ps1 을 실행한다.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-ai.ps1" %*
if "%~1"=="" pause
