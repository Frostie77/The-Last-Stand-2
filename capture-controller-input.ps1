param(
    [ValidateRange(8, 30)][int]$CaptureSeconds = 12,
    [string]$WebSocketUrl,
    [switch]$Performance,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '.diagnostics')
)
$ErrorActionPreference = 'Stop'
$tls2Adb = $null
if ($env:LOCALAPPDATA) { $tls2Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$tls2ForwardPort = $null
$tls2Socket = $null
$script:tls2CdpRequest = 0
$script:tls2ProfilerEnabled = $false
$script:tls2ProfilerRunning = $false
$script:tls2MetricsEnabled = $false

function Invoke-Tls2Command([string]$Method, [hashtable]$Parameters = @{}) {
    $script:tls2CdpRequest++
    $requestNumber = $script:tls2CdpRequest
    $message = @{id=$requestNumber; method=$Method; params=$Parameters} | ConvertTo-Json -Depth 5 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($message)
    $timeout = [Threading.CancellationTokenSource]::new(20000)
    try {
        $segment = [ArraySegment[byte]]::new($bytes)
        $tls2Socket.SendAsync($segment, [Net.WebSockets.WebSocketMessageType]::Text,
            $true, $timeout.Token).GetAwaiter().GetResult() | Out-Null
        while ($true) {
            $stream = [IO.MemoryStream]::new()
            try {
                do {
                    $buffer = [byte[]]::new(65536)
                    $received = $tls2Socket.ReceiveAsync([ArraySegment[byte]]::new($buffer),
                        $timeout.Token).GetAwaiter().GetResult()
                    if ($received.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) {
                        throw 'The game closed its debugging connection.'
                    }
                    $stream.Write($buffer, 0, $received.Count)
                } while (-not $received.EndOfMessage)
                $reply = [Text.Encoding]::UTF8.GetString($stream.ToArray()) | ConvertFrom-Json
            } finally { $stream.Dispose() }
            if ($reply.id -ne $requestNumber) { continue }
            if ($reply.error) { throw ($reply.error | ConvertTo-Json -Compress) }
            if ($reply.result.exceptionDetails) {
                throw ($reply.result.exceptionDetails | ConvertTo-Json -Depth 5 -Compress)
            }
            return $reply.result
        }
    } finally { $timeout.Dispose() }
}

function Invoke-Tls2Evaluation([string]$Expression) {
    $reply = Invoke-Tls2Command 'Runtime.evaluate' @{expression=$Expression; returnByValue=$true}
    return $reply.result.value
}

function Get-Tls2MetricMap($Result) {
    $metrics = @{}
    foreach ($metric in $Result.metrics) { $metrics[$metric.name] = [double]$metric.value }
    return $metrics
}

function Get-Tls2CpuSummary($Profile) {
    $nodes = @{}
    foreach ($node in $Profile.nodes) { $nodes[[int]$node.id] = $node }
    $counts = @{}
    foreach ($id in $Profile.samples) { $counts[[int]$id]++ }
    $total = @($Profile.samples).Count
    $buckets = [ordered]@{wasm=0; ruffleJavaScript=0; hostJavaScript=0; idle=0; garbageCollection=0; other=0}
    $functions = @{}
    foreach ($id in $counts.Keys) {
        $frame = $nodes[$id].callFrame
        $name = [string]$frame.functionName
        $url = [string]$frame.url
        $count = $counts[$id]
        if ($name -eq '(idle)') { $bucket = 'idle' }
        elseif ($name -eq '(garbage collector)') { $bucket = 'garbageCollection' }
        elseif ($url -match '^wasm:|\.wasm($|[?#])' -or $name -match '^wasm-function\[') { $bucket = 'wasm' }
        elseif ($url -match '/ruffle/') { $bucket = 'ruffleJavaScript' }
        elseif ($url -match '/index\.html') { $bucket = 'hostJavaScript' }
        else { $bucket = 'other' }
        $buckets[$bucket] += $count
        $key = $name + '|' + $url + '|' + $frame.lineNumber
        if (-not $functions.ContainsKey($key)) {
            $display = $name
            if ($display.Length -gt 180) { $display = $display.Substring(0,180) + '...' }
            $functions[$key] = [ordered]@{function=$display; url=$url; line=$frame.lineNumber; samples=0; percent=0}
        }
        $functions[$key].samples += $count
    }
    foreach ($key in @($buckets.Keys)) {
        $buckets[$key] = if ($total) { [Math]::Round(100.0 * $buckets[$key] / $total,2) } else { 0 }
    }
    $hotspots = @($functions.Values | Sort-Object { $_.samples } -Descending | Select-Object -First 12)
    foreach ($hotspot in $hotspots) {
        $hotspot.percent = if ($total) { [Math]::Round(100.0 * $hotspot.samples / $total,2) } else { 0 }
    }
    return [ordered]@{sampleCount=$total; selfSamplePercent=$buckets; hotspots=$hotspots}
}

function Invoke-Tls2PerformanceCapture {
    $probe = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tools\performance-probe.js') -Raw
    Invoke-Tls2Evaluation $probe | Out-Null
    Invoke-Tls2Command 'Performance.enable' @{} | Out-Null
    $script:tls2MetricsEnabled = $true
    Invoke-Tls2Command 'Profiler.enable' @{} | Out-Null
    $script:tls2ProfilerEnabled = $true
    Invoke-Tls2Command 'Profiler.setSamplingInterval' @{interval=2000} | Out-Null
    $before = Get-Tls2MetricMap (Invoke-Tls2Command 'Performance.getMetrics' @{})
    Invoke-Tls2Command 'Profiler.start' @{} | Out-Null
    $script:tls2ProfilerRunning = $true
    Write-Host "Capturing performance for $CaptureSeconds seconds. Keep playing during the stutter."
    Write-Host 'Shoot at running zombies or hold the chainsaw; leave FrostFrontend closed.'
    Start-Sleep -Seconds $CaptureSeconds
    $profile = (Invoke-Tls2Command 'Profiler.stop' @{}).profile
    $script:tls2ProfilerRunning = $false
    $after = Get-Tls2MetricMap (Invoke-Tls2Command 'Performance.getMetrics' @{})
    $page = Invoke-Tls2Evaluation 'window.__tls2PerformanceCapture.finish()'
    $durations = [ordered]@{}
    foreach ($name in @('TaskDuration','ScriptDuration','LayoutDuration','RecalcStyleDuration')) {
        if ($before.ContainsKey($name) -and $after.ContainsKey($name)) {
            $durations[$name] = [Math]::Round(1000.0 * ($after[$name] - $before[$name]),2)
        }
    }
    $elapsed = [Math]::Round(($profile.endTime - $profile.startTime) / 1000000.0,3)
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $base = Join-Path $OutputDirectory ('performance-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6))
    $profilePath = $base + '.cpuprofile'
    [IO.File]::WriteAllText($profilePath, ($profile | ConvertTo-Json -Depth 30 -Compress), [Text.UTF8Encoding]::new($false))
    $report = [ordered]@{schema=1; profilerSeconds=$elapsed; page=$page; mainThreadMilliseconds=$durations;
        cpu=(Get-Tls2CpuSummary $profile); cpuProfile=$profilePath}
    if ($before.ContainsKey('JSHeapUsedSize') -and $after.ContainsKey('JSHeapUsedSize')) {
        $report.jsHeapMegabytes = @([Math]::Round($before.JSHeapUsedSize / 1048576.0,2),
            [Math]::Round($after.JSHeapUsedSize / 1048576.0,2))
    }
    $json = $report | ConvertTo-Json -Depth 15 -Compress
    [IO.File]::WriteAllText(($base + '.json'), $json, [Text.UTF8Encoding]::new($false))
    Write-Host 'PERFORMANCE CAPTURE:'
    Write-Output $json
    Write-Host 'Paste the PERFORMANCE CAPTURE output. The full CPU profile is also saved in .diagnostics.'
}

$tls2InstallProbe = @'
(() => {
  window.__tls2InputCapture?.restore();
  if (typeof tls2Ready === 'undefined' || !tls2Ready) throw Error('Wait until the imported game is loaded.');
  const p = document.querySelector('ruffle-player'), c = canvasForPointer();
  const state = () => ({phase:tls2Phase, paused:[tls2MenuPaused,tls2BackgroundPaused],
    focus:document.hasFocus(), active:document.activeElement?.tagName,
    held:Object.fromEntries(Object.entries(sources).filter(([,v])=>v.size).map(([k,v])=>[k,[...v]]))});
  const d = {before:state(), keys:[], aimCalls:0, events:{}, started:performance.now(),
    oldKey:window.tls2Key, oldAim:window.tls2Aim, listeners:[]};
  d.key = function(id, down, source) {
    const mapped = id === 'select' ? 'fire' : id;
    const before = sources[mapped]?.size || 0;
    try { return d.oldKey.apply(this,arguments); }
    finally {
      d.keys.push([Math.round(performance.now()-d.started),id,!!down,source,before,sources[mapped]?.size||0]);
      if (d.keys.length > 48) d.keys.shift();
    }
  };
  d.aim = function(x,y) { d.aimCalls++; d.lastAim=[x,y]; return d.oldAim.apply(this,arguments); };
  for (const [target,types] of [[p,['keydown','keyup']],[c,['pointerdown','pointerup','pointermove']]]) {
    if (!target) continue;
    for (const type of types) {
      const f = () => { d.events[type]=(d.events[type]||0)+1; };
      target.addEventListener(type,f); d.listeners.push([target,type,f]);
    }
  }
  d.report = () => ({before:d.before,after:state(),keyCalls:d.keys,aimCalls:d.aimCalls,
    lastAim:d.lastAim,deliveredEvents:d.events});
  d.restore = () => {
    if (window.tls2Key === d.key) window.tls2Key=d.oldKey;
    if (window.tls2Aim === d.aim) window.tls2Aim=d.oldAim;
    for (const [target,type,f] of d.listeners) target.removeEventListener(type,f);
    delete window.__tls2InputCapture;
  };
  window.__tls2InputCapture=d; window.tls2Key=d.key; window.tls2Aim=d.aim;
  return d.before;
})()
'@
$tls2CollectProbe = @'
(() => { const d=window.__tls2InputCapture; if(!d) throw Error('The game reloaded during capture.');
const report=d.report(); d.restore(); return report; })()
'@

try {
    if (-not $WebSocketUrl) {
        if (-not $tls2Adb -or -not (Test-Path $tls2Adb)) { throw "ADB not found: $tls2Adb" }
        $tls2ProcessId = ((& $tls2Adb shell pidof -s io.github.frostie77.laststand2) -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or $tls2ProcessId -notmatch '^\d+$') {
            throw 'Open The Last Stand 2 on the Odin and leave the imported game running.'
        }
        $tls2DebugName = "webview_devtools_remote_$tls2ProcessId"
        # Android builds can hide socket names in /proc/net/unix. Connect to
        # this app's known endpoint instead of treating that listing as proof.
        $tls2ForwardPort = ((& $tls2Adb forward tcp:0 "localabstract:$tls2DebugName") -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or $tls2ForwardPort -notmatch '^\d+$') { throw 'ADB forwarding failed.' }
        try {
            $tls2Pages = Invoke-RestMethod -Uri "http://127.0.0.1:$tls2ForwardPort/json/list" -TimeoutSec 5
        } catch {
            throw 'The game WebView could not be reached. Install the updated Debug APK, reopen the imported game, then run this capture again.'
        }
        $tls2Page = $tls2Pages | Where-Object {
            $_.url -match '^https://appassets\.androidplatform\.net/assets/www/index\.html(?:\?|$)'
        } | Select-Object -First 1
        if (-not $tls2Page) { throw 'The imported game page is not open.' }
        $WebSocketUrl = $tls2Page.webSocketDebuggerUrl
    }
    $tls2Socket = [Net.WebSockets.ClientWebSocket]::new()
    $tls2ConnectTimeout = [Threading.CancellationTokenSource]::new(10000)
    try {
        $tls2Socket.ConnectAsync([uri]$WebSocketUrl,$tls2ConnectTimeout.Token).GetAwaiter().GetResult() | Out-Null
    } finally { $tls2ConnectTimeout.Dispose() }
    if ($Performance) {
        Invoke-Tls2PerformanceCapture
        return
    }
    Invoke-Tls2Evaluation $tls2InstallProbe | Out-Null
    Write-Host "Capturing for $CaptureSeconds seconds. Move BOTH sticks and press Fire, Reload and Switch on the Odin."
    Write-Host 'Use the gamepad only; leave FrostFrontend closed.'
    Start-Sleep -Seconds $CaptureSeconds
    $tls2Report = Invoke-Tls2Evaluation $tls2CollectProbe
    Write-Host 'INPUT CAPTURE:'
    $tls2Report | ConvertTo-Json -Depth 12 -Compress
    Write-Host 'Paste the INPUT CAPTURE output.'
} finally {
    if ($tls2Socket) {
        if ($tls2Socket.State -eq [Net.WebSockets.WebSocketState]::Open) {
            if ($Performance) {
                if ($script:tls2ProfilerRunning) {
                    try { Invoke-Tls2Command 'Profiler.stop' @{} | Out-Null } catch {}
                }
                if ($script:tls2ProfilerEnabled) {
                    try { Invoke-Tls2Command 'Profiler.disable' @{} | Out-Null } catch {}
                }
                if ($script:tls2MetricsEnabled) {
                    try { Invoke-Tls2Command 'Performance.disable' @{} | Out-Null } catch {}
                }
                try { Invoke-Tls2Evaluation 'window.__tls2PerformanceCapture?.restore()' | Out-Null } catch {}
            } else {
                try { Invoke-Tls2Evaluation 'window.__tls2InputCapture?.restore()' | Out-Null } catch {}
            }
        }
        $tls2Socket.Dispose()
    }
    if ($tls2ForwardPort -match '^\d+$') {
        & $tls2Adb forward --remove "tcp:$tls2ForwardPort" | Out-Null
    }
}
