package main

import (
	"math"
	"sync"
)

type DrawEvent struct {
	GSeq   int64
	Epoch  int64
	Stroke string
	Kind   string
	X, Y   float64
	P      float64
	Color  uint32
}

type AppState struct {
	mu sync.RWMutex

	epoch   int64
	events  []DrawEvent
	phoneVX float64
	phoneVY float64
	phoneW  int
	phoneH  int

	bgColor    uint32
	brushColor uint32
	landscape  bool
	clip       bool
	clipL      float64
	clipT      float64
	clipR      float64
	clipB      float64

	gyroMode bool
	gyroDX   float64
	gyroDY   float64
	gyroDown bool
	gyroCX   float64
	gyroCY   float64
	gyroWpp  float64

	zoom      float64
	connected bool
}

type StateSnapshot struct {
	Epoch   int64
	Events  []DrawEvent
	PhoneVX float64
	PhoneVY float64
	PhoneW  int
	PhoneH  int

	BgColor    uint32
	BrushColor uint32
	Landscape  bool
	Clip       bool
	ClipL      float64
	ClipT      float64
	ClipR      float64
	ClipB      float64

	GyroMode bool
	GyroDX   float64
	GyroDY   float64
	GyroDown bool
	GyroCX   float64
	GyroCY   float64
	GyroWpp  float64

	Zoom      float64
	Connected bool
}

func NewAppState() *AppState {
	return &AppState{
		epoch:      1,
		phoneW:     1080,
		phoneH:     2460,
		bgColor:    0x000000,
		brushColor: 0xEBEEF4,
		clipR:      1,
		clipB:      1,
		gyroCX:     0.5,
		gyroCY:     1.0,
		gyroWpp:    1.0 / 500.0,
		zoom:       1.0,
	}
}

func (s *AppState) snapshot() StateSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()
	ev := make([]DrawEvent, len(s.events))
	copy(ev, s.events)
	return StateSnapshot{
		Epoch: s.epoch, Events: ev, PhoneVX: s.phoneVX, PhoneVY: s.phoneVY,
		PhoneW: s.phoneW, PhoneH: s.phoneH, BgColor: s.bgColor,
		BrushColor: s.brushColor, Landscape: s.landscape, Clip: s.clip,
		ClipL: s.clipL, ClipT: s.clipT, ClipR: s.clipR, ClipB: s.clipB,
		GyroMode: s.gyroMode, GyroDX: s.gyroDX, GyroDY: s.gyroDY,
		GyroDown: s.gyroDown, GyroCX: s.gyroCX, GyroCY: s.gyroCY,
		GyroWpp: s.gyroWpp, Zoom: s.zoom, Connected: s.connected,
	}
}

func worldDims(land bool, w, h int) (float64, float64) {
	if w <= 0 {
		w = 1080
	}
	if h <= 0 {
		h = 2460
	}
	hi, lo := w, h
	if hi < lo {
		hi, lo = lo, hi
	}
	aspect := float64(hi) / float64(lo)
	if land {
		return aspect, 1
	}
	return 1, aspect
}

func clamp(v, lo, hi float64) float64 {
	return math.Max(lo, math.Min(hi, v))
}

func (s *AppState) setConnected(v bool) {
	s.mu.Lock()
	s.connected = v
	s.mu.Unlock()
}

func (s *AppState) setZoom(v float64) {
	s.mu.Lock()
	s.zoom = clamp(v, 0.12, 8.0)
	s.mu.Unlock()
}

func (s *AppState) panByWorld(dx, dy float64) {
	s.mu.Lock()
	s.phoneVX += dx
	s.phoneVY += dy
	ww, wh := worldDims(s.landscape, s.phoneW, s.phoneH)
	s.gyroCX = s.phoneVX + ww/2
	s.gyroCY = s.phoneVY + wh/2
	s.mu.Unlock()
}

func (s *AppState) toggleOrientationPreserveCenter() (land bool, vx, vy float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	oldW, oldH := worldDims(s.landscape, s.phoneW, s.phoneH)
	cx, cy := s.phoneVX+oldW/2, s.phoneVY+oldH/2
	s.landscape = !s.landscape
	newW, newH := worldDims(s.landscape, s.phoneW, s.phoneH)
	s.phoneVX, s.phoneVY = cx-newW/2, cy-newH/2
	s.gyroCX, s.gyroCY = cx, cy
	return s.landscape, s.phoneVX, s.phoneVY
}

func (s *AppState) gyroMapping() (cx, cy, wpp float64) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	ww, wh := worldDims(s.landscape, s.phoneW, s.phoneH)
	cx = s.phoneVX + ww/2
	cy = s.phoneVY + wh/2
	z := s.zoom
	if z < 0.001 {
		z = 0.001
	}
	wpp = 1.0 / (500.0 * z)
	return
}
