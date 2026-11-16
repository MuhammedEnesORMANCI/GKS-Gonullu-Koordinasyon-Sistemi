<#
.SYNOPSIS
  Geliştirme veritabanına demo verisi EKLER (hiçbir kaydı silmez/değiştirmez). Görev 25.

.DESCRIPTION
  Backend'i "local,demo-seed" profiliyle kısa süreli başlatır: bekleyen Flyway migration'ları uygulanır, demo verisi
  eklenir, uygulama kendiliğinden kapanır (web sunucusu açılmaz, bildirim gönderilmez, zamanlanmış işler çalışmaz).
  Tekrar çalıştırılırsa aynı kayıtları ikinci kez eklemez. Üretimde çalışmaz: yalnızca bu makinedeki veritabanı,
  demo-seed profili ve GKS_DEMO_CONFIRM_LOCAL=true ile çalışır.

  Önkoşul: Docker'da PostgreSQL açık, backend KAPALI (aynı anda tek bootRun), kök dizinde .env.

.EXAMPLE
  $env:GKS_DEMO_PASSWORD = '<en az 12 karakter>'
  .\scripts\dev\demo-seed.ps1
#>
$ErrorActionPreference = 'Stop'
if (-not $env:GKS_DEMO_PASSWORD) {
  $secure = Read-Host 'Demo hesaplarının ortak parolası (en az 12 karakter)' -AsSecureString
  $env:GKS_DEMO_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
}
$env:SPRING_PROFILES_ACTIVE = 'local,demo-seed'
$env:GKS_DEMO_CONFIRM_LOCAL = 'true'
Push-Location (Join-Path $PSScriptRoot '..\..\backend')
try {
  & .\gradlew.bat bootRun --offline --no-daemon
  if ($LASTEXITCODE -ne 0) { throw "Demo verisi eklenemedi (çıkış kodu $LASTEXITCODE). Günlükte 'DEMO VERİSİ' satırına bakın." }
}
finally {
  Pop-Location
  Remove-Item Env:GKS_DEMO_PASSWORD, Env:GKS_DEMO_CONFIRM_LOCAL, Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
}
