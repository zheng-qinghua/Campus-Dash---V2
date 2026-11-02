"""仅供独立验收进程使用的网络故障注入，不提供应用后门。"""
import socket
import threading
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError


class LostCommitAckProxy:
    """只丢弃目标流水所在事务的 COMMIT OK；使用无 TLS、客户端预编译的验收连接。"""
    QUERY_ATTRIBUTES = 1 << 27
    LEDGER_INSERT = re.compile(rb'^\s*INSERT\s+INTO\s+(?:`?\w+`?\s*\.\s*)?`?wallet_ledger`?\s*\(', re.I)

    def __init__(self, target_port=3307):
        self.target_port = target_port
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.port = self.listener.getsockname()[1]
        self.listener.listen()
        self.listener.settimeout(.5)
        self.running = True
        self.target_biz = None
        self.generation = 0
        self.dropped = threading.Event()
        self.connections = []
        self.lock = threading.Lock()
        self.observed = []
        threading.Thread(target=self.accept, daemon=True).start()

    @staticmethod
    def packet(stream):
        def exact(size):
            result = bytearray()
            while len(result) < size:
                chunk = stream.recv(size - len(result))
                if not chunk:
                    raise EOFError()
                result.extend(chunk)
            return bytes(result)
        header = exact(4)
        return header, exact(int.from_bytes(header[:3], 'little'))

    def arm(self, biz_no):
        with self.lock:
            self.target_biz = biz_no.encode()
            self.generation += 1
            self.dropped.clear()

    def accept(self):
        while self.running:
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            server = socket.create_connection(('127.0.0.1', self.target_port))
            self.connections.extend([client, server])
            state = {'handshake': False, 'query_attributes': False, 'matched': None,
                     'insert_pending': None, 'commit_pending': None}
            threading.Thread(target=self.forward, args=(client, server, state, True), daemon=True).start()
            threading.Thread(target=self.forward, args=(server, client, state, False), daemon=True).start()

    def forward(self, source, destination, state, upstream):
        try:
            while self.running:
                header, body = self.packet(source)
                with self.lock:
                    drop = self.observe_packet(state, body, upstream)
                if drop:
                    break
                destination.sendall(header + body)
        except (EOFError, OSError):
            pass
        finally:
            for stream in (source, destination):
                try:
                    stream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                stream.close()

    def observe_packet(self, state, body, upstream):
        """只解析本验收实际使用的协议；不修改转发内容，不把二进制头当 SQL。调用方持锁。"""
        if upstream:
            if not state['handshake']:
                state['handshake'] = True
                # HandshakeResponse41 前四字节为双方协商后的能力标志。
                state['query_attributes'] = bool(int.from_bytes(body[:4], 'little') & self.QUERY_ATTRIBUTES)
                return False
            if body[:1] != b'\x03':
                # 当前 Connector/J 默认为客户端预编译。显式切换到服务端预编译时留下
                # 可诊断证据，不猜测二进制参数、也不误伤别的事务。
                if body[:1] == b'\x16' and self.target_biz and b'wallet_ledger' in body.lower():
                    self.observed.append({'event': 'UNSUPPORTED_SERVER_PREPARE'})
                return False
            query = body[1:]
            if state['query_attributes']:
                # CLIENT_QUERY_ATTRIBUTES 下，即便没有属性也有 parameter_count=0、
                # parameter_set_count=1；Connector/J 9.x 的 COMMIT 就是 03 00 01 COMMIT。
                if query[:2] != b'\x00\x01':
                    self.observed.append({'event': 'UNSUPPORTED_QUERY_ATTRIBUTES'})
                    return False
                query = query[2:]
            command = query.strip().rstrip(b';').strip().upper()
            state['insert_pending'] = None
            if self.target_biz and self.LEDGER_INSERT.match(query):
                quoted_biz = b"'" + self.target_biz.replace(b'\\', b'\\\\').replace(b"'", b"\\'") + b"'"
                # 必须匹配完整字符串，settle:12 不得命中 settle:123。
                if quoted_biz in query:
                    state['insert_pending'] = (self.generation, self.target_biz)
            if command in (b'COMMIT', b'ROLLBACK'):
                matched = state['matched']
                state['matched'] = None
                if command == b'COMMIT' and matched and matched[0] == self.generation and not self.dropped.is_set():
                    state['commit_pending'] = matched
            return False
        pending_insert = state['insert_pending']
        state['insert_pending'] = None
        if pending_insert and body[:1] == b'\x00':
            # INSERT 错误不能武装 COMMIT，否则会截断失败事务后的无关提交。
            state['matched'] = pending_insert
        pending_commit = state['commit_pending']
        state['commit_pending'] = None
        if pending_commit and body[:1] == b'\x00' and pending_commit[0] == self.generation and not self.dropped.is_set():
            self.observed.append({'event': 'COMMIT_OK_DROPPED', 'bizNo': pending_commit[1].decode()})
            self.dropped.set()
            return True
        return False

    def close(self):
        self.running = False
        self.listener.close()
        for stream in self.connections:
            try:
                stream.close()
            except OSError:
                pass


class LostHttpResponseProxy:
    """读完真实 API 响应后阻断返回，供客户端原 UUID 重试与 API 重启验收。"""
    def __init__(self, base):
        self.received = threading.Event()
        self.release = threading.Event()
        self.result = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                headers = {key: value for key, value in self.headers.items()
                           if key.lower() not in ('host', 'connection', 'content-length')}
                req = Request(base + self.path, data=body, headers=headers, method='POST')
                try:
                    response = urlopen(req, timeout=90)
                except HTTPError as error:
                    response = error
                with response:
                    owner.result = {'http': response.status, 'body': response.read().decode()}
                owner.received.set()
                owner.release.wait(100)
                self.close_connection = True
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
