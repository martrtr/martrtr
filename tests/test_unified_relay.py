#!/usr/bin/env python3
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

TOKEN = b"f2f3a025173941f9cb1d297eba2c0469"
ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "footdraw_server_unified.py"


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def recvn(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("socket closed")
        out += chunk
    return bytes(out)


class TextPeer:
    def __init__(self, role, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=2)
        self.sock.settimeout(2)
        if role == "phone":
            hello = b"HELLO PHONE " + TOKEN + b" testdev 1080 2460\n"
        else:
            hello = b"HELLO OP " + TOKEN + b"\n"
        self.sock.sendall(hello)
        self.buf = bytearray()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def send(self, line):
        self.sock.sendall((line + "\n").encode())

    def line(self):
        while b"\n" not in self.buf:
            data = self.sock.recv(65536)
            if not data:
                raise EOFError("peer closed")
            self.buf += data
        raw, _, rest = self.buf.partition(b"\n")
        self.buf = bytearray(rest)
        return raw.decode("utf-8", "replace").strip()

    def until(self, prefix, limit=30):
        for _ in range(limit):
            line = self.line()
            if line.startswith(prefix):
                return line
        raise AssertionError(f"did not receive {prefix!r}")


class RelayIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.port = free_port()
        self.proc = None
        self.start_server()

    def tearDown(self):
        self.stop_server()
        self.tmp.cleanup()

    def start_server(self):
        self.proc = subprocess.Popen(
            [sys.executable, "-u", str(SERVER),
             "--tcp-host", "127.0.0.1", "--tcp-port", str(self.port),
             "--udp-host", "127.0.0.1", "--udp-port", str(self.port),
             "--data", self.tmp.name],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        deadline = time.time() + 5
        while time.time() < deadline:
            if self.proc.poll() is not None:
                out = self.proc.stdout.read()
                self.fail("server exited: " + out)
            try:
                s = socket.create_connection(("127.0.0.1", self.port), timeout=.15)
                s.close()
                return
            except OSError:
                time.sleep(.03)
        self.fail("server did not start")

    def stop_server(self):
        if self.proc is None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=2)
        self.proc = None

    def initial_sync(self, phone, op):
        self.assertTrue(phone.until("STATE ").startswith("STATE 1 "))
        phone.until("CFG ")
        phone.until("MODE ")
        phone.until("GVIEW ")
        self.assertTrue(op.until("STATE ").startswith("STATE 1 "))
        op.until("CFG ")
        op.until("MODE ")
        op.until("GVIEW ")
        op.until("RESET ")
        op.until("SYNCED")

    def test_full_protocol_and_persistence(self):
        phone = TextPeer("phone", self.port)
        op = TextPeer("op", self.port)
        self.initial_sync(phone, op)

        op.send("BG 112233")
        self.assertIn("112233", phone.until("CFG "))
        op.until("CFG ")

        op.send("BRUSH aabbcc")
        cfg = phone.until("CFG ")
        self.assertIn("aabbcc", cfg)
        op.until("CFG ")

        op.send("ORIENT 1")
        self.assertIn(" 1 ", phone.until("CFG "))
        op.until("CFG ")
        op.until("STATE ")

        op.send("CLIP 1 0.1 0.2 0.8 0.9")
        cfg = phone.until("CFG ")
        self.assertIn(" 1 0.100000 0.200000 0.800000 0.900000", cfg)
        op.until("CFG ")

        op.send("MODE 1")
        self.assertEqual(phone.until("MODE "), "MODE 1")
        self.assertEqual(op.until("MODE "), "MODE 1")

        op.send("GVIEW 3.5 -2.25 0.004")
        self.assertEqual(phone.until("GVIEW "), "GVIEW 3.5 -2.25 0.004")

        phone.send("P 1 1 s1 D 0.25 0.5 1.0 123456 aabbcc")
        self.assertEqual(phone.until("ACKP "), "ACKP 1")
        event = op.until("O ")
        self.assertIn(" s1 D 0.250000000 0.500000000 ", event)
        self.assertTrue(event.endswith("aabbcc"))

        # Camera JPEG relay keeps frame payload byte-exact.
        sub = socket.create_connection(("127.0.0.1", self.port), timeout=2)
        sub.settimeout(2)
        sub.sendall(b"VIDEO_SUB OP " + TOKEN + b"\n")
        vtx = socket.create_connection(("127.0.0.1", self.port), timeout=2)
        vtx.settimeout(2)
        vtx.sendall(b"VIDEO PHONE " + TOKEN + b" testdev JPEG/1\n")
        jpeg = b"\xff\xd8" + os.urandom(700) + b"\xff\xd9"
        vtx.sendall(struct.pack("!I", len(jpeg)) + jpeg)
        seq, n = struct.unpack("!II", recvn(sub, 8))
        self.assertGreater(seq, 0)
        self.assertEqual(n, len(jpeg))
        self.assertEqual(recvn(sub, n), jpeg)
        sub.close(); vtx.close()

        # Operator -> phone UDP audio relay.
        p_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        o_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        p_udp.bind(("127.0.0.1", 0)); o_udp.bind(("127.0.0.1", 0))
        p_udp.settimeout(2); o_udp.settimeout(2)
        addr = ("127.0.0.1", self.port)
        p_udp.sendto(b"FDH1" + TOKEN, addr)
        o_udp.sendto(b"FDOH" + TOKEN, addr)
        time.sleep(.05)
        tail = struct.pack("!IH", 77, 2) + b"\x01\x02\x03\x04"
        o_udp.sendto(b"FDO1" + TOKEN + tail, addr)
        audio, _ = p_udp.recvfrom(4096)
        self.assertEqual(audio, b"FDA1" + tail)

        # Phone -> operator path remains supported for headset-mic work.
        p_udp.sendto(b"FDP1" + TOKEN + tail, addr)
        reverse, _ = o_udp.recvfrom(4096)
        self.assertEqual(reverse, b"FDR1" + tail)
        p_udp.close(); o_udp.close()

        phone.close(); op.close()
        time.sleep(.08)
        self.stop_server()
        self.start_server()

        op2 = TextPeer("op", self.port)
        self.assertTrue(op2.until("STATE ").startswith("STATE 1 "))
        cfg = op2.until("CFG ")
        self.assertIn("112233", cfg)
        self.assertIn("aabbcc", cfg)
        self.assertTrue(cfg.split()[3] == "1")
        self.assertTrue(cfg.split()[4] == "1")
        self.assertEqual(op2.until("MODE "), "MODE 1")
        self.assertEqual(op2.until("GVIEW "), "GVIEW 3.5 -2.25 0.004")
        op2.until("RESET ")
        replay = op2.until("O ")
        self.assertIn(" s1 D ", replay)
        op2.until("SYNCED")
        op2.close()


if __name__ == "__main__":
    unittest.main()
