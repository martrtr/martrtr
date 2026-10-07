#!/usr/bin/env python3
import argparse
import asyncio
import os
import struct
import time
from pathlib import Path

TOKEN = b"f2f3a025173941f9cb1d297eba2c0469"
TOKEN_S = TOKEN.decode("ascii")
MAX_LINE = 8192
MAX_FRAME = 2_000_000

class BoardState:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.state_file = data_dir / "footdraw.state"
        self.events_file = data_dir / "footdraw.events"
        self.epoch = 1
        self.vx = 0.0
        self.vy = 0.0
        self.phone_w = 1080
        self.phone_h = 2460
        self.bg = 0x000000
        self.brush = 0xEBEEF4
        self.land = 0
        self.clip = 0
        self.cl = 0.0
        self.ct = 0.0
        self.cr = 1.0
        self.cb = 1.0
        self.gyro = 0
        self.gcx = 0.5
        self.gcy = 1.0
        self.gwpp = 1.0 / 500.0
        self.next_gseq = 1
        self.events = []
        self.seen = {}
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.load()

    def _default_gyro(self):
        a = max(self.phone_w, self.phone_h) / max(1, min(self.phone_w, self.phone_h))
        ww, wh = (a, 1.0) if self.land else (1.0, a)
        self.gcx = self.vx + ww / 2.0
        self.gcy = self.vy + wh / 2.0
        self.gwpp = 1.0 / 500.0

    def load(self):
        try:
            p = self.state_file.read_text().strip().split()
            if len(p) >= 5:
                self.epoch = int(p[0]); self.vx = float(p[1]); self.vy = float(p[2])
                self.phone_w = int(p[3]); self.phone_h = int(p[4])
            if len(p) >= 13:
                self.bg = int(p[5], 16); self.brush = int(p[6], 16)
                self.land = 1 if int(p[7]) else 0; self.clip = 1 if int(p[8]) else 0
                self.cl, self.ct, self.cr, self.cb = map(float, p[9:13])
            if len(p) >= 17:
                self.gyro = 1 if int(p[13]) else 0
                self.gcx, self.gcy, self.gwpp = map(float, p[14:17])
            else:
                self._default_gyro()
        except Exception:
            self._default_gyro()
            self.persist()

        try:
            with self.events_file.open("r", encoding="utf-8") as f:
                for line in f:
                    a = line.strip().split()
                    if len(a) < 9:
                        continue
                    try:
                        gseq = int(a[0]); epoch = int(a[1]); dev = a[2]; devseq = int(a[3])
                        stroke, kind = a[4], a[5]
                        x, y, p = float(a[6]), float(a[7]), float(a[8])
                        color = int(a[9], 16) if len(a) >= 10 else self.brush
                    except Exception:
                        continue
                    self.next_gseq = max(self.next_gseq, gseq + 1)
                    if epoch == self.epoch:
                        e = (gseq, epoch, dev, devseq, stroke, kind, x, y, p, color & 0xFFFFFF)
                        self.events.append(e); self.seen[(epoch, dev, devseq)] = gseq
        except FileNotFoundError:
            self.events_file.touch()

    def persist(self):
        tmp = self.state_file.with_suffix(".tmp")
        line = (
            f"{self.epoch} {self.vx:.17g} {self.vy:.17g} {self.phone_w} {self.phone_h} "
            f"{self.bg & 0xFFFFFF:06x} {self.brush & 0xFFFFFF:06x} {self.land} {self.clip} "
            f"{self.cl:.17g} {self.ct:.17g} {self.cr:.17g} {self.cb:.17g} "
            f"{self.gyro} {self.gcx:.17g} {self.gcy:.17g} {self.gwpp:.17g}\n"
        )
        with tmp.open("w", encoding="utf-8") as f:
            f.write(line); f.flush(); os.fsync(f.fileno())
        os.replace(tmp, self.state_file)

    def add_event(self, dev, devseq, epoch, stroke, kind, x, y, p, color):
        key = (epoch, dev, devseq)
        if key in self.seen:
            return self.seen[key]
        if epoch != self.epoch:
            return None
        gseq = self.next_gseq; self.next_gseq += 1
        color &= 0xFFFFFF
        line = f"{gseq} {epoch} {dev} {devseq} {stroke} {kind} {x:.17g} {y:.17g} {p:.17g} {color:06x}\n"
        with self.events_file.open("a", encoding="utf-8") as f:
            f.write(line); f.flush(); os.fsync(f.fileno())
        e = (gseq, epoch, dev, devseq, stroke, kind, x, y, p, color)
        self.events.append(e); self.seen[key] = gseq
        return gseq

    def clear(self):
        self.epoch += 1
        self.events.clear(); self.seen.clear()
        self.events_file.write_text("")
        self.persist()

class Peer:
    def __init__(self, writer):
        self.writer = writer
        self.lock = asyncio.Lock()
        self.dead = False

    async def send(self, line, timeout=2.0):
        if self.dead:
            return False
        try:
            async with self.lock:
                self.writer.write((line + "\n").encode("utf-8"))
                await asyncio.wait_for(self.writer.drain(), timeout)
            return True
        except Exception:
            self.dead = True
            try: self.writer.close()
            except Exception: pass
            return False

    def close(self):
        self.dead = True
        try: self.writer.close()
        except Exception: pass

class VideoSub:
    def __init__(self, writer):
        self.writer = writer
        self.queue = asyncio.Queue(maxsize=1)
        self.dead = False
        self.task = asyncio.create_task(self.loop())

    def offer(self, seq, jpeg):
        if self.dead:
            return
        if self.queue.full():
            try: self.queue.get_nowait()
            except Exception: pass
        try: self.queue.put_nowait((seq, jpeg))
        except Exception: pass

    async def loop(self):
        try:
            while True:
                seq, jpeg = await self.queue.get()
                self.writer.write(struct.pack("!II", seq & 0xFFFFFFFF, len(jpeg)) + jpeg)
                await asyncio.wait_for(self.writer.drain(), 2.0)
        except Exception:
            self.dead = True
            try: self.writer.close()
            except Exception: pass

    def close(self):
        self.dead = True
        self.task.cancel()
        try: self.writer.close()
        except Exception: pass

class Relay:
    def __init__(self, data_dir):
        self.state = BoardState(data_dir)
        self.operators = set()
        self.phones = set()
        self.video_subs = set()
        self.video_seq = 0
        self.latest_video = None
        self.udp_transport = None
        self.phone_audio = None
        self.phone_audio_seen = 0.0
        self.operator_audio = None
        self.operator_audio_seen = 0.0

    async def broadcast(self, peers, line):
        dead = []
        for p in list(peers):
            if not await p.send(line):
                dead.append(p)
        for p in dead:
            peers.discard(p)

    async def send_state(self, p):
        s = self.state
        await p.send(f"STATE {s.epoch} {s.vx:.9f} {s.vy:.9f} {s.phone_w} {s.phone_h}")
        await p.send(f"CFG {s.bg:06x} {s.brush:06x} {s.land} {s.clip} {s.cl:.6f} {s.ct:.6f} {s.cr:.6f} {s.cb:.6f}")
        await p.send(f"MODE {s.gyro}")
        await p.send(f"GVIEW {s.gcx:.12g} {s.gcy:.12g} {s.gwpp:.12g}")

    async def handle_phone(self, reader, writer, dev, w, h):
        p = Peer(writer); self.phones.add(p)
        s = self.state; s.phone_w = max(1, w); s.phone_h = max(1, h); s.persist()
        print("phone", dev, writer.get_extra_info("peername"), flush=True)
        await self.send_state(p)
        await self.broadcast(self.operators, f"STATE {s.epoch} {s.vx:.9f} {s.vy:.9f} {s.phone_w} {s.phone_h}")
        try:
            while True:
                raw = await reader.readline()
                if not raw: break
                line = raw.decode("utf-8", "replace").strip()
                if line == "PING":
                    await p.send("PONG"); continue
                if line.startswith("GC "):
                    await self.broadcast(self.operators, line); continue
                if not line.startswith("P "):
                    continue
                a = line.split()
                if len(a) < 9:
                    continue
                try:
                    devseq = int(a[1]); epoch = int(a[2]); stroke = a[3]; kind = a[4]
                    x, y, pressure = float(a[5]), float(a[6]), float(a[7])
                    color = int(a[9], 16) if len(a) >= 10 else s.brush
                except Exception:
                    continue
                if epoch != s.epoch:
                    await p.send(f"CLEAR {s.epoch}"); continue
                gseq = s.add_event(dev, devseq, epoch, stroke, kind, x, y, pressure, color)
                if gseq is None:
                    continue
                await p.send(f"ACKP {devseq}")
                await self.broadcast(self.operators,
                    f"O {gseq} {epoch} {stroke} {kind} {x:.9f} {y:.9f} {pressure:.5f} {color & 0xFFFFFF:06x}")
        finally:
            self.phones.discard(p); p.close()
            print("phone-out", dev, flush=True)

    async def handle_operator(self, reader, writer):
        p = Peer(writer)
        print("operator", writer.get_extra_info("peername"), flush=True)
        await self.send_state(p)
        s = self.state
        await p.send(f"RESET {s.epoch}")
        for e in list(s.events):
            gseq, epoch, dev, devseq, stroke, kind, x, y, pressure, color = e
            if not await p.send(f"O {gseq} {epoch} {stroke} {kind} {x:.9f} {y:.9f} {pressure:.5f} {color:06x}"):
                return
        await p.send("SYNCED")
        self.operators.add(p)
        try:
            while True:
                raw = await reader.readline()
                if not raw: break
                line = raw.decode("utf-8", "replace").strip()
                if line == "PING":
                    await p.send("PONG"); continue
                a = line.split()
                try:
                    if len(a) >= 3 and a[0] == "VIEW":
                        s.vx, s.vy = float(a[1]), float(a[2]); s.persist()
                        await self.broadcast(self.phones, f"VIEW {s.vx:.9f} {s.vy:.9f}")
                    elif len(a) >= 2 and a[0] == "BG":
                        s.bg = int(a[1], 16) & 0xFFFFFF; s.persist(); await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "BRUSH":
                        s.brush = int(a[1], 16) & 0xFFFFFF; s.persist(); await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "ORIENT":
                        s.land = 1 if int(a[1]) else 0; s.persist(); await self.broadcast_cfg()
                        await self.broadcast(self.operators, f"STATE {s.epoch} {s.vx:.9f} {s.vy:.9f} {s.phone_w} {s.phone_h}")
                    elif len(a) >= 6 and a[0] == "CLIP":
                        s.clip = 1 if int(a[1]) else 0
                        s.cl, s.ct, s.cr, s.cb = map(float, a[2:6]); s.persist(); await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "MODE":
                        s.gyro = 1 if int(a[1]) else 0; s.persist()
                        await self.broadcast(self.phones, f"MODE {s.gyro}")
                        await self.broadcast(self.operators, f"MODE {s.gyro}")
                    elif len(a) >= 4 and a[0] == "GVIEW":
                        cx, cy, wpp = float(a[1]), float(a[2]), float(a[3])
                        if wpp > 0:
                            s.gcx, s.gcy, s.gwpp = cx, cy, wpp; s.persist()
                            await self.broadcast(self.phones, f"GVIEW {cx:.12g} {cy:.12g} {wpp:.12g}")
                    elif line == "CLEAR":
                        s.clear()
                        await self.broadcast(self.phones, f"CLEAR {s.epoch}")
                        await self.broadcast(self.operators, f"RESET {s.epoch}")
                except Exception:
                    pass
        finally:
            self.operators.discard(p); p.close()
            print("operator-out", flush=True)

    async def broadcast_cfg(self):
        s = self.state
        line = f"CFG {s.bg:06x} {s.brush:06x} {s.land} {s.clip} {s.cl:.6f} {s.ct:.6f} {s.cr:.6f} {s.cb:.6f}"
        await self.broadcast(self.phones, line)
        await self.broadcast(self.operators, line)

    async def handle_video(self, reader, writer, dev):
        print("video", dev, writer.get_extra_info("peername"), flush=True)
        try:
            while True:
                hdr = await reader.readexactly(4)
                n = struct.unpack("!I", hdr)[0]
                if n < 100 or n > MAX_FRAME:
                    raise ValueError("bad frame")
                jpeg = await reader.readexactly(n)
                self.video_seq = (self.video_seq + 1) & 0xFFFFFFFF
                self.latest_video = (self.video_seq, jpeg)
                dead = []
                for sub in list(self.video_subs):
                    if sub.dead: dead.append(sub)
                    else: sub.offer(self.video_seq, jpeg)
                for sub in dead:
                    self.video_subs.discard(sub)
        except Exception:
            pass
        finally:
            print("video-out", dev, flush=True)

    async def handle_video_sub(self, reader, writer):
        sub = VideoSub(writer); self.video_subs.add(sub)
        print("video-sub", writer.get_extra_info("peername"), flush=True)
        if self.latest_video:
            sub.offer(*self.latest_video)
        try:
            await reader.read()
        finally:
            self.video_subs.discard(sub); sub.close()

    async def handle_tcp(self, reader, writer):
        handed_off = False
        try:
            raw = await asyncio.wait_for(reader.readline(), 5.0)
            if not raw or len(raw) > MAX_LINE:
                return
            line = raw.decode("ascii", "replace").strip()
            a = line.split()
            if len(a) >= 6 and a[0] == "HELLO" and a[1] == "PHONE" and a[2] == TOKEN_S:
                handed_off = True; await self.handle_phone(reader, writer, a[3], int(a[4]), int(a[5]))
            elif len(a) >= 3 and a[0] == "HELLO" and a[1] == "OP" and a[2] == TOKEN_S:
                handed_off = True; await self.handle_operator(reader, writer)
            elif len(a) >= 5 and a[0] == "VIDEO" and a[1] == "PHONE" and a[2] == TOKEN_S and a[4] == "JPEG/1":
                handed_off = True; await self.handle_video(reader, writer, a[3])
            elif len(a) >= 3 and a[0] == "VIDEO_SUB" and a[1] == "OP" and a[2] == TOKEN_S:
                handed_off = True; await self.handle_video_sub(reader, writer)
            else:
                writer.write(b"ERR bad hello\n"); await writer.drain()
        except Exception as e:
            print("tcp-error", repr(e), flush=True)
        finally:
            if not handed_off:
                try: writer.close(); await writer.wait_closed()
                except Exception: pass

    def udp_received(self, data, addr):
        now = time.monotonic()
        try:
            if len(data) == 36 and data[:4] == b"FDH1" and data[4:36] == TOKEN:
                self.phone_audio = addr; self.phone_audio_seen = now; return
            if len(data) == 36 and data[:4] == b"FDOH" and data[4:36] == TOKEN:
                self.operator_audio = addr; self.operator_audio_seen = now; return
            if len(data) >= 42 and data[:4] == b"FDO1" and data[4:36] == TOKEN:
                if self.phone_audio and now - self.phone_audio_seen <= 8:
                    self.udp_transport.sendto(b"FDA1" + data[36:], self.phone_audio)
                return
            if len(data) >= 42 and data[:4] == b"FDP1" and data[4:36] == TOKEN:
                if self.operator_audio and now - self.operator_audio_seen <= 8:
                    self.udp_transport.sendto(b"FDR1" + data[36:], self.operator_audio)
                return
        except Exception:
            pass

class UDP(asyncio.DatagramProtocol):
    def __init__(self, relay): self.relay = relay
    def connection_made(self, transport): self.relay.udp_transport = transport
    def datagram_received(self, data, addr): self.relay.udp_received(data, addr)

async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tcp-host", default="0.0.0.0")
    ap.add_argument("--tcp-port", type=int, default=4950)
    ap.add_argument("--udp-host", default="0.0.0.0")
    ap.add_argument("--udp-port", type=int, default=4950)
    ap.add_argument("--data", default="data")
    args = ap.parse_args()

    relay = Relay(Path(args.data))
    loop = asyncio.get_running_loop()
    tcp = await asyncio.start_server(relay.handle_tcp, args.tcp_host, args.tcp_port, limit=MAX_LINE)
    udp, _ = await loop.create_datagram_endpoint(lambda: UDP(relay), local_addr=(args.udp_host, args.udp_port))
    print(f"FootDraw unified relay TCP {args.tcp_host}:{args.tcp_port} UDP {args.udp_host}:{args.udp_port}", flush=True)
    try:
        await tcp.serve_forever()
    finally:
        udp.close()

if __name__ == "__main__":
    asyncio.run(main())
