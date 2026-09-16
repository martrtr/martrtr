package main

import (
	"math"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"
)

type WAVEFORMATEX struct {
	FormatTag, Channels               uint16
	SamplesPerSec, AvgBytesPerSec     uint32
	BlockAlign, BitsPerSample, CbSize uint16
}
type WAVEHDR struct {
	Data           uintptr
	BufferLength   uint32
	BytesRecorded  uint32
	User           uintptr
	Flags, Loops   uint32
	Next, Reserved uintptr
}
type WAVEINCAPSW struct {
	ManufacturerID, ProductID uint16
	DriverVersion             uint32
	ProductName               [32]uint16
	Formats                   uint32
	Channels                  uint16
	Reserved1                 uint16
}

var winmm = syscall.NewLazyDLL("winmm.dll")
var waveOpen = winmm.NewProc("waveInOpen")
var wavePrepare = winmm.NewProc("waveInPrepareHeader")
var waveUnprepare = winmm.NewProc("waveInUnprepareHeader")
var waveAdd = winmm.NewProc("waveInAddBuffer")
var waveStart = winmm.NewProc("waveInStart")
var waveStop = winmm.NewProc("waveInStop")
var waveReset = winmm.NewProc("waveInReset")
var waveClose = winmm.NewProc("waveInClose")
var waveNumDevs = winmm.NewProc("waveInGetNumDevs")
var waveCaps = winmm.NewProc("waveInGetDevCapsW")

var micHandle uintptr
var micBufs [8][]byte
var micHdrs [8]WAVEHDR
var micOut chan []byte
var micMu sync.Mutex
var micRate int
var micMuted bool
var micSelected uintptr = 0xFFFFFFFF
var micDeviceNames []string
var micActive atomic.Bool
var micCallbackPtr uintptr

const (
	WIM_DATA          = 0x3C0
	CALLBACK_FUNCTION = 0x00030000
)

func isMicMuted() bool {
	micMu.Lock(); v := micMuted; micMu.Unlock(); return v
}
func toggleMicMuted() {
	micMu.Lock(); micMuted = !micMuted; micMu.Unlock(); invalidate()
}
func getMicDevices() []string {
	micMu.Lock(); out := append([]string(nil), micDeviceNames...); micMu.Unlock(); return out
}
func currentMicIndex() int {
	micMu.Lock(); sel := micSelected; micMu.Unlock(); if sel == 0xFFFFFFFF { return 0 }; return int(sel)+1
}
func wideToString(a []uint16) string {
	n:=0; for n<len(a)&&a[n]!=0 { n++ }; return syscall.UTF16ToString(a[:n])
}
func refreshMicNames() {
	n,_,_:=waveNumDevs.Call(); names:=[]string{"По умолчанию"}
	for dev:=uintptr(0);dev<n;dev++ { var c WAVEINCAPSW; if r,_,_:=waveCaps.Call(dev,uintptr(unsafe.Pointer(&c)),unsafe.Sizeof(c));r==0 { name:=wideToString(c.ProductName[:]);if name==""{name="Микрофон"};names=append(names,name) } }
	micMu.Lock();micDeviceNames=names;micMu.Unlock()
}

func micCallback(hwi uintptr,msg uint32,instance,param1,param2 uintptr) uintptr {
	if msg!=WIM_DATA||param1==0||!micActive.Load(){return 0}
	h:=(*WAVEHDR)(unsafe.Pointer(param1));n:=int(h.BytesRecorded)
	if n>1 {
		raw:=make([]byte,n);copy(raw,unsafe.Slice((*byte)(unsafe.Pointer(h.Data)),n))
		micMu.Lock();rate:=micRate;muted:=micMuted;out:=micOut;micMu.Unlock()
		pcm:=normalizePCM16(raw,rate)
		if len(pcm)>0&&!muted&&out!=nil {
			select { case out<-pcm: default:
				select { case <-out: default: }
				select { case out<-pcm: default: }
			}
		}
	}
	h.BytesRecorded=0;if micActive.Load(){waveAdd.Call(hwi,param1,unsafe.Sizeof(*h))};return 0
}

func tryOpenMic(device uintptr,rate int)(uintptr,uintptr){
	var h uintptr;f:=WAVEFORMATEX{FormatTag:1,Channels:1,SamplesPerSec:uint32(rate),AvgBytesPerSec:uint32(rate*2),BlockAlign:2,BitsPerSample:16}
	r,_,_:=waveOpen.Call(uintptr(unsafe.Pointer(&h)),device,uintptr(unsafe.Pointer(&f)),micCallbackPtr,0,CALLBACK_FUNCTION);return h,r
}
func closeCurrentMic(){
	micActive.Store(false);micMu.Lock();h:=micHandle;micHandle=0;micMu.Unlock();if h==0{return}
	waveStop.Call(h);waveReset.Call(h)
	for i:=range micHdrs{if micHdrs[i].Data!=0{waveUnprepare.Call(h,uintptr(unsafe.Pointer(&micHdrs[i])),unsafe.Sizeof(micHdrs[i]))}}
	waveClose.Call(h)
}
func startMicrophone(out chan []byte){
	micMu.Lock();micOut=out;micMu.Unlock();if micCallbackPtr==0{micCallbackPtr=syscall.NewCallback(micCallback)};refreshMicNames();_=restartMicrophone()
}
func restartMicrophone() error {
	closeCurrentMic();micMu.Lock();selected:=micSelected;micMu.Unlock()
	rates:=[]int{16000,48000,44100};var h uintptr;var rc uintptr=1;chosenRate:=0
	for _,rate:=range rates{h,rc=tryOpenMic(selected,rate);if rc==0{chosenRate=rate;break}}
	if rc!=0&&selected!=0xFFFFFFFF{selected=0xFFFFFFFF;for _,rate:=range rates{h,rc=tryOpenMic(selected,rate);if rc==0{chosenRate=rate;break}}}
	if rc!=0||h==0{return syscall.Errno(rc)}
	frameBytes:=chosenRate*2/50
	for i:=range micBufs{
		micBufs[i]=make([]byte,frameBytes);micHdrs[i]=WAVEHDR{Data:uintptr(unsafe.Pointer(&micBufs[i][0])),BufferLength:uint32(len(micBufs[i]))}
		if r,_,_:=wavePrepare.Call(h,uintptr(unsafe.Pointer(&micHdrs[i])),unsafe.Sizeof(micHdrs[i]));r!=0{waveReset.Call(h);waveClose.Call(h);return syscall.Errno(r)}
		if r,_,_:=waveAdd.Call(h,uintptr(unsafe.Pointer(&micHdrs[i])),unsafe.Sizeof(micHdrs[i]));r!=0{waveReset.Call(h);waveClose.Call(h);return syscall.Errno(r)}
	}
	micMu.Lock();micSelected=selected;micHandle=h;micRate=chosenRate;micMu.Unlock();micActive.Store(true)
	if r,_,_:=waveStart.Call(h);r!=0{closeCurrentMic();return syscall.Errno(r)};return nil
}
func selectMicByPopupIndex(idx int){
	devices:=getMicDevices();if idx<0||idx>=len(devices){return};micMu.Lock();if idx==0{micSelected=0xFFFFFFFF}else{micSelected=uintptr(idx-1)};micMu.Unlock()
	go func(){_=restartMicrophone();invalidate()}()
}

func normalizePCM16(src []byte,rate int)[]byte{
	samples:=len(src)/2;if samples==0{return nil};if rate==16000{out:=make([]byte,samples*2);copy(out,src[:samples*2]);return out}
	outSamples:=int((int64(samples)*16000+int64(rate)/2)/int64(rate));if outSamples<1{return nil};out:=make([]byte,outSamples*2)
	read:=func(i int)int16{if i<0{i=0};if i>=samples{i=samples-1};return int16(uint16(src[2*i])|uint16(src[2*i+1])<<8)}
	for i:=0;i<outSamples;i++{pos:=float64(i)*float64(rate)/16000.0;i0:=int(pos);frac:=pos-float64(i0);s0,s1:=float64(read(i0)),float64(read(i0+1));v:=int(math.Round(s0+(s1-s0)*frac));if v>32767{v=32767};if v<-32768{v=-32768};out[2*i]=byte(uint16(int16(v)));out[2*i+1]=byte(uint16(int16(v))>>8)}
	return out
}
