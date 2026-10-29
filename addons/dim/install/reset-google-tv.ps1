# ВОЗВРАТ В НОЛЬ: телевизор снова со стандартным Google TV, как до CleanYourTV и Flick TV.
#  - главный экран и кнопка Home снова у Google TV
#  - включаются все приложения, которые отключали (на заводе отключены только talkback, feedback,
#    com.sky.market и com.security.SwaiotTV - они остаются как были)
#  - убираются служба «Flick TV: кнопка Home», доступ к «Сейчас играет» и разрешения Flick TV
#  - удаляются Flick TV (ua.volkov.dim) и лаунчер CleanYourTV (com.gathin.tvhome)
# Свои приложения (Lampa, TorrServe, VLC и т.д.) и их настройки не трогаются.
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = $null
foreach ($d in @($here, (Split-Path $here -Parent), "$env:USERPROFILE\Downloads", "$env:USERPROFILE\Desktop", [Environment]::GetFolderPath('Desktop'))) {
  foreach ($p in @((Join-Path $d "platform-tools\adb.exe"), (Join-Path $d "adb.exe"))) { if (-not $adb -and (Test-Path $p)) { $adb = $p } }
}
if (-not $adb) {
  $f = Get-ChildItem -Path "$env:USERPROFILE\Downloads" -Filter adb.exe -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($f) { $adb = $f.FullName }
}
if (-not $adb) { Write-Host "Не найден adb.exe. Скачай Android platform-tools в Загрузки и запусти снова."; pause; exit 1 }
$tv = ""
foreach ($f in @((Join-Path $here "tv-ip.txt"), (Join-Path (Split-Path $here -Parent) "tv-ip.txt"))) { if (-not $tv -and (Test-Path $f)) { $tv = (Get-Content $f -Raw).Trim() } }
if (-not $tv) { $tv = Read-Host "IP-адрес телевизора (Настройки > Сеть)"; $tv | Out-File (Join-Path $here "tv-ip.txt") -Encoding ascii }
$t = "$($tv):5555"
Write-Host "Подключаюсь к $t (на телевизоре может появиться запрос - нажми Разрешить)"
& $adb connect $t | Out-Host
$state = (& $adb -s $t get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") { Write-Host "Телевизор не ответил ($state). Проверь, что включена отладка по сети, и запусти снова."; pause; exit 1 }
function Sh([string]$c) { & $adb -s $t shell $c 2>&1 | Out-String }


$a = Read-Host "Вернуть телевизор к стандартному Google TV и удалить Flick TV? y/n"
if ($a -ne "y") { exit 0 }

Write-Host "Главный экран - Google TV"
Sh "pm enable --user 0 com.google.android.apps.tv.launcherx" | Out-Null
Sh "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity" | Out-Null

Write-Host "Спец. возможности: убираю службы Flick TV и CleanYourTV"
$list = (Sh "settings get secure enabled_accessibility_services").Trim()
if ($list -eq "null") { $list = "" }
$keep = @($list -split ":" | Where-Object { $_ -and $_ -notmatch "ua\.volkov\.dim|com\.gathin\.tvhome" })
if ($keep.Count -gt 0) { Sh "settings put secure enabled_accessibility_services $($keep -join ':')" | Out-Null }
else { Sh "settings delete secure enabled_accessibility_services" | Out-Null }
Sh "cmd notification disallow_listener ua.volkov.dim/ua.volkov.dim.NowPlayingListener" | Out-Null

Write-Host "Включаю отключённые приложения"
$factory = @("com.google.android.marvin.talkback", "com.google.android.feedback", "com.sky.market", "com.security.SwaiotTV")
foreach ($line in ((Sh "pm list packages -d") -split "`n")) {
  $p = ($line.Trim() -replace '^package:','')
  if ($p -and ($factory -notcontains $p)) { Write-Host "  включаю $p"; Sh "pm enable --user 0 $p" | Out-Null }
}

Write-Host "Удаляю Flick TV и лаунчер CleanYourTV"
& $adb -s $t uninstall ua.volkov.dim | Out-Host
& $adb -s $t uninstall com.gathin.tvhome | Out-Host

Sh "input keyevent KEYCODE_HOME" | Out-Null
Write-Host ""
Write-Host "Готово: на телевизоре снова стандартный Google TV."
Write-Host "Последний шаг вручную: Настройки > Система > Для разработчиков - выключи «Отладку по сети»"
Write-Host "(и «Отладку по USB»), чтобы порт 5555 закрылся."
pause
