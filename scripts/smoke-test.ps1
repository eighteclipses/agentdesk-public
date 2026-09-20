$ErrorActionPreference = 'Stop'
$base = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }

# 管理员与员工会话
$admin = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$emp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"AgentDesk@2026"}' -WebSession $admin | Out-Null
Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"zhangsan","password":"AgentDesk@2026"}' -WebSession $emp | Out-Null
Invoke-RestMethod "$base/api/auth/password" -Method Post -ContentType 'application/json' -Body '{"currentPassword":"AgentDesk@2026","newPassword":"AgentDesk@2026"}' -WebSession $emp | Out-Null

Invoke-RestMethod "$base/actuator/health" | Out-Null

# 员工访问他人工单应 403（PS 5.1 无 -SkipHttpErrorCheck，用 try/catch 捕获）
$code = $null
try { Invoke-WebRequest "$base/api/tickets/2" -WebSession $emp -UseBasicParsing | Out-Null; $code = 200 }
catch { $code = [int]$_.Exception.Response.StatusCode }
if ($code -ne 403) { throw "expected employee access to return 403, got $code" }

# 分块检索：命中必须带锚点引用 article:x/version:y#cN
$hits = Invoke-RestMethod "$base/api/knowledge/search?q=VPN" -WebSession $emp
if (-not ($hits | Where-Object { $_.citation -like 'article:*#c*' })) { throw 'knowledge search did not return chunk anchor citation' }

$articleId = $hits[0].articleId
Invoke-RestMethod "$base/api/knowledge/articles/$articleId" -WebSession $emp | Out-Null
Invoke-RestMethod "$base/api/knowledge/articles/$articleId/versions" -WebSession $emp | Out-Null

Write-Output 'smoke tests passed'
