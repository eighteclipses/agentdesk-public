[CmdletBinding()]
param([switch]$CheckOnly, [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Push-Location $projectPath
try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '请先安装 Docker Desktop，并把数据目录配置在 E 盘。' }
  docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Docker 尚未就绪。请启动 Docker Desktop，等待引擎运行后重试。' }
  if (-not (Test-Path -LiteralPath '.env')) {
    if ($CheckOnly) { throw '缺少 .env。执行 scripts/start.ps1 可从模板生成本机配置。' }
    $configText = Get-Content -LiteralPath '.env.example' -Raw
    foreach ($key in @('JWT_SECRET','AGENT_SERVICE_TOKEN','POSTGRES_PASSWORD','REDIS_PASSWORD','MINIO_ROOT_PASSWORD')) {
      $bytes = New-Object byte[] 32
      $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
      try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
      $secret = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
      $configText = [regex]::Replace($configText, "(?m)^${key}=.*$", "${key}=$secret")
    }
    [IO.File]::WriteAllText((Join-Path $projectPath '.env'), $configText, (New-Object Text.UTF8Encoding($false)))
    Write-Host '已生成本机 .env；秘密仅写入此文件。已有配置不会被覆盖。'
  }
  $existing = Get-Content -LiteralPath '.env' -Raw
  foreach ($key in @('JWT_SECRET','AGENT_SERVICE_TOKEN','POSTGRES_PASSWORD','REDIS_PASSWORD','MINIO_ROOT_PASSWORD')) {
    $match = [regex]::Match($existing, "(?m)^${key}=([^\r\n]*)")
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups[1].Value) -or $match.Groups[1].Value.StartsWith('replace-with-')) {
      throw "请在 .env 中设置 $key。为保护已有配置，本脚本不会自动替换它。"
    }
  }
  docker compose config --quiet
  if ($LASTEXITCODE -ne 0) { throw 'Compose 配置检查失败，请按上方提示修正。' }
  Write-Host 'Docker 与配置检查通过。'
  if ($CheckOnly) { return }
  if ($NoBuild) { docker compose up -d } else { docker compose --parallel 1 up -d --build }
  if ($LASTEXITCODE -ne 0) { throw '启动失败。查看上方构建错误；已存储的数据会保留。' }
  Write-Host '服务已启动。docker compose ps 查看健康状态；docker compose logs --tail 80 backend 查看后端日志。'
  Write-Host '默认入口：http://localhost:5173（自定义端口以 .env 为准）。首次登录请设置新密码。'
} finally { Pop-Location }
