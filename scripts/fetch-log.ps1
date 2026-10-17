<#
.SYNOPSIS
    从 acidisland 测试服下载 Minecraft 服务器日志（默认 logs/latest.log）。

.DESCRIPTION
    通过 SSH 密钥认证（gj999@gw.evermc.net）把远程日志抓回本地 server-logs/ 目录，
    默认会：
      * 覆盖保存一份 latest.log
      * 另存一份带时间戳的历史副本（可用 -NoHistory 关闭）
      * 打印文件大小、远程修改时间、总行数，以及 ERROR / WARN / 异常堆栈行计数
    可选地只显示最后 N 行（-Tail）或只看匹配的行（-Grep / -ErrorsOnly）。

.PARAMETER Remote
    要下载的文件。相对路径按 -RemoteLogDir 解析；也可以给绝对路径或别的日志名，
    例如 '2026-08-18-2.log.gz'。

.PARAMETER OutDir
    本地保存目录，默认是仓库根目录下的 server-logs/。

.PARAMETER Tail
    下载完成后在控制台显示最后多少行，0 表示不显示。

.PARAMETER Grep
    仅显示匹配该正则的行（从本地副本里筛，不在服务器上跑命令）。

.PARAMETER ErrorsOnly
    等价于 -Grep 'ERROR|SEVERE|FATAL|Exception|Caused by:'。

.PARAMETER SinceRestart
    只保留最近一次服务器启动之后的日志（按 'Starting minecraft server version'
    或 'Done (x.xxxs)!' 等启动标记切分），并额外写出 --boot.log。

.PARAMETER List
    只列出远程 logs/ 目录里的文件，然后退出。

.EXAMPLE
    .\scripts\fetch-log.ps1
    拉取 latest.log，显示统计信息。

.EXAMPLE
    .\scripts\fetch-log.ps1 -ErrorsOnly -NoHistory
    拉取后只打印错误和异常相关的行。

.EXAMPLE
    .\scripts\fetch-log.ps1 -Tail 200 -NoHistory
    只看最后 200 行，不保留时间戳副本。

.EXAMPLE
    .\scripts\fetch-log.ps1 -SinceRestart -Tail 100
    重启服务器后，只看本次启动以来的日志。

.EXAMPLE
    .\scripts\fetch-log.ps1 -Remote 2026-08-18-1.log.gz
    拉取已轮转的旧日志（gz 原样下载，不做解压）。
#>
[CmdletBinding()]
param(
    [string]$Remote = 'latest.log',
    [string]$OutDir,
    [int]$Tail = 0,
    [string]$Grep,
    [switch]$ErrorsOnly,
    [switch]$SinceRestart,
    [switch]$List,
    [switch]$NoHistory,

    [string]$User = 'gj999',
    [string]$SshHost = 'gw.evermc.net',
    [int]$Port = 22,
    [string]$RemoteLogDir = '/nfs/3406e49acd/minecraft/acidisland-test/logs'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 工具函数 ---

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Fail([string]$Message) {
    Write-Host "!!  $Message" -ForegroundColor Red
    exit 1
}

# 调用外部程序并捕获输出：原生命令的 stderr 在 $ErrorActionPreference='Stop'
# 下会被当成终止错误，所以这里局部放宽，再用退出码自行判断。
function Invoke-Native {
    param([string]$FilePath, [string[]]$ArgList)

    $ErrorActionPreference = 'Continue'
    $output = & $FilePath @ArgList 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output   = (($output | Where-Object { $_ -ne $null }) -join [Environment]::NewLine).Trim()
    }
}

# 驱动 ssh / scp；BatchMode 保证密钥不可用时立即失败，而不是卡在密码提示上
$sshArgs = @(
    '-o', 'BatchMode=yes'
    '-o', 'ConnectTimeout=10'
    '-o', 'StrictHostKeyChecking=accept-new'
    '-p', $Port
    "$User@$SshHost"
)
$scpArgs = @(
    '-o', 'BatchMode=yes'
    '-o', 'ConnectTimeout=10'
    '-o', 'StrictHostKeyChecking=accept-new'
    '-P', $Port
)

# ------------------------------------------------------------ 远程路径解析 ---

if ([string]::IsNullOrWhiteSpace($Remote)) { Fail '-Remote 不能为空。' }

if ($Remote.StartsWith('/')) {
    $remotePath = $Remote
} else {
    $remotePath = "$($RemoteLogDir.TrimEnd('/'))/$Remote"
}

if (-not $OutDir) {
    $OutDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'server-logs'
}

$target = "$User@$($SshHost)"

# ------------------------------------------------------------------- 预检 ----

Write-Step "检查 SSH 连接 $target ..."

# -List 时只需要目录可读，其余情况检查目标文件
if ($List) {
    $probeTest = "test -d '$RemoteLogDir'"
} else {
    $probeTest = "test -r '$remotePath'"
}

$probe = Invoke-Native 'ssh' ($sshArgs + "$probeTest && echo PROBE_OK || echo PROBE_MISSING")
if ($probe.ExitCode -ne 0) {
    Fail ("SSH 连接失败（退出码 {0}）：{1}`n    确认密钥认证可用：ssh -o BatchMode=yes {2} true" -f `
            $probe.ExitCode, $probe.Output, $target)
}
if ($probe.Output -notmatch 'PROBE_OK') {
    Fail "远程文件不存在或不可读：$remotePath"
}
Write-Ok '连接正常，远程文件可读。'

if ($List) {
    Write-Step "远程日志目录 $RemoteLogDir"
    $listing = Invoke-Native 'ssh' ($sshArgs + "ls -lht '$RemoteLogDir' | head -40")
    Write-Host $listing.Output
    exit 0
}

# 只取文件名，避免把路径写成本地路径之外的文件
$remoteName = [System.IO.Path]::GetFileName($Remote)
if ([string]::IsNullOrWhiteSpace($remoteName)) { $remoteName = 'latest.log' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ------------------------------------------------------------------- 下载 ----

$localLatest = Join-Path $OutDir $remoteName
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$baseName = [System.IO.Path]::GetFileNameWithoutExtension($remoteName)
$extension = [System.IO.Path]::GetExtension($remoteName)
$localHistory = Join-Path $OutDir "$baseName-$stamp$extension"

Write-Step "下载 ${target}:$remotePath"
$transfer = Invoke-Native 'scp' ($scpArgs + @("${target}:$remotePath", $localLatest))
if ($transfer.ExitCode -ne 0) {
    Fail ("scp 下载失败（退出码 {0}）：`n    {1}" -f $transfer.ExitCode, $transfer.Output)
}

$file = Get-Item -LiteralPath $localLatest
Write-Ok ("已保存：{0}  ({1:N1} KB)" -f $file.FullName, ($file.Length / 1KB))

$remoteMtime = Invoke-Native 'ssh' ($sshArgs + "date -r '$remotePath' '+%Y-%m-%d %H:%M:%S %z'")
if ($remoteMtime.ExitCode -eq 0) { Write-Ok "远程修改时间：$($remoteMtime.Output)" }
Write-Ok ("本地下载时间：{0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

# 轮转日志（.gz 等）是压缩二进制，不做文本统计和检索
if ($extension -match '^\.(gz|zip|bz2|xz|tar|7z)$') {
    if (-not $NoHistory) {
        Copy-Item -LiteralPath $localLatest -Destination $localHistory -Force
        Write-Ok "历史副本：$([System.IO.Path]::GetFileName($localHistory))"
    }
    Write-Step '已压缩的轮转日志，跳过文本统计'
    Write-Host ''
    Write-Host "本地文件：$($file.FullName)" -ForegroundColor Green
    exit 0
}

# ------------------------------------------------------- 历史副本 / 启动切分 -

$text = Get-Content -LiteralPath $localLatest -Raw -Encoding UTF8
$lines = if ([string]::IsNullOrEmpty($text)) { @() } else { $text -split "`r?`n" }
if ($lines.Length -gt 0 -and $lines[$lines.Length - 1] -eq '') {
    $lines = $lines[0..($lines.Length - 2)]
}

if (-not $NoHistory) {
    Copy-Item -LiteralPath $localLatest -Destination $localHistory -Force
    Write-Ok "历史副本：$([System.IO.Path]::GetFileName($localHistory))"
}

if ($SinceRestart) {
    # 从末尾往前找最近一次启动标记，只保留它之后的内容
    $bootPattern = 'Starting minecraft server version|Done \(\d+(\.\d+)?s\)!'
    $bootIndex = -1
    for ($i = $lines.Length - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match $bootPattern) { $bootIndex = $i; break }
    }
    if ($bootIndex -ge 0) {
        $lines = $lines[$bootIndex..($lines.Length - 1)]
        $bootFile = Join-Path $OutDir "$baseName-$stamp--boot$extension"
        Set-Content -LiteralPath $bootFile -Value $lines -Encoding UTF8
        Write-Ok ("本次启动起始行：第 {0} 行（另存 {1}）" -f ($bootIndex + 1), [System.IO.Path]::GetFileName($bootFile))
    } else {
        Write-Host '    未找到启动标记，保留全部日志。' -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------------- 统计 ----

$total = $lines.Length
$errorCount = @($lines | Where-Object { $_ -match '\b(ERROR|SEVERE|FATAL)\b' }).Count
$warnCount = @($lines | Where-Object { $_ -match '\bWARN\b' }).Count
$exceptionCount = @($lines | Where-Object { $_ -match 'Exception|Caused by:' }).Count

Write-Step '统计'
Write-Ok ("总行数 {0} | ERROR {1} | WARN {2} | 异常堆栈行 {3}" -f $total, $errorCount, $warnCount, $exceptionCount)

# ------------------------------------------------------------------- 查看 ----

if ($ErrorsOnly -and -not $Grep) {
    $Grep = 'ERROR|SEVERE|FATAL|Exception|Caused by:'
}

if ($Grep) {
    $matched = @($lines | Where-Object { $_ -match $Grep })
    Write-Step "匹配 '$Grep' 的行：$($matched.Count) 行"
    if ($matched.Count -gt 0) {
        Write-Host ''
        $matched | ForEach-Object { Write-Host $_ }
    }
    Write-Host ''
    Write-Host "本地文件：$($file.FullName)" -ForegroundColor Green
    exit 0
}

if ($Tail -gt 0) {
    $take = [Math]::Min($Tail, $lines.Length)
    if ($take -gt 0) {
        Write-Step "最后 $take 行"
        Write-Host ''
        $lines[($lines.Length - $take)..($lines.Length - 1)] | ForEach-Object { Write-Host $_ }
    }
}

Write-Host ''
Write-Host "本地文件：$($file.FullName)" -ForegroundColor Green
