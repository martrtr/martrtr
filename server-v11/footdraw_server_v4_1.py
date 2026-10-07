#!/usr/bin/env python3
import argparse
import asyncio
import json
import os
import struct
import time
from pathlib import Path

TOKEN = b"f2f3a025173941f9cb1d297eba2c0469"
TOKEN_S = TOKEN.decode("ascii")
MAX_LINE = 8192

class BoardState:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.state_file = data_dir / "state.json"
        self.events_file = data_dir / "events.jsonl"
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
        data_dir.mkdir(parents=True, exist_ok=True)
        self.load()

    def load(self):
        try:
            d = json.loads(self.state_file.read_text())
            for k in ("epoch","vx","vy","phone_w","phone_h","bg","brush","land","clip",
                      "cl","ct","cr","cb","gyro","gcx","gcy","gwpp"):
                if k in d:
                    setattr(self, k, d[k])
        except Exception:
            self.persist()

        try:
            with self.events_file.open("r", encoding="utf-8") as f:
                for line in f:
                    try:
                        e = json.loads(line)
                        if int(e["epoch"]) != int(self.epoch):
                            continue
                        item = (
                            int(e["gseq"]), int(e["epoch"]), str(e["dev"]), int(e["devseq"]),
                            str(e["stroke"]), str(e["kind"]), float(e["x"]), float(e["y"]),
                            float(e["p"]), int(e["color"]) & 0xFFFFFF,
                        )
                        self.events.append(item)
                        self.seen[(item[1], item[2], item[3])] = item[0]
                        self.next_gseq = max(self.next_gseq, item[0] + 1)
                    except Exception:
                        pass
        except FileNotFoundError:
            self.events_file.touch()

    def persist(self):
        payload = {
            "epoch": self.epoch, "vx": self.vx, "vy": self.vy,
            "phone_w": self.phone_w, "phone_h": self.phone_h,
            "bg": self.bg, "brush": self.brush, "land": self.land, "clip": self.clip,
            "cl": self.cl, "ct": self.ct, "cr": self.cr, "cb": self.cb,
            "gyro": self.gyro, "gcx": self.gcx, "gcy": self.gcy, "gwpp": self.gwpp,
        }
        tmp = self.state_file.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as f:
            json.dump(payload, f, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.state_file)

    def add_event(self, dev, devseq, epoch, stroke, kind, x, y, pressure, color):
        key = (epoch, dev, devseq)
        if key in self.seen:
            return self.seen[key]
        if epoch != self.epoch:
            return None
        gseq = self.next_gseq
        self.next_gseq += 1
        item = (gseq, epoch, dev, devseq, stroke, kind, x, y, pressure, color & 0xFFFFFF)
        record = {
            "gseq": gseq, "epoch": epoch, "dev": dev, "devseq": devseq,
            "stroke": stroke, "kind": kind, "x": x, "y": y, "p": pressure,
            "color": color & 0xFFFFFF,
        }
        with self.events_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, separators=(",", ":")) + "\n")
            f.flush()
            os.fsync(f.fileno())
        self.events.append(item)
        self.seen[key] = gseq
        return gseq

    def clear(self):
        self.epoch += 1
        self.events.clear()
        self.seen.clear()
        self.next_gseq = 1
        self.events_file.write_text("")
        self.persist()

class Peer:
    def __init__(self, writer):
        self.writer = writer
        self.send_lock = asyncio.Lock()
        self.dead = False

    async def send(self, line):
        if self.dead:
            return False
        try:
            async with self.send_lock:
                self.writer.write((line + "\n").encode("utf-8"))
                await asyncio.wait_for(self.writer.drain(), 2.0)
            return True
        except Exception:
            self.dead = True
            try:
                self.writer.close()
            except Exception:
                pass
            return False

    def close(self):
        self.dead = True
        try:
            self.writer.close()
        except Exception:
            pass

class Relay:
    def __init__(self, data_dir: Path):
        self.state = BoardState(data_dir)
        self.phones = set()
        self.operators = set()
        self.udp_transport = None
        self.phone_audio = None
        self.phone_audio_seen = 0.0
        self.control_seq = 1

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

    def send_udp_cfg(self):
        if not self.udp_transport or not self.phone_audio:
            return
        if time.monotonic() - self.phone_audio_seen > 8.0:
            return
        s = self.state
        payload = bytearray(28)
        payload[0:4] = b"FDC1"
        payload[4:7] = bytes(((s.bg >> 16) & 255, (s.bg >> 8) & 255, s.bg & 255))
        payload[7:10] = bytes(((s.brush >> 16) & 255, (s.brush >> 8) & 255, s.brush & 255))
        payload[10] = 1 if s.land else 0
        payload[11] = 1 if s.clip else 0
        struct.pack_into("!ffff", payload, 12, float(s.cl), float(s.ct), float(s.cr), float(s.cb))
        seq = self.control_seq & 0xFFFFFFFF
        self.control_seq += 1
        pkt = b"FDA1" + struct.pack("!IH", seq, len(payload) // 2) + bytes(payload)
        self.udp_transport.sendto(pkt, self.phone_audio)

    async def broadcast_cfg(self):
        s = self.state
        line = f"CFG {s.bg:06x} {s.brush:06x} {s.land} {s.clip} {s.cl:.6f} {s.ct:.6f} {s.cr:.6f} {s.cb:.6f}"
        await self.broadcast(self.phones, line)
        await self.broadcast(self.operators, line)
        self.send_udp_cfg()

    async def handle_phone(self, reader, writer, dev, w, h):
        p = Peer(writer)
        self.phones.add(p)
        s = self.state
        s.phone_w = max(1, w)
        s.phone_h = max(1, h)
        s.persist()
        print("phone", dev, writer.get_extra_info("peername"), flush=True)
        await self.send_state(p)
        await self.broadcast(self.operators, f"STATE {s.epoch} {s.vx:.9f} {s.vy:.9f} {s.phone_w} {s.phone_h}")
        try:
            while True:
                raw = await reader.readline()
                if not raw:
                    break
                line = raw.decode("utf-8", "replace").strip()
                if line == "PING":
                    await p.send("PONG")
                    continue
                if line.startswith("GC "):
                    await self.broadcast(self.operators, line)
                    continue
                if not line.startswith("P "):
                    continue
                a = line.split()
                if len(a) < 9:
                    continue
                try:
                    devseq = int(a[1]); epoch = int(a[2])
                    stroke, kind = a[3], a[4]
                    x, y, pressure = float(a[5]), float(a[6]), float(a[7])
                    color = int(a[9], 16) if len(a) >= 10 else s.brush
                except Exception:
                    continue
                if epoch != s.epoch:
                    await p.send(f"CLEAR {s.epoch}")
                    continue
                gseq = s.add_event(dev, devseq, epoch, stroke, kind, x, y, pressure, color)
                if gseq is None:
                    continue
                await p.send(f"ACKP {devseq}")
                await self.broadcast(
                    self.operators,
                    f"O {gseq} {epoch} {stroke} {kind} {x:.9f} {y:.9f} {pressure:.5f} {color & 0xFFFFFF:06x}",
                )
        finally:
            self.phones.discard(p)
            p.close()
            print("phone-out", dev, flush=True)

    async def handle_operator(self, reader, writer):
        p = Peer(writer)
        s = self.state
        print("operator", writer.get_extra_info("peername"), flush=True)
        await self.send_state(p)
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
                if not raw:
                    break
                line = raw.decode("utf-8", "replace").strip()
                if line == "PING":
                    await p.send("PONG")
                    continue
                a = line.split()
                try:
                    if len(a) >= 3 and a[0] == "VIEW":
                        s.vx, s.vy = float(a[1]), float(a[2])
                        s.persist()
                        await self.broadcast(self.phones, f"VIEW {s.vx:.9f} {s.vy:.9f}")
                    elif len(a) >= 2 and a[0] == "BG":
                        s.bg = int(a[1], 16) & 0xFFFFFF
                        s.persist()
                        await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "BRUSH":
                        s.brush = int(a[1], 16) & 0xFFFFFF
                        s.persist()
                        await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "ORIENT":
                        s.land = 1 if int(a[1]) else 0
                        s.persist()
                        await self.broadcast_cfg()
                        await self.broadcast(self.operators, f"STATE {s.epoch} {s.vx:.9f} {s.vy:.9f} {s.phone_w} {s.phone_h}")
                    elif len(a) >= 6 and a[0] == "CLIP":
                        s.clip = 1 if int(a[1]) else 0
                        s.cl, s.ct, s.cr, s.cb = map(float, a[2:6])
                        s.persist()
                        await self.broadcast_cfg()
                    elif len(a) >= 2 and a[0] == "MODE":
                        s.gyro = 1 if int(a[1]) else 0
                        s.persist()
                        await self.broadcast(self.phones, f"MODE {s.gyro}")
                        await self.broadcast(self.operators, f"MODE {s.gyro}")
                    elif len(a) >= 4 and a[0] == "GVIEW":
                        cx, cy, wpp = float(a[1]), float(a[2]), float(a[3])
                        if wpp > 0:
                            s.gcx, s.gcy, s.gwpp = cx, cy, wpp
                            s.persist()
                            await self.broadcast(self.phones, f"GVIEW {cx:.12g} {cy:.12g} {wpp:.12g}")
                            await self.broadcast(self.operators, f"GVIEW {cx:.12g} {cy:.12g} {wpp:.12g}")
                    elif line == "CLEAR":
                        s.clear()
                        await self.broadcast(self.phones, f"CLEAR {s.epoch}")
                        await self.broadcast(self.operators, f"RESET {s.epoch}")
                except Exception:
                    pass
        finally:
            self.operators.discard(p)
            p.close()
            print("operator-out", flush=True)

    async def handle_tcp(self, reader, writer):
        handed = False
        try:
            raw = await asyncio.wait_for(reader.readline(), 5.0)
            if not raw or len(raw) > MAX_LINE:
                return
            a = raw.decode("ascii", "replace").strip().split()
            if len(a) >= 6 and a[0] == "HELLO" and a[1] == "PHONE" and a[2] == TOKEN_S:
                handed = True
                await self.handle_phone(reader, writer, a[3], int(a[4]), int(a[5]))
            elif len(a) >= 3 and a[0] == "HELLO" and a[1] == "OP" and a[2] == TOKEN_S:
                handed = True
                await self.handle_operator(reader, writer)
            else:
                writer.write(b"ERR bad hello\n")
                await writer.drain()
        except Exception as e:
            print("tcp-error", repr(e), flush=True)
        finally:
            if not handed:
                try:
                    writer.close()
                    await writer.wait_closed()
                except Exception:
                    pass

    def udp_received(self, data, addr):
        now = time.monotonic()
        try:
            if len(data) == 36 and data[:4] == b"FDH1" and data[4:36] == TOKEN:
                self.phone_audio = addr
                self.phone_audio_seen = now
                return
            if len(data) >= 42 and data[:4] == b"FDO1" and data[4:36] == TOKEN:
                if self.phone_audio and now - self.phone_audio_seen <= 8.0:
                    self.udp_transport.sendto(b"FDA1" + data[36:], self.phone_audio)
                return
        except Exception:
            pass

class UDP(asyncio.DatagramProtocol):
    def __init__(self, relay):
        self.relay = relay

    def connection_made(self, transport):
        self.relay.udp_transport = transport

    def datagram_received(self, data, addr):
        self.relay.udp_received(data, addr)

async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=4950)
    ap.add_argument("--data", default="/opt/footdraw-v11/data")
    args = ap.parse_args()

    relay = Relay(Path(args.data))
    loop = asyncio.get_running_loop()
    tcp = await asyncio.start_server(relay.handle_tcp, args.host, args.port, limit=MAX_LINE)
    udp, _ = await loop.create_datagram_endpoint(lambda: UDP(relay), local_addr=(args.host, args.port))
    print(f"FootDraw V11-compatible relay v4.1 TCP/UDP {args.host}:{args.port}", flush=True)
    try:
        await tcp.serve_forever()
    finally:
        udp.close()

if __name__ == "__main__":
    asyncio.run(main())
