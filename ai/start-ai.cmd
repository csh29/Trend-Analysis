@echo off
rem AI 서비스 기동(더블클릭 가능). 실행 정책과 무관하게 start-ai.ps1 을 돌린다.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-ai.ps1" %*
if "%~1"=="" pause
