#!/usr/bin/env python3
import argparse, queue, socket, struct, sys, threading, time
from collections import OrderedDict

import numpy as np
import sounddevice as sd
from PySide6.QtCore import Qt, QObject, Signal, QRectF, QPointF
from PySide6.QtGui import QColor, QImage, QPainter, QPainterPath, QPen, QPixmap, QTransform
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFrame, QHBoxLayout, QLabel, QMainWindow,
    QPushButton, QSlider, QSpinBox, QStatusBar, QTabWidget, QToolButton,
    QVBoxLayout, QWidget
)

HOST_DEFAULT = "31.77.251.51"
PORT = 4950
TOKEN = "f2f3a025173941f9cb1d297eba2c0469"
GYRO_PX_PER_RAD = 800.0

class Bus(QObject):
    line = Signal(str)
    relay_status = Signal(str)
    video = Signal(bytes, int)
    video_status = Signal(str)
    audio_status = Signal(str)

class ControlClient:
    def __init__(self, host, bus):
        self.host, self.bus = host, bus
        self.running = True
        self.out = queue.Queue()
        self.sock = None
        threading.Thread(target=self.loop, daemon=True, name="FootDraw-Control").start()

    def send(self, line):
        if self.running:
            self.out.put(line)

    def close(self):
        self.running = False
        try:
            if self.sock: self.sock.close()
        except Exception:
            pass

    def loop(self):
        backoff = 0.15
        while self.running:
            try:
                self.bus.relay_status.emit("relay: connecting")
                with socket.create_connection((self.host, PORT), timeout=4) as s:
                    self.sock = s
                    s.settimeout(0.05)
                    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    s.sendall(f"HELLO OP {TOKEN}\n".encode("ascii"))
                    self.bus.relay_status.emit("relay: connected")
                    backoff = 0.15
                    buf = bytearray()
                    last_ping = time.monotonic()
                    while self.running:
                        while True:
                            try: cmd = self.out.get_nowait()
                            except queue.Empty: break
                            s.sendall((cmd + "\n").encode("utf-8"))
                        try:
                            chunk = s.recv(65536)
                            if not chunk: raise ConnectionError("relay closed")
                            buf += chunk
                            while True:
                                pos = buf.find(b"\n")
                                if pos < 0: break
                                raw = bytes(buf[:pos]); del buf[:pos+1]
                                self.bus.line.emit(raw.decode("utf-8","replace").strip())
                        except socket.timeout:
                            pass
                        if time.monotonic()-last_ping > 1.8:
                            s.sendall(b"PING\n"); last_ping=time.monotonic()
            except Exception:
                self.bus.relay_status.emit("relay: reconnecting")
                time.sleep(backoff); backoff=min(3.0,backoff*1.8)
            finally:
                self.sock=None

class VideoReceiver:
    def __init__(self, host, bus):
        self.host,self.bus=host,bus
        self.running=True
        self.sock=None
        threading.Thread(target=self.loop,daemon=True,name="FootDraw-Video").start()

    def close(self):
        self.running=False
        try:
            if self.sock:self.sock.close()
        except Exception: pass

    @staticmethod
    def recvn(s,n):
        out=bytearray()
        while len(out)<n:
            b=s.recv(n-len(out))
            if not b: raise ConnectionError("video closed")
            out+=b
        return bytes(out)

    def loop(self):
        backoff=.15
        while self.running:
            try:
                self.bus.video_status.emit("camera: connecting")
                with socket.create_connection((self.host,PORT),timeout=4) as s:
                    self.sock=s; s.settimeout(8)
                    s.sendall(f"VIDEO_SUB OP {TOKEN}\n".encode("ascii"))
                    self.bus.video_status.emit("camera: connected")
                    backoff=.15
                    while self.running:
                        seq,n=struct.unpack("!II",self.recvn(s,8))
                        if n<100 or n>2_000_000: raise ValueError("bad jpeg size")
                        self.bus.video.emit(self.recvn(s,n),seq)
            except Exception:
                self.bus.video_status.emit("camera: reconnecting")
                time.sleep(backoff); backoff=min(3.0,backoff*1.8)
            finally:
                self.sock=None

class AudioSender:
    def __init__(self,host,bus):
        self.host,self.bus=host,bus
        self.running=True; self.muted=False; self.device=None
        self.stream=None; self.stream_lock=threading.Lock()
        self.frames=queue.Queue(maxsize=60); self.seq=0; self.prev=None
        threading.Thread(target=self.net_loop,daemon=True,name="FootDraw-AudioTx").start()

    @staticmethod
    def input_devices():
        out=[("По умолчанию",None)]
        try:
            for i,d in enumerate(sd.query_devices()):
                if int(d.get("max_input_channels",0))>0:
                    out.append((str(d.get("name",f"Input {i}")),i))
        except Exception: pass
        return out

    def set_muted(self,v): self.muted=bool(v)
    def set_device(self,d): self.device=d; self.restart_stream()

    def restart_stream(self):
        with self.stream_lock:
            if self.stream is not None:
                try:self.stream.stop();self.stream.close()
                except Exception:pass
                self.stream=None
            while True:
                try:self.frames.get_nowait()
                except queue.Empty:break
            try:
                info=sd.query_devices(self.device,"input")
                sr=int(round(float(info["default_samplerate"] or 48000)))
                if sr<8000:sr=48000
                block=max(64,int(round(sr*.020)))
                def callback(indata,frames,timing,status):
                    if not self.running or self.muted:return
                    a=np.frombuffer(bytes(indata),dtype="<i2")
                    if len(a)==0:return
                    if sr!=16000:
                        n=max(1,int(round(len(a)*16000.0/sr)))
                        xo=np.linspace(0.0,1.0,len(a),endpoint=False)
                        xn=np.linspace(0.0,1.0,n,endpoint=False)
                        a=np.interp(xn,xo,a).clip(-32768,32767).astype("<i2")
                    raw=a.tobytes()
                    for off in range(0,len(raw)-639,640):
                        f=raw[off:off+640]
                        try:self.frames.put_nowait(f)
                        except queue.Full:
                            try:self.frames.get_nowait()
                            except queue.Empty:pass
                            try:self.frames.put_nowait(f)
                            except queue.Full:pass
                self.stream=sd.RawInputStream(device=self.device,samplerate=sr,blocksize=block,channels=1,dtype="int16",callback=callback)
                self.stream.start()
                self.bus.audio_status.emit(f"mic: {info['name']}")
            except Exception:
                self.bus.audio_status.emit("mic: unavailable")
                self.stream=None

    def start(self): self.restart_stream()
    def close(self):
        self.running=False
        with self.stream_lock:
            if self.stream is not None:
                try:self.stream.stop();self.stream.close()
                except Exception:pass
                self.stream=None

    def net_loop(self):
        backoff=.15
        while self.running:
            try:
                with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
                    s.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,512*1024)
                    addr=(self.host,PORT); backoff=.15
                    while self.running:
                        try:cur=self.frames.get(timeout=.05)
                        except queue.Empty:continue
                        if self.muted or len(cur)!=640:
                            self.prev=None;continue
                        prev=self.prev if self.prev is not None and len(self.prev)==640 else b""
                        inner=b"FDR2"+struct.pack("!H",len(cur))+cur+struct.pack("!H",len(prev))+prev
                        self.seq=(self.seq+1)&0xffffffff
                        pkt=b"FDO1"+TOKEN.encode("ascii")+struct.pack("!IH",self.seq,len(inner)//2)+inner
                        s.sendto(pkt,addr); self.prev=cur
            except Exception:
                time.sleep(backoff);backoff=min(2.0,backoff*1.7)

class AudioReceiver:
    FRAME_BYTES=640
    def __init__(self,host,bus):
        self.host,self.bus=host,bus
        self.running=True;self.sock=None
        self.frames={};self.lock=threading.Condition();self.expected=None
        threading.Thread(target=self.net_loop,daemon=True,name="FootDraw-AudioRx").start()
        threading.Thread(target=self.play_loop,daemon=True,name="FootDraw-AudioPlay").start()
    def close(self):
        self.running=False
        try:
            if self.sock:self.sock.close()
        except Exception:pass
        with self.lock:self.lock.notify_all()
    def enqueue(self,seq,cur,prev=None):
        with self.lock:
            if prev is not None and len(prev)==self.FRAME_BYTES and seq>0:
                self.frames.setdefault((seq-1)&0xffffffff,prev)
            if len(cur)==self.FRAME_BYTES:self.frames[seq]=cur
            if len(self.frames)>60:
                for k in sorted(self.frames)[:-16]:self.frames.pop(k,None)
                self.expected=None
            self.lock.notify_all()
    def parse(self,d):
        if len(d)<10 or d[:4]!=b"FDR1":return
        seq,samples=struct.unpack("!IH",d[4:10]);payload=d[10:]
        if payload.startswith(b"FDR2") and len(payload)>=8:
            try:
                cur_len=struct.unpack("!H",payload[4:6])[0];o=6
                if cur_len<=0 or o+cur_len+2>len(payload):return
                cur=payload[o:o+cur_len];o+=cur_len
                prev_len=struct.unpack("!H",payload[o:o+2])[0];o+=2
                if o+prev_len!=len(payload):return
                prev=payload[o:o+prev_len] if prev_len else None
                self.enqueue(seq,cur,prev)
            except Exception:return
        elif len(payload)==self.FRAME_BYTES:self.enqueue(seq,payload)
    def net_loop(self):
        backoff=.15;hello=b"FDOH"+TOKEN.encode("ascii")
        while self.running:
            try:
                with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
                    self.sock=s;s.settimeout(.08);s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,512*1024)
                    addr=(self.host,PORT);last=0.;backoff=.15
                    while self.running:
                        now=time.monotonic()
                        if now-last>.65:s.sendto(hello,addr);last=now
                        try:d,_=s.recvfrom(4096);self.parse(d)
                        except socket.timeout:pass
            except Exception:
                time.sleep(backoff);backoff=min(2.,backoff*1.7)
            finally:self.sock=None
    def take(self):
        with self.lock:
            while self.running and self.expected is None:
                if len(self.frames)>=5:self.expected=min(self.frames);break
                self.lock.wait(.02)
            if not self.running:return None
            deadline=time.monotonic()+.032
            while self.running:
                if self.expected in self.frames:
                    f=self.frames.pop(self.expected);self.expected=(self.expected+1)&0xffffffff;return f
                left=deadline-time.monotonic()
                if left<=0:
                    self.expected=(self.expected+1)&0xffffffff
                    return bytes(self.FRAME_BYTES)
                self.lock.wait(min(left,.006))
            return None
    def play_loop(self):
        backoff=.25
        while self.running:
            try:
                with sd.RawOutputStream(samplerate=16000,blocksize=320,channels=1,dtype="int16",latency="low") as out:
                    self.bus.audio_status.emit("phone mic: listening");backoff=.25
                    while self.running:
                        f=self.take()
                        if f is None:break
                        out.write(f)
            except Exception:
                self.bus.audio_status.emit("phone mic: output unavailable")
                time.sleep(backoff);backoff=min(2.,backoff*1.7)

class PalettePopup(QFrame):
    COLORS=[0x000000,0xFFFFFF,0xEBEEF4,0x8B93A7,0xFF4D6D,0xFF8A3D,0xFFD166,0x72D572,0x2DD4BF,0x4CC9F0,0x4D7CFE,0x7C5CFC,0xB85CFF,0xFF62C0,0x8B5E3C,0x3A2F2A]
    picked=Signal(int)
    def __init__(self,parent=None):
        super().__init__(parent,Qt.Popup);self.setObjectName("palette")
        outer=QVBoxLayout(self);outer.setContentsMargins(8,8,8,8);outer.setSpacing(5)
        for r in range(4):
            row=QHBoxLayout();row.setSpacing(5);outer.addLayout(row)
            for cc in range(4):
                color=self.COLORS[r*4+cc];b=QPushButton();b.setFixedSize(28,28)
                border="#6f7890" if color in (0x000000,0xFFFFFF) else "#303746"
                b.setStyleSheet(f"background:#{color:06x};border:1px solid {border};border-radius:6px;")
                b.clicked.connect(lambda _,v=color:self.choose(v));row.addWidget(b)
    def choose(self,v):self.picked.emit(v);self.close()

class CanvasWidget(QWidget):
    state_changed=Signal()
    def __init__(self,control):
        super().__init__();self.control=control
        self.epoch=1;self.vx=self.vy=0.;self.phone_w,self.phone_h=1080,2460
        self.bg,self.brush=0x000000,0xEBEEF4
        self.land=self.clip=False;self.cl=self.ct=0.;self.cr=self.cb=1.
        self.gyro=False;self.gdx=self.gdy=0.;self.gdown=False;self.zoom=1.
        self.paths=OrderedDict();self.path_colors={}
        self.dragging=False;self.last_mouse=None;self.selecting=False;self.sel_start=self.sel_end=None
        self.setMouseTracking(True);self.setMinimumSize(480,320)

    def aspect(self):return max(self.phone_w,self.phone_h)/max(1.,min(self.phone_w,self.phone_h))
    def world_dims(self,land=None):
        land=self.land if land is None else land;a=self.aspect();return (a,1.) if land else (1.,a)
    def scale(self):
        ww,wh=self.world_dims();fit=min(max(100.,self.width()-80)/ww,max(100.,self.height()-80)/wh);return max(20.,fit*self.zoom)
    def world_to_screen(self,x,y):
        ww,wh=self.world_dims();sc=self.scale();cx,cy=self.width()/2.,self.height()/2.;wcx,wcy=self.vx+ww/2.,self.vy+wh/2.
        return QPointF(cx+(x-wcx)*sc,cy+(y-wcy)*sc)
    def phone_rect(self):
        ww,wh=self.world_dims();sc=self.scale();cx,cy=self.width()/2.,self.height()/2.;return QRectF(cx-ww*sc/2,cy-wh*sc/2,ww*sc,wh*sc)
    def set_selecting(self,v):
        self.selecting=bool(v);self.sel_start=self.sel_end=None;self.update();self.state_changed.emit()

    def consume(self,line):
        if not line:return
        a=line.split()
        try:
            if a[0]=="STATE" and len(a)>=6:
                ne=int(a[1])
                if ne!=self.epoch:self.paths.clear();self.path_colors.clear()
                self.epoch=ne;self.vx,self.vy=float(a[2]),float(a[3]);self.phone_w,self.phone_h=int(a[4]),int(a[5])
            elif a[0]=="CFG" and len(a)>=9:
                self.bg,self.brush=int(a[1],16),int(a[2],16);self.land,self.clip=a[3]!="0",a[4]!="0";self.cl,self.ct,self.cr,self.cb=map(float,a[5:9])
            elif a[0]=="MODE" and len(a)>=2:
                self.gyro=a[1]!="0"
                if not self.gyro:self.gdx=self.gdy=0.;self.gdown=False
            elif a[0]=="GC" and len(a)>=4:self.gdx,self.gdy=float(a[1]),float(a[2]);self.gdown=a[3]!="0"
            elif a[0]=="RESET" and len(a)>=2:self.epoch=int(a[1]);self.paths.clear();self.path_colors.clear()
            elif a[0]=="O" and len(a)>=9:
                stroke,kind=a[3],a[4];x,y=float(a[5]),float(a[6]);color=int(a[8],16)
                self.paths.setdefault(stroke,[]).append((x,y,kind));self.path_colors.setdefault(stroke,color)
            elif a[0]=="VIEW" and len(a)>=3:self.vx,self.vy=float(a[1]),float(a[2])
        except Exception:return
        self.update();self.state_changed.emit()

    def paintEvent(self,_):
        p=QPainter(self);p.setRenderHint(QPainter.Antialiasing,True);p.fillRect(self.rect(),QColor.fromRgb(self.bg))
        for stroke,pts in self.paths.items():
            if not pts:continue
            p.setPen(QPen(QColor.fromRgb(self.path_colors.get(stroke,self.brush)),3.,Qt.SolidLine,Qt.RoundCap,Qt.RoundJoin))
            path=QPainterPath();have=False
            for x,y,kind in pts:
                q=self.world_to_screen(x,y)
                if kind=="D" or not have:path.moveTo(q);have=True
                else:path.lineTo(q)
            p.drawPath(path)
        cx,cy=self.width()/2.,self.height()/2.
        if self.gyro:
            p.setPen(QPen(QColor("#78afff"),2));p.drawLine(QPointF(cx-6,cy),QPointF(cx+6,cy));p.drawLine(QPointF(cx,cy-6),QPointF(cx,cy+6))
            gx,gy=cx+self.gdx*GYRO_PX_PER_RAD,cy+self.gdy*GYRO_PX_PER_RAD
            p.setPen(QPen(QColor.fromRgb(self.brush),2.4));arm=10 if self.gdown else 8;gap=3
            p.drawLine(QPointF(gx-arm,gy),QPointF(gx-gap,gy));p.drawLine(QPointF(gx+gap,gy),QPointF(gx+arm,gy))
            p.drawLine(QPointF(gx,gy-arm),QPointF(gx,gy-gap));p.drawLine(QPointF(gx,gy+gap),QPointF(gx,gy+arm))
        else:
            r=self.phone_rect();p.setPen(QPen(QColor("#78afff"),3));p.drawRect(r)
            if self.clip:
                ar=QRectF(r.left()+r.width()*self.cl,r.top()+r.height()*self.ct,r.width()*(self.cr-self.cl),r.height()*(self.cb-self.ct))
                p.setPen(QPen(QColor("#ffbe46"),3));p.drawRect(ar)
            if self.selecting and self.sel_start is not None and self.sel_end is not None:
                sr=QRectF(self.sel_start,self.sel_end).normalized().intersected(r);p.setPen(QPen(QColor("#6ee7b7"),2,Qt.DashLine));p.drawRect(sr)

    def mousePressEvent(self,e):
        if e.button()!=Qt.LeftButton:return
        if self.selecting and not self.gyro:self.sel_start=self.sel_end=e.position();self.update()
        else:self.dragging=True;self.last_mouse=e.position()
    def mouseMoveEvent(self,e):
        if self.selecting and self.sel_start is not None:self.sel_end=e.position();self.update();return
        if not self.dragging or self.last_mouse is None:return
        pos=e.position();dx,dy=pos.x()-self.last_mouse.x(),pos.y()-self.last_mouse.y();sc=self.scale()
        self.vx-=dx/sc;self.vy-=dy/sc;self.last_mouse=pos
        self.control.send(f"VIEW {self.vx:.9f} {self.vy:.9f}")
        if self.gyro:self.send_gview()
        self.update()
    def mouseReleaseEvent(self,e):
        if e.button()!=Qt.LeftButton:return
        if self.selecting and self.sel_start is not None and self.sel_end is not None and not self.gyro:
            r=self.phone_rect();sr=QRectF(self.sel_start,self.sel_end).normalized().intersected(r)
            if sr.width()>=3 and sr.height()>=3:
                l=max(0.,min(1.,(sr.left()-r.left())/r.width()));t=max(0.,min(1.,(sr.top()-r.top())/r.height()))
                rr=max(l,min(1.,(sr.right()-r.left())/r.width()));bb=max(t,min(1.,(sr.bottom()-r.top())/r.height()))
                self.clip=True;self.cl,self.ct,self.cr,self.cb=l,t,rr,bb;self.control.send(f"CLIP 1 {l:.6f} {t:.6f} {rr:.6f} {bb:.6f}")
            self.set_selecting(False)
        self.dragging=False;self.last_mouse=None
    def wheelEvent(self,e):
        self.zoom=max(.18,min(8.,self.zoom*(1.12 if e.angleDelta().y()>0 else 1/1.12)))
        if self.gyro:self.send_gview()
        self.update()
    def send_gview(self):
        ww,wh=self.world_dims();cx,cy=self.vx+ww/2.,self.vy+wh/2.;self.control.send(f"GVIEW {cx:.12g} {cy:.12g} {1./self.scale():.12g}")

class CameraView(QLabel):
    def __init__(self):
        super().__init__("Ожидание камеры…");self.setAlignment(Qt.AlignCenter);self.setMinimumSize(640,360)
        self.setStyleSheet("background:#080a0f;color:#6e7586;border-radius:10px;");self.image=None;self.angle=0;self.mirror_h=self.mirror_v=False
    def set_frame(self,data):
        img=QImage.fromData(data,"JPEG")
        if not img.isNull():self.image=img;self.render()
    def render(self):
        if self.image is None:return
        tr=QTransform();tr.scale(-1 if self.mirror_h else 1,-1 if self.mirror_v else 1);tr.rotate(self.angle)
        img=self.image.transformed(tr,Qt.SmoothTransformation);self.setPixmap(QPixmap.fromImage(img).scaled(self.size(),Qt.KeepAspectRatio,Qt.SmoothTransformation))
    def resizeEvent(self,e):super().resizeEvent(e);self.render()

class CameraPane(QWidget):
    def __init__(self):
        super().__init__();self.view=CameraView();self.frames=0;self.last=time.monotonic();self.info=QLabel("0 fps")
        slider=QSlider(Qt.Horizontal);slider.setRange(0,360);spin=QSpinBox();spin.setRange(0,360);spin.setSuffix("°")
        slider.valueChanged.connect(spin.setValue);spin.valueChanged.connect(slider.setValue);spin.valueChanged.connect(self.set_angle)
        mh=QCheckBox("↔ зеркало");mv=QCheckBox("↕ вертикально");mh.toggled.connect(self.set_h);mv.toggled.connect(self.set_v)
        reset=QPushButton("Сброс");reset.clicked.connect(lambda:(slider.setValue(0),mh.setChecked(False),mv.setChecked(False)))
        row=QHBoxLayout();row.addWidget(QLabel("Поворот"));row.addWidget(slider,1);row.addWidget(spin);row.addSpacing(12);row.addWidget(mh);row.addWidget(mv);row.addWidget(reset);row.addWidget(self.info)
        lay=QVBoxLayout(self);lay.setContentsMargins(10,10,10,10);lay.addWidget(self.view,1);lay.addLayout(row)
    def set_angle(self,v):self.view.angle=v;self.view.render()
    def set_h(self,v):self.view.mirror_h=v;self.view.render()
    def set_v(self,v):self.view.mirror_v=v;self.view.render()
    def frame(self,data,seq):
        self.view.set_frame(data);self.frames+=1;now=time.monotonic()
        if now-self.last>=1:
            fps=self.frames/(now-self.last);self.frames=0;self.last=now
            if self.view.image:self.info.setText(f"{fps:.1f} fps · {self.view.image.width()}×{self.view.image.height()} · {seq}")

class Window(QMainWindow):
    def __init__(self,host):
        super().__init__();self.setWindowTitle("FootDraw Operator · Unified");self.resize(1180,760)
        self.bus=Bus();self.control=ControlClient(host,self.bus);self.video=VideoReceiver(host,self.bus);self.audio=AudioSender(host,self.bus);self.audio_rx=AudioReceiver(host,self.bus)
        root=QWidget();outer=QVBoxLayout(root);outer.setContentsMargins(8,8,8,8);outer.setSpacing(6);bar=QHBoxLayout();bar.setSpacing(6)
        self.fs=QToolButton();self.fs.setText("⛶");self.fs.clicked.connect(self.toggle_fullscreen)
        self.clear=QToolButton();self.clear.setText("⌫");self.clear.clicked.connect(lambda:self.control.send("CLEAR"))
        self.bg=QToolButton();self.bg.setText("■");self.bg.clicked.connect(lambda:self.open_palette(self.bg,"bg"))
        self.brush=QToolButton();self.brush.setText("✎");self.brush.clicked.connect(lambda:self.open_palette(self.brush,"brush"))
        self.mode=QToolButton();self.mode.clicked.connect(self.toggle_mode)
        self.orient=QToolButton();self.orient.clicked.connect(self.toggle_orient)
        self.area=QToolButton();self.area.setText("▱");self.area.setCheckable(True);self.area.clicked.connect(self.toggle_area)
        self.center=QToolButton();self.center.setText("⊙");self.center.clicked.connect(self.recenter)
        self.mic=QToolButton();self.mic.setText("🎙");self.mic.setCheckable(True);self.mic.toggled.connect(self.toggle_mic)
        self.mics=QComboBox();self.mic_devices=AudioSender.input_devices()
        for name,_ in self.mic_devices:self.mics.addItem(name)
        self.mics.currentIndexChanged.connect(self.select_mic)
        for w in (self.fs,self.clear,self.bg,self.brush,self.mode,self.orient,self.area,self.center,self.mic):w.setFixedSize(42,38)
        bar.addWidget(self.fs);bar.addSpacing(10);bar.addWidget(self.clear);bar.addWidget(self.bg);bar.addWidget(self.brush);bar.addSpacing(10);bar.addWidget(self.mode);bar.addSpacing(6);bar.addWidget(self.orient);bar.addWidget(self.area);bar.addWidget(self.center);bar.addStretch(1);bar.addWidget(self.mic);bar.addWidget(self.mics)
        outer.addLayout(bar)
        self.tabs=QTabWidget();self.canvas=CanvasWidget(self.control);self.camera=CameraPane();self.tabs.addTab(self.canvas,"Полотно");self.tabs.addTab(self.camera,"Камера");outer.addWidget(self.tabs,1)
        self.setCentralWidget(root);self.setStatusBar(QStatusBar())
        self.palette=PalettePopup(self);self.palette_target="brush";self.palette.picked.connect(self.palette_picked)
        self.bus.line.connect(self.consume);self.bus.video.connect(self.camera.frame);self.bus.relay_status.connect(self.status);self.bus.video_status.connect(self.status);self.bus.audio_status.connect(self.status);self.canvas.state_changed.connect(self.sync_toolbar)
        self.audio.start();self.sync_toolbar()
    def status(self,s):self.statusBar().showMessage(s,2500)
    def consume(self,line):self.canvas.consume(line);self.sync_toolbar()
    def sync_toolbar(self):
        c=self.canvas;self.mode.setText("⌖" if c.gyro else "✎");self.orient.setText("▭" if c.land else "▯")
        self.orient.setVisible(not c.gyro);self.area.setVisible(not c.gyro);self.center.setVisible(c.gyro)
        self.area.blockSignals(True);self.area.setChecked(c.clip or c.selecting);self.area.blockSignals(False)
        self.bg.setStyleSheet(f"background:#{c.bg:06x};");self.brush.setStyleSheet(f"color:#{c.brush:06x};")
    def open_palette(self,button,target):self.palette_target=target;self.palette.move(button.mapToGlobal(button.rect().bottomLeft()));self.palette.show()
    def palette_picked(self,color):
        if self.palette_target=="bg":self.canvas.bg=color;self.control.send(f"BG {color:06x}")
        else:self.canvas.brush=color;self.control.send(f"BRUSH {color:06x}")
        self.canvas.update();self.sync_toolbar()
    def toggle_mode(self):
        new=not self.canvas.gyro;self.canvas.gyro=new;self.canvas.set_selecting(False);self.control.send(f"MODE {1 if new else 0}")
        if new:self.canvas.send_gview()
        self.sync_toolbar();self.canvas.update()
    def recenter(self):
        if self.canvas.gyro:self.control.send("MODE 1")
    def toggle_orient(self):
        if self.canvas.gyro:return
        oldww,oldwh=self.canvas.world_dims();cx=self.canvas.vx+oldww/2;cy=self.canvas.vy+oldwh/2;new=not self.canvas.land;newww,newwh=self.canvas.world_dims(new)
        self.canvas.land=new;self.canvas.vx=cx-newww/2;self.canvas.vy=cy-newwh/2;self.control.send(f"ORIENT {1 if new else 0}");self.control.send(f"VIEW {self.canvas.vx:.9f} {self.canvas.vy:.9f}");self.canvas.update();self.sync_toolbar()
    def toggle_area(self,_checked=False):
        if self.canvas.gyro:return
        if self.canvas.selecting:self.canvas.set_selecting(False)
        elif self.canvas.clip:self.canvas.clip=False;self.control.send("CLIP 0 0 0 1 1");self.canvas.update();self.sync_toolbar()
        else:self.canvas.set_selecting(True)
    def toggle_fullscreen(self):
        if self.isFullScreen():self.showNormal();self.fs.setText("⛶")
        else:self.showFullScreen();self.fs.setText("◱")
    def toggle_mic(self,muted):self.audio.set_muted(muted);self.mic.setText("🔇" if muted else "🎙")
    def select_mic(self,idx):
        if 0<=idx<len(self.mic_devices):self.audio.set_device(self.mic_devices[idx][1])
    def keyPressEvent(self,e):
        if e.key()==Qt.Key_Escape and self.isFullScreen():self.toggle_fullscreen();return
        super().keyPressEvent(e)
    def closeEvent(self,e):self.audio.close();self.audio_rx.close();self.video.close();self.control.close();super().closeEvent(e)

def main():
    ap=argparse.ArgumentParser();ap.add_argument("--host",default=HOST_DEFAULT);args=ap.parse_args()
    app=QApplication(sys.argv)
    app.setStyleSheet("""
      QMainWindow,QWidget{background:#11141b;color:#e8ebf2;font-size:14px}
      QToolButton,QPushButton,QComboBox,QSpinBox{background:#222835;border:1px solid #343c4e;border-radius:7px;padding:6px}
      QToolButton:hover,QPushButton:hover{background:#2a3140}
      QTabWidget::pane{border:0} QTabBar::tab{padding:9px 18px;background:#181c25}
      QTabBar::tab:selected{background:#252b38}
      QStatusBar{background:#0d1016}
      QFrame#palette{background:#171b23;border:1px solid #343c4e;border-radius:8px}
    """)
    w=Window(args.host);w.show();sys.exit(app.exec())

if __name__=="__main__":main()
