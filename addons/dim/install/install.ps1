# Flick TV - установка на Android TV / Google TV. Автор: Serhii Volkov (@sergvo1kov).
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

function Setup-Flick {
  $me = "ua.volkov.dim/.HomeRedirectService"
  Write-Host "Делаю Flick TV главным экраном"
  Sh "cmd package set-home-activity --user 0 ua.volkov.dim/.HomeActivity" | Out-Null
  $list = (Sh "settings get secure enabled_accessibility_services").Trim()
  if ($list -eq "null") { $list = "" }
  $parts = @($list -split ":" | Where-Object { $_ -and $_ -ne $me -and $_ -ne "ua.volkov.dim/ua.volkov.dim.HomeRedirectService" })
  $base = ($parts -join ":")
  # turning the service off and on again makes Android bind it after an update
  if ($base) { Sh "settings put secure enabled_accessibility_services $base" | Out-Null } else { Sh "settings delete secure enabled_accessibility_services" | Out-Null }
  Start-Sleep -Seconds 1
  $new = if ($base) { "$($base):$me" } else { $me }
  Sh "settings put secure enabled_accessibility_services $new" | Out-Null
  Sh "settings put secure accessibility_enabled 1" | Out-Null
  Write-Host "Разрешения: Сейчас играет, каналы ТВ, таймер заставки"
  Sh "cmd notification allow_listener ua.volkov.dim/ua.volkov.dim.NowPlayingListener" | Out-Null
  Sh "pm grant ua.volkov.dim com.android.providers.tv.permission.READ_EPG_DATA" | Out-Null
  Sh "pm grant ua.volkov.dim android.permission.READ_TV_LISTINGS" | Out-Null
  Sh "appops set ua.volkov.dim WRITE_SETTINGS allow" | Out-Null
  Sh "appops set ua.volkov.dim REQUEST_INSTALL_PACKAGES allow" | Out-Null   # обновления по воздуху
  Sh "am start -n ua.volkov.dim/.HomeActivity" | Out-Null
}

function Install-Flick([string]$apk) {
  Write-Host "Устанавливаю $(Split-Path $apk -Leaf)"
  $out = & $adb -s $t install -r $apk 2>&1 | Out-String
  Write-Host $out.Trim()
  if ($out -match "UPDATE_INCOMPATIBLE") {
    $a = Read-Host "Старая версия подписана другим ключом. Удалить её и поставить заново? Настройки Flick TV (ключи, избранное) сбросятся. y/n"
    if ($a -ne "y") { exit 1 }
    & $adb -s $t uninstall ua.volkov.dim | Out-Host
    $out = & $adb -s $t install $apk 2>&1 | Out-String
    Write-Host $out.Trim()
  }
  if ($out -notmatch "Success") { Write-Host "Установка не удалась."; pause; exit 1 }
}

Install-Flick (Join-Path $here "flick-tv-2.0.apk")
Setup-Flick
Write-Host ""
Write-Host "Готово. Нажми Home на пульте - откроется Flick TV."
Write-Host "Ключи (Smart Life, тревоги, TMDB для Lampa) вводятся на телевизоре: Настройки > Flick TV."
pause
