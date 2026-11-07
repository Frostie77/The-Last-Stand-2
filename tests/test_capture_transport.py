#!/usr/bin/env python3
"""Run the production capture script through Windows PowerShell and real WS I/O."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
REQUESTS = []
FAILURES = []
PERFORMANCE_CASE = False
FAIL_METHOD = None


def fixture_result(request):
    method = request['method']
    REQUESTS.append(method)
    if method == FAIL_METHOD:
        return None
    if method == 'Runtime.evaluate':
        expression = request['params']['expression']
        assert request['params']['returnByValue'] is True
        if PERFORMANCE_CASE:
            assert '__tls2PerformanceCapture' in expression
            value = {'before': {'phase': 'combat'}, 'pageFrameIntervals': {'maxMs': 220},
                     'aim': {'calls': 55}} if '.finish()' in expression else {'phase': 'combat'}
        elif 'return d.before' in expression:
            assert expression.endswith(('})(false)', '})(true)')), expression[-40:]
            value = ({'error': 'Enter an active fight'} if expression.endswith('})(true)')
                     else {'phase': 'combat', 'paused': [False, False]})
        elif 'const report=d.report()' in expression:
            value = {'aimCalls': 12, 'keyCalls': [[1, 'fire', True, 'rightTrigger', 0, 1]],
                     'deliveredEvents': {'pointerdown': 1}, 'fragmentProbe': 'x' * 70000}
        else:
            assert expression == 'window.__tls2InputCapture?.restore()'
            value = None
        return {'result': {'type': 'object', 'value': value}}
    assert PERFORMANCE_CASE
    if method == 'Performance.getMetrics':
        after = REQUESTS.count(method) == 2
        values = {'TaskDuration': 7 if after else 1, 'ScriptDuration': 5.7 if after else .7,
                  'LayoutDuration': .03 if after else .02, 'JSHeapUsedSize': 2097152 if after else 1048576}
        return {'metrics': [{'name': name, 'value': value} for name, value in values.items()]}
    if method == 'Profiler.stop':
        names = ['(root)', 'wasm-function[21]', 'sendPointer', '(idle)', '(garbage collector)']
        urls = ['', 'wasm://wasm/abc', 'https://appassets.androidplatform.net/assets/www/index.html', '', '']
        return {'profile': {'startTime': 1000000, 'endTime': 9000000,
                'nodes': [{'id': i+1, 'callFrame': {'functionName': name, 'url': urls[i],
                           'scriptId': str(i), 'lineNumber': 0, 'columnNumber': 0}} for i, name in enumerate(names)],
                'samples': [2, 2, 3, 4, 5, 2], 'timeDeltas': [2000] * 6,
                'fixturePadding': 'x' * 70000}}
    assert method in ['Performance.enable', 'Performance.disable', 'Profiler.enable', 'Profiler.disable',
                      'Profiler.start', 'Profiler.setSamplingInterval'], method
    if method == 'Profiler.setSamplingInterval':
        assert request['params']['interval'] == 2000
    return {}


class CdpFixture(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def read_exactly(self, count):
        data = self.rfile.read(count)
        if len(data) != count:
            raise EOFError()
        return data

    def send_frame(self, payload, opcode=1, final=True):
        size = len(payload)
        header = bytes([(0x80 if final else 0) | opcode])
        if size < 126:
            header += bytes([size])
        elif size < 65536:
            header += bytes([126]) + struct.pack('>H', size)
        else:
            header += bytes([127]) + struct.pack('>Q', size)
        self.wfile.write(header + payload)
        self.wfile.flush()

    def do_GET(self):
        try:
            key = self.headers['Sec-WebSocket-Key']
            accept = base64.b64encode(hashlib.sha1(
                (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101)
            self.send_header('Upgrade', 'websocket')
            self.send_header('Connection', 'Upgrade')
            self.send_header('Sec-WebSocket-Accept', accept)
            self.end_headers()
            self.close_connection = True
            self.connection.settimeout(25)
            while True:
                first, second = self.read_exactly(2)
                opcode, size = first & 15, second & 127
                if size == 126:
                    size = struct.unpack('>H', self.read_exactly(2))[0]
                elif size == 127:
                    size = struct.unpack('>Q', self.read_exactly(8))[0]
                mask = self.read_exactly(4) if second & 128 else None
                body = self.read_exactly(size)
                if mask:
                    body = bytes(value ^ mask[i % 4] for i, value in enumerate(body))
                if opcode == 8:
                    return
                assert opcode == 1 and first & 128, 'Unexpected client frame'
                request = json.loads(body)
                result = fixture_result(request)
                # The client must ignore an unsolicited protocol event and
                # reassemble a response split into continuation frames.
                self.send_frame(json.dumps({'method': 'Runtime.consoleAPICalled', 'params': {}}).encode())
                reply = {'id': request['id'], 'result': result} if result is not None else {
                    'id': request['id'], 'error': {'code': -32000, 'message': 'Simulated profiler error'}}
                response = json.dumps(reply).encode()
                split = min(20000, len(response) // 2)
                self.send_frame(response[:split], final=False)
                self.send_frame(response[split:], opcode=0)
        except EOFError:
            pass
        except Exception as error:
            FAILURES.append(repr(error))


def main():
    global PERFORMANCE_CASE, FAIL_METHOD
    parser = argparse.ArgumentParser()
    parser.add_argument('--powershell', default='powershell.exe')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 0), CdpFixture)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        url = f'ws://127.0.0.1:{server.server_port}/devtools/page/fixture'
        run = subprocess.run([args.powershell, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                              '-File', str(ROOT / 'capture-controller-input.ps1'),
                              '-WebSocketUrl', url, '-CaptureSeconds', '8'],
                             capture_output=True, text=True, timeout=40)
        assert run.returncode == 0, run.stdout[-3000:] + run.stderr[-3000:]
        assert not FAILURES, FAILURES
        lines = run.stdout.splitlines()
        report = json.loads(lines[lines.index('INPUT CAPTURE:') + 1])
        assert report['aimCalls'] == 12 and report['deliveredEvents']['pointerdown'] == 1
        assert report['fragmentProbe'] == 'x' * 70000
        assert len(REQUESTS) == 3, REQUESTS
        print('PASS: production PowerShell capture, WebSocket connection, protocol events, '
              'fragmented responses, report decoding and cleanup.')
        REQUESTS.clear()
        run = subprocess.run([args.powershell, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                              '-File', str(ROOT / 'capture-controller-input.ps1'),
                              '-WebSocketUrl', url, '-SawnOff'],
                             capture_output=True, text=True, timeout=25)
        assert run.returncode != 0 and 'Enter an active fight' in run.stderr, run.stderr
        assert not FAILURES, FAILURES
        assert REQUESTS == ['Runtime.evaluate', 'Runtime.evaluate'], REQUESTS
        print('PASS: the sawn-off option reports setup errors and cleans up before capture.')
        PERFORMANCE_CASE = True
        command = [args.powershell, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                   '-File', str(ROOT / 'capture-controller-input.ps1'), '-Performance',
                   '-WebSocketUrl', url, '-CaptureSeconds', '8']
        with tempfile.TemporaryDirectory() as output:
            REQUESTS.clear()
            run = subprocess.run(command + ['-OutputDirectory', output], capture_output=True, text=True, timeout=40)
            assert run.returncode == 0, run.stdout[-3000:] + run.stderr[-3000:]
            assert not FAILURES, FAILURES
            lines = run.stdout.splitlines()
            report = json.loads(lines[lines.index('PERFORMANCE CAPTURE:') + 1])
            assert report['profilerSeconds'] == 8
            assert report['mainThreadMilliseconds'] == {'TaskDuration': 6000, 'ScriptDuration': 5000, 'LayoutDuration': 10}
            assert report['jsHeapMegabytes'] == [1, 2]
            assert report['cpu']['sampleCount'] == 6
            assert report['cpu']['selfSamplePercent']['wasm'] == 50
            assert report['cpu']['hotspots'][0]['function'] == 'wasm-function[21]'
            assert report['page']['aim']['calls'] == 55
            profile = json.loads(Path(report['cpuProfile']).read_text(encoding='utf-8'))
            assert profile['fixturePadding'] == 'x' * 70000
            saved = list(Path(output).glob('*.json'))
            assert len(saved) == 1 and json.loads(saved[0].read_text(encoding='utf-8')) == report
            assert REQUESTS == ['Runtime.evaluate', 'Performance.enable', 'Profiler.enable',
                                'Profiler.setSamplingInterval', 'Performance.getMetrics', 'Profiler.start',
                                'Profiler.stop', 'Performance.getMetrics', 'Runtime.evaluate',
                                'Profiler.disable', 'Performance.disable', 'Runtime.evaluate'], REQUESTS
            print('PASS: Windows performance capture, metrics, sampled CPU summary, fragmented profile, '
                  'saved profile/report and profiler cleanup.')
            REQUESTS.clear()
            FAIL_METHOD = 'Profiler.setSamplingInterval'
            run = subprocess.run(command + ['-OutputDirectory', output], capture_output=True, text=True, timeout=25)
            assert run.returncode != 0
            assert 'Simulated profiler error' in run.stderr, run.stderr
            assert REQUESTS[-3:] == ['Profiler.disable', 'Performance.disable', 'Runtime.evaluate'], REQUESTS
            assert not FAILURES, FAILURES
            print('PASS: profiler setup failure still disables instrumentation and restores the page.')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
