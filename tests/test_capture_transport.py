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
import threading

ROOT = Path(__file__).resolve().parents[1]
REQUESTS = []
FAILURES = []


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
                assert request['method'] == 'Runtime.evaluate'
                expression = request['params']['expression']
                assert request['params']['returnByValue'] is True
                REQUESTS.append(expression)
                if 'return d.before' in expression:
                    value = {'phase': 'combat', 'paused': [False, False]}
                elif 'const report=d.report()' in expression:
                    value = {'aimCalls': 12, 'keyCalls': [[1, 'fire', True, 'rightTrigger', 0, 1]],
                             'deliveredEvents': {'pointerdown': 1}, 'fragmentProbe': 'x' * 70000}
                else:
                    assert expression == 'window.__tls2InputCapture?.restore()'
                    value = None
                # The client must ignore an unsolicited protocol event and
                # reassemble a response split into continuation frames.
                self.send_frame(json.dumps({'method': 'Runtime.consoleAPICalled', 'params': {}}).encode())
                response = json.dumps({'id': request['id'], 'result': {
                    'result': {'type': 'object', 'value': value}}}).encode()
                split = min(20000, len(response) // 2)
                self.send_frame(response[:split], final=False)
                self.send_frame(response[split:], opcode=0)
        except EOFError:
            pass
        except Exception as error:
            FAILURES.append(repr(error))


def main():
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
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
