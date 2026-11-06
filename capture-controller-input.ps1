param(
    [ValidateRange(8, 30)][int]$CaptureSeconds = 12,
    [string]$WebSocketUrl
)
$ErrorActionPreference = 'Stop'
$tls2Adb = $null
if ($env:LOCALAPPDATA) { $tls2Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$tls2ForwardPort = $null
$tls2Socket = $null
$script:tls2CdpRequest = 0

function Invoke-Tls2Evaluation([string]$Expression) {
    $script:tls2CdpRequest++
    $requestNumber = $script:tls2CdpRequest
    $message = @{id=$requestNumber; method='Runtime.evaluate'; params=@{
        expression=$Expression; returnByValue=$true
    }} | ConvertTo-Json -Depth 5 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($message)
    $timeout = [Threading.CancellationTokenSource]::new(10000)
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
            return $reply.result.result.value
        }
    } finally { $timeout.Dispose() }
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
            throw 'Open The Last Stand 2 on the Odin and leave it on the encounter where gamepad input fails.'
        }
        $tls2DebugName = "webview_devtools_remote_$tls2ProcessId"
        $tls2UnixSockets = (& $tls2Adb shell cat /proc/net/unix) -join "`n"
        if ($tls2UnixSockets -notmatch ("@" + [regex]::Escape($tls2DebugName) + '(?:\s|$)')) {
            throw 'The installed WebView does not expose the Debug app for inspection. Paste this message.'
        }
        $tls2ForwardPort = ((& $tls2Adb forward tcp:0 "localabstract:$tls2DebugName") -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or $tls2ForwardPort -notmatch '^\d+$') { throw 'ADB forwarding failed.' }
        $tls2Pages = Invoke-RestMethod -Uri "http://127.0.0.1:$tls2ForwardPort/json/list" -TimeoutSec 5
        $tls2Page = $tls2Pages | Where-Object {
            $_.url -eq 'https://appassets.androidplatform.net/assets/www/index.html'
        } | Select-Object -First 1
        if (-not $tls2Page) { throw 'The imported game page is not open.' }
        $WebSocketUrl = $tls2Page.webSocketDebuggerUrl
    }
    $tls2Socket = [Net.WebSockets.ClientWebSocket]::new()
    $tls2ConnectTimeout = [Threading.CancellationTokenSource]::new(10000)
    try {
        $tls2Socket.ConnectAsync([uri]$WebSocketUrl,$tls2ConnectTimeout.Token).GetAwaiter().GetResult() | Out-Null
    } finally { $tls2ConnectTimeout.Dispose() }
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
            try { Invoke-Tls2Evaluation 'window.__tls2InputCapture?.restore()' | Out-Null } catch {}
        }
        $tls2Socket.Dispose()
    }
    if ($tls2ForwardPort -match '^\d+$') {
        & $tls2Adb forward --remove "tcp:$tls2ForwardPort" | Out-Null
    }
}
