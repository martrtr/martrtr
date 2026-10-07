package main

import (
	"encoding/binary"
	"fmt"
	"math"
	"os"
	"strings"
	"time"

	"github.com/jezek/xgb"
	"github.com/jezek/xgb/xproto"
)

const toolbarH = 70

type ButtonKind int

const (
	btnFullscreen ButtonKind = iota + 1
	btnClear
	btnBG
	btnBrush
	btnMode
	btnRecenter
	btnOrient
	btnArea
	btnMic
	btnMicMenu
)

type RectI struct{ X, Y, W, H int }

func (r RectI) contains(x, y int) bool {
	return x >= r.X && y >= r.Y && x < r.X+r.W && y < r.Y+r.H
}

type UIButton struct {
	Kind ButtonKind
	R    RectI
}

type UI struct {
	conn   *xgb.Conn
	screen *xproto.ScreenInfo
	win    xproto.Window
	gc     xproto.Gcontext
	font   xproto.Font
	pix    xproto.Pixmap
	pixW   int
	pixH   int

	state  *AppState
	net    *Network
	mic    *Microphone
	redraw <-chan struct{}

	width  int
	height int

	buttons []UIButton
	hoverX  int
	hoverY  int

	fullscreen bool
	palette    bool
	colorTarget ButtonKind
	micMenu    bool

	dragging bool
	dragX    int
	dragY    int
	lastPanSend time.Time

	selecting bool
	areaDrag bool
	selX0, selY0 int
	selX1, selY1 int

	wmProtocols xproto.Atom
	wmDelete    xproto.Atom
	netWmState  xproto.Atom
	netWmFS     xproto.Atom
}

func ensureDisplay() {
	if os.Getenv("DISPLAY") != "" {
		return
	}
	for _, n := range []int{1, 2, 3, 4, 5, 0} {
		if _, err := os.Stat(fmt.Sprintf("/tmp/.X11-unix/X%d", n)); err == nil {
			_ = os.Setenv("DISPLAY", fmt.Sprintf(":%d", n))
			return
		}
	}
}

func NewUI(state *AppState, redraw <-chan struct{}) (*UI, error) {
	ensureDisplay()
	c, err := xgb.NewConn()
	if err != nil {
		return nil, fmt.Errorf("X11 connection failed (DISPLAY=%q): %w", os.Getenv("DISPLAY"), err)
	}
	setup := xproto.Setup(c)
	screen := setup.DefaultScreen(c)
	win, err := xproto.NewWindowId(c)
	if err != nil {
		c.Close()
		return nil, err
	}
	gc, err := xproto.NewGcontextId(c)
	if err != nil {
		c.Close()
		return nil, err
	}
	font, err := xproto.NewFontId(c)
	if err != nil {
		c.Close()
		return nil, err
	}
	_ = xproto.OpenFontChecked(c, font, uint16(len("fixed")), "fixed").Check()

	eventMask := uint32(
		xproto.EventMaskExposure |
			xproto.EventMaskStructureNotify |
			xproto.EventMaskButtonPress |
			xproto.EventMaskButtonRelease |
			xproto.EventMaskPointerMotion |
			xproto.EventMaskKeyPress,
	)
	xproto.CreateWindow(c, screen.RootDepth, win, screen.Root, 80, 80, 1180, 760, 0,
		xproto.WindowClassInputOutput, screen.RootVisual,
		xproto.CwBackPixel|xproto.CwEventMask,
		[]uint32{0x000000, eventMask})
	xproto.CreateGC(c, gc, xproto.Drawable(win),
		xproto.GcForeground|xproto.GcBackground|xproto.GcLineWidth|xproto.GcCapStyle|xproto.GcJoinStyle|xproto.GcFont,
		[]uint32{0xFFFFFF, 0x000000, 1, xproto.CapStyleRound, xproto.JoinStyleRound, uint32(font)})

	u := &UI{
		conn: c, screen: screen, win: win, gc: gc, font: font,
		state: state, redraw: redraw, width: 1180, height: 760,
	}
	u.wmProtocols = u.atom("WM_PROTOCOLS")
	u.wmDelete = u.atom("WM_DELETE_WINDOW")
	u.netWmState = u.atom("_NET_WM_STATE")
	u.netWmFS = u.atom("_NET_WM_STATE_FULLSCREEN")

	title := []byte("FootDraw Operator v11.1")
	xproto.ChangeProperty(c, xproto.PropModeReplace, win, xproto.AtomWmName, xproto.AtomString, 8, uint32(len(title)), title)
	del := make([]byte, 4)
	xgb.Put32(del, uint32(u.wmDelete))
	xproto.ChangeProperty(c, xproto.PropModeReplace, win, u.wmProtocols, xproto.AtomAtom, 32, 1, del)
	u.setWindowIcon()
	xproto.MapWindow(c, win)
	c.Flush()
	return u, nil
}

func (u *UI) Bind(netw *Network, mic *Microphone) {
	u.net = netw
	u.mic = mic
}

func (u *UI) Close() {
	if u.pix != 0 {
		xproto.FreePixmap(u.conn, u.pix)
	}
	if u.font != 0 {
		xproto.CloseFont(u.conn, u.font)
	}
	u.conn.Close()
}

func (u *UI) atom(name string) xproto.Atom {
	r, err := xproto.InternAtom(u.conn, false, uint16(len(name)), name).Reply()
	if err != nil {
		return 0
	}
	return r.Atom
}

func (u *UI) setWindowIcon() {
	a := u.atom("_NET_WM_ICON")
	if a == 0 {
		return
	}
	const n = 64
	vals := make([]uint32, 0, 2+n*n)
	vals = append(vals, n, n)
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			r, g, b := uint32(8), uint32(21), uint32(34)
			if x >= 5 && x < n-5 && y >= 5 && y < n-5 && (x < 9 || x >= n-9 || y < 9 || y >= n-9) {
				r, g, b = 39, 200, 255
			}
			// Minimal stylus: a clean diagonal body and cyan tip.
			d := absInt((x+y)-64)
			if x >= 19 && x <= 47 && y >= 17 && y <= 45 && d <= 3 {
				r, g, b = 248, 251, 255
			}
			if x >= 17 && x <= 27 && y >= 37 && y <= 49 && absInt((x+y)-65) <= 5 {
				r, g, b = 114, 223, 255
			}
			vals = append(vals, 0xFF000000|(r<<16)|(g<<8)|b)
		}
	}
	buf := make([]byte, len(vals)*4)
	for i, v := range vals {
		binary.LittleEndian.PutUint32(buf[i*4:], v)
	}
	xproto.ChangeProperty(u.conn, xproto.PropModeReplace, u.win, a, xproto.AtomCardinal, 32, uint32(len(vals)), buf)
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func (u *UI) recreatePixmap() {
	if u.width < 1 || u.height < 1 {
		return
	}
	if u.pix != 0 {
		xproto.FreePixmap(u.conn, u.pix)
		u.pix = 0
	}
	p, err := xproto.NewPixmapId(u.conn)
	if err != nil {
		return
	}
	u.pix = p
	u.pixW, u.pixH = u.width, u.height
	xproto.CreatePixmap(u.conn, u.screen.RootDepth, p, xproto.Drawable(u.win), uint16(u.width), uint16(u.height))
}

func (u *UI) setGC(fg uint32, width int, lineStyle uint32) {
	if width < 1 {
		width = 1
	}
	xproto.ChangeGC(u.conn, u.gc,
		xproto.GcForeground|xproto.GcLineWidth|xproto.GcLineStyle|xproto.GcCapStyle|xproto.GcJoinStyle,
		[]uint32{fg & 0xFFFFFF, uint32(width), lineStyle, xproto.CapStyleRound, xproto.JoinStyleRound})
}

func (u *UI) setTextGC(fg, bg uint32) {
	xproto.ChangeGC(u.conn, u.gc, xproto.GcForeground|xproto.GcBackground|xproto.GcFont,
		[]uint32{fg & 0xFFFFFF, bg & 0xFFFFFF, uint32(u.font)})
}

func (u *UI) fillRect(d xproto.Drawable, r RectI, color uint32) {
	if r.W <= 0 || r.H <= 0 {
		return
	}
	u.setGC(color, 1, xproto.LineStyleSolid)
	xproto.PolyFillRectangle(u.conn, d, u.gc, []xproto.Rectangle{{
		X: int16(r.X), Y: int16(r.Y), Width: uint16(r.W), Height: uint16(r.H),
	}})
}

func (u *UI) outlineRect(d xproto.Drawable, r RectI, color uint32, width int, dashed bool) {
	if r.W <= 0 || r.H <= 0 {
		return
	}
	style := uint32(xproto.LineStyleSolid)
	if dashed {
		style = xproto.LineStyleOnOffDash
	}
	u.setGC(color, width, style)
	xproto.PolyRectangle(u.conn, d, u.gc, []xproto.Rectangle{{
		X: int16(r.X), Y: int16(r.Y), Width: uint16(maxInt(1, r.W-1)), Height: uint16(maxInt(1, r.H-1)),
	}})
}

func (u *UI) line(d xproto.Drawable, x1, y1, x2, y2 int, color uint32, width int) {
	u.setGC(color, width, xproto.LineStyleSolid)
	xproto.PolyLine(u.conn, xproto.CoordModeOrigin, d, u.gc, []xproto.Point{
		{X: clampI16(x1), Y: clampI16(y1)},
		{X: clampI16(x2), Y: clampI16(y2)},
	})
}

func (u *UI) fillCircle(d xproto.Drawable, cx, cy, r int, color uint32) {
	u.setGC(color, 1, xproto.LineStyleSolid)
	xproto.PolyFillArc(u.conn, d, u.gc, []xproto.Arc{{
		X: int16(cx-r), Y: int16(cy-r), Width: uint16(r * 2), Height: uint16(r * 2),
		Angle1: 0, Angle2: 360 * 64,
	}})
}

func (u *UI) fillPoly(d xproto.Drawable, pts []xproto.Point, color uint32) {
	u.setGC(color, 1, xproto.LineStyleSolid)
	xproto.FillPoly(u.conn, d, u.gc, xproto.PolyShapeConvex, xproto.CoordModeOrigin, pts)
}

func clampI16(v int) int16 {
	if v < -32000 {
		return -32000
	}
	if v > 32000 {
		return 32000
	}
	return int16(v)
}
func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func sanitizeText(s string, n int) string {
	var b strings.Builder
	for _, r := range s {
		if b.Len() >= n {
			break
		}
		if r >= 32 && r <= 126 {
			b.WriteByte(byte(r))
		} else {
			b.WriteByte('?')
		}
	}
	return b.String()
}

func (u *UI) text(d xproto.Drawable, x, y int, s string, fg, bg uint32) {
	s = sanitizeText(s, 90)
	if len(s) > 255 {
		s = s[:255]
	}
	u.setTextGC(fg, bg)
	xproto.ImageText8(u.conn, byte(len(s)), d, u.gc, int16(x), int16(y), s)
}

func (u *UI) layoutButtons(gyro bool) {
	const big = 46
	const small = 26
	const gap = 8
	const groupGap = 18
	type item struct {
		kind  ButtonKind
		w     int
		after int
	}
	items := []item{
		{btnFullscreen, big, groupGap},
		{btnClear, big, gap}, {btnBG, big, gap}, {btnBrush, big, groupGap},
		{btnMode, big, groupGap},
	}
	if gyro {
		items = append(items, item{btnRecenter, big, groupGap})
	} else {
		items = append(items, item{btnOrient, big, gap}, item{btnArea, big, groupGap})
	}
	items = append(items, item{btnMic, big, 0}, item{btnMicMenu, small, 0})
	total := 0
	for i, it := range items {
		total += it.w
		if i < len(items)-1 {
			total += it.after
		}
	}
	x := (u.width - total) / 2
	if x < 8 {
		x = 8
	}
	u.buttons = u.buttons[:0]
	for i, it := range items {
		u.buttons = append(u.buttons, UIButton{Kind: it.kind, R: RectI{X: x, Y: 12, W: it.w, H: big}})
		x += it.w
		if i < len(items)-1 {
			x += it.after
		}
	}
}

func (u *UI) phoneFrame(s StateSnapshot) (RectI, float64, float64, float64) {
	ww, wh := worldDims(s.Landscape, s.PhoneW, s.PhoneH)
	scale := 500.0 * s.Zoom
	cx := float64(u.width) / 2
	cy := float64(toolbarH+u.height) / 2
	worldCX := s.PhoneVX + ww/2
	worldCY := s.PhoneVY + wh/2
	l := int(math.Round(cx + (s.PhoneVX-worldCX)*scale))
	t := int(math.Round(cy + (s.PhoneVY-worldCY)*scale))
	r := int(math.Round(cx + (s.PhoneVX+ww-worldCX)*scale))
	b := int(math.Round(cy + (s.PhoneVY+wh-worldCY)*scale))
	return RectI{X: l, Y: t, W: r - l, H: b - t}, worldCX, worldCY, scale
}

func (u *UI) worldToScreen(x, y, centerX, centerY, scale float64) (int, int) {
	cx := float64(u.width) / 2
	cy := float64(toolbarH+u.height) / 2
	return int(math.Round(cx + (x-centerX)*scale)), int(math.Round(cy + (y-centerY)*scale))
}

func (u *UI) render() {
	if u.width < 2 || u.height < 2 {
		return
	}
	if u.pix == 0 || u.pixW != u.width || u.pixH != u.height {
		u.recreatePixmap()
	}
	if u.pix == 0 {
		return
	}
	d := xproto.Drawable(u.pix)
	s := u.state.snapshot()
	u.layoutButtons(s.GyroMode)
	u.fillRect(d, RectI{0, 0, u.width, u.height}, s.BgColor)

	ww, wh := worldDims(s.Landscape, s.PhoneW, s.PhoneH)
	scale := 500.0 * s.Zoom
	centerX, centerY := s.PhoneVX+ww/2, s.PhoneVY+wh/2
	if s.GyroMode {
		centerX, centerY = s.GyroCX, s.GyroCY
	}

	prev := make(map[string][2]float64)
	for _, e := range s.Events {
		if p, ok := prev[e.Stroke]; ok {
			x1, y1 := u.worldToScreen(p[0], p[1], centerX, centerY, scale)
			x2, y2 := u.worldToScreen(e.X, e.Y, centerX, centerY, scale)
			u.line(d, x1, y1, x2, y2, e.Color, 4)
		}
		if e.Kind == "U" {
			delete(prev, e.Stroke)
		} else {
			prev[e.Stroke] = [2]float64{e.X, e.Y}
		}
	}

	if s.GyroMode {
		ccx := u.width / 2
		ccy := (toolbarH + u.height) / 2
		u.fillCircle(d, ccx, ccy, 3, 0x78AFFF)
		px := ccx + int(math.Round(s.GyroDX*800.0))
		py := ccy + int(math.Round(s.GyroDY*800.0))
		col := s.BrushColor
		if s.GyroDown {
			col = 0xFFFFFF
		}
		u.line(d, px-9, py, px-2, py, col, 3)
		u.line(d, px+2, py, px+9, py, col, 3)
		u.line(d, px, py-9, px, py-2, col, 3)
		u.line(d, px, py+2, px, py+9, col, 3)
		if s.GyroDown {
			u.fillCircle(d, px, py, 2, s.BrushColor)
		}
	} else {
		fr, _, _, _ := u.phoneFrame(s)
		u.outlineRect(d, fr, 0x78AFFF, 3, false)
		if s.Clip {
			ar := RectI{
				X: fr.X + int(float64(fr.W)*s.ClipL),
				Y: fr.Y + int(float64(fr.H)*s.ClipT),
				W: int(float64(fr.W) * (s.ClipR - s.ClipL)),
				H: int(float64(fr.H) * (s.ClipB - s.ClipT)),
			}
			u.outlineRect(d, ar, 0xFFBE46, 3, false)
		}
		if u.selecting && u.areaDrag {
			x0, x1 := u.selX0, u.selX1
			y0, y1 := u.selY0, u.selY1
			if x0 > x1 {
				x0, x1 = x1, x0
			}
			if y0 > y1 {
				y0, y1 = y1, y0
			}
			u.outlineRect(d, RectI{x0, y0, x1 - x0, y1 - y0}, 0x6EE7B7, 2, true)
		}
	}

	u.drawToolbar(d, s)
	if u.palette {
		u.drawPalette(d, s)
	}
	if u.micMenu {
		u.drawMicMenu(d)
	}
	xproto.CopyArea(u.conn, d, xproto.Drawable(u.win), u.gc, 0, 0, 0, 0, uint16(u.width), uint16(u.height))
	u.conn.Flush()
}

func (u *UI) drawToolbar(d xproto.Drawable, s StateSnapshot) {
	for _, b := range u.buttons {
		bg := uint32(0x151B24)
		fg := uint32(0xEAF0F7)
		if b.R.contains(u.hoverX, u.hoverY) {
			bg = 0x222C39
		}
		if b.Kind == btnArea && (s.Clip || u.selecting) {
			bg, fg = 0xF2F5F8, 0x10151D
		}
		u.fillRect(d, b.R, bg)
		u.outlineRect(d, b.R, 0x303B4A, 1, false)
		u.drawButtonIcon(d, b, s, fg)
	}
}

func (u *UI) drawButtonIcon(d xproto.Drawable, b UIButton, s StateSnapshot, fg uint32) {
	cx, cy := b.R.X+b.R.W/2, b.R.Y+b.R.H/2
	switch b.Kind {
	case btnFullscreen:
		pad := 11
		x1, y1, x2, y2 := b.R.X+pad, b.R.Y+pad, b.R.X+b.R.W-pad, b.R.Y+b.R.H-pad
		if !u.fullscreen {
			u.line(d, x1+7, y1, x1, y1, fg, 2); u.line(d, x1, y1, x1, y1+7, fg, 2)
			u.line(d, x2-7, y1, x2, y1, fg, 2); u.line(d, x2, y1, x2, y1+7, fg, 2)
			u.line(d, x1+7, y2, x1, y2, fg, 2); u.line(d, x1, y2, x1, y2-7, fg, 2)
			u.line(d, x2-7, y2, x2, y2, fg, 2); u.line(d, x2, y2, x2, y2-7, fg, 2)
		} else {
			ix1, iy1, ix2, iy2 := x1+3, y1+3, x2-3, y2-3
			u.line(d, ix1, iy1+7, ix1+7, iy1+7, fg, 2); u.line(d, ix1+7, iy1+7, ix1+7, iy1, fg, 2)
			u.line(d, ix2, iy1+7, ix2-7, iy1+7, fg, 2); u.line(d, ix2-7, iy1+7, ix2-7, iy1, fg, 2)
			u.line(d, ix1, iy2-7, ix1+7, iy2-7, fg, 2); u.line(d, ix1+7, iy2-7, ix1+7, iy2, fg, 2)
			u.line(d, ix2, iy2-7, ix2-7, iy2-7, fg, 2); u.line(d, ix2-7, iy2-7, ix2-7, iy2, fg, 2)
		}
	case btnClear:
		body := []xproto.Point{{X:int16(cx-12),Y:int16(cy+5)},{X:int16(cx+1),Y:int16(cy-10)},{X:int16(cx+12),Y:int16(cy-1)},{X:int16(cx-2),Y:int16(cy+14)}}
		tip := []xproto.Point{{X:int16(cx-12),Y:int16(cy+5)},{X:int16(cx-6),Y:int16(cy-2)},{X:int16(cx+4),Y:int16(cy+7)},{X:int16(cx-2),Y:int16(cy+14)}}
		u.fillPoly(d, body, 0xF5F7FB); u.fillPoly(d, tip, 0xFF6F91)
	case btnBG:
		u.outlineRect(d, RectI{cx-11, cy-9, 22, 18}, fg, 2, false)
		u.fillCircle(d, cx+5, cy-3, 3, 0xFFD166)
		u.line(d, cx-9, cy+6, cx-2, cy, 0x6EE7B7, 2)
		u.line(d, cx-2, cy, cx+8, cy+7, 0x6EE7B7, 2)
	case btnBrush:
		u.line(d, cx-9, cy+9, cx+7, cy-7, fg, 5)
		u.fillCircle(d, cx+9, cy-9, 4, s.BrushColor)
	case btnMode:
		if s.GyroMode {
			u.line(d, cx-10, cy, cx-3, cy, fg, 2); u.line(d, cx+3, cy, cx+10, cy, fg, 2)
			u.line(d, cx, cy-10, cx, cy-3, fg, 2); u.line(d, cx, cy+3, cx, cy+10, fg, 2)
			u.fillCircle(d, cx, cy, 2, 0x78AFFF)
		} else {
			u.line(d, cx-9, cy+9, cx+8, cy-8, fg, 5)
			u.fillCircle(d, cx+9, cy-9, 4, 0x78AFFF)
		}
	case btnRecenter:
		u.line(d, cx-11, cy, cx-4, cy, fg, 2); u.line(d, cx+4, cy, cx+11, cy, fg, 2)
		u.line(d, cx, cy-11, cx, cy-4, fg, 2); u.line(d, cx, cy+4, cx, cy+11, fg, 2)
		u.fillCircle(d, cx, cy, 3, fg)
	case btnOrient:
		if s.Landscape {
			u.outlineRect(d, RectI{cx-13, cy-8, 26, 16}, fg, 2, false)
		} else {
			u.outlineRect(d, RectI{cx-8, cy-13, 16, 26}, fg, 2, false)
		}
	case btnArea:
		u.line(d, cx-11, cy-9, cx-4, cy-9, fg, 2); u.line(d, cx-11, cy-9, cx-11, cy-2, fg, 2)
		u.line(d, cx+11, cy-9, cx+4, cy-9, fg, 2); u.line(d, cx+11, cy-9, cx+11, cy-2, fg, 2)
		u.line(d, cx-11, cy+9, cx-4, cy+9, fg, 2); u.line(d, cx-11, cy+9, cx-11, cy+2, fg, 2)
		u.line(d, cx+11, cy+9, cx+4, cy+9, fg, 2); u.line(d, cx+11, cy+9, cx+11, cy+2, fg, 2)
	case btnMic:
		muted := u.mic != nil && u.mic.Muted()
		u.outlineRect(d, RectI{cx-5, cy-10, 10, 17}, fg, 2, false)
		u.line(d, cx-9, cy+2, cx-9, cy+4, fg, 2); u.line(d, cx-9, cy+4, cx, cy+10, fg, 2)
		u.line(d, cx, cy+10, cx+9, cy+4, fg, 2); u.line(d, cx+9, cy+4, cx+9, cy+2, fg, 2)
		u.line(d, cx, cy+10, cx, cy+14, fg, 2)
		if muted { u.line(d, cx-12, cy-12, cx+12, cy+12, 0xFF6B6B, 3) }
	case btnMicMenu:
		pts := []xproto.Point{{X:int16(cx-5),Y:int16(cy-2)},{X:int16(cx+5),Y:int16(cy-2)},{X:int16(cx),Y:int16(cy+4)}}
		u.fillPoly(d, pts, fg)
	}
}

var paletteColors = []uint32{
	0xFFFFFF, 0xEBEEF4, 0xB8C0CC, 0x6B7280,
	0x111827, 0x000000, 0xFF6B6B, 0xFF9F43,
	0xFFD93D, 0x6BCB77, 0x4D96FF, 0x6C5CE7,
	0xA66CFF, 0xFF6FB1, 0x00D2D3, 0x8D6E63,
}

func (u *UI) paletteRect() RectI {
	x := u.width/2 - 68
	for _, b := range u.buttons {
		if b.Kind == u.colorTarget {
			x = b.R.X
			break
		}
	}
	w := 4*30 + 16
	if x+w > u.width-8 {
		x = u.width - 8 - w
	}
	if x < 8 { x = 8 }
	return RectI{x, 64, w, 4*30 + 16}
}

func (u *UI) drawPalette(d xproto.Drawable, s StateSnapshot) {
	r := u.paletteRect()
	u.fillRect(d, r, 0x111720)
	u.outlineRect(d, r, 0x344050, 1, false)
	for i, col := range paletteColors {
		row, coln := i/4, i%4
		cr := RectI{r.X+8+coln*30, r.Y+8+row*30, 24, 24}
		u.fillRect(d, cr, col)
		selected := (u.colorTarget == btnBG && s.BgColor == col) || (u.colorTarget == btnBrush && s.BrushColor == col)
		if selected {
			u.outlineRect(d, RectI{cr.X-2,cr.Y-2,cr.W+4,cr.H+4}, 0x78AFFF, 2, false)
		}
	}
}

func (u *UI) paletteHit(x, y int) (uint32, bool) {
	r := u.paletteRect()
	if !r.contains(x, y) { return 0, false }
	for i, col := range paletteColors {
		row, coln := i/4, i%4
		cr := RectI{r.X+8+coln*30, r.Y+8+row*30, 24, 24}
		if cr.contains(x,y) { return col, true }
	}
	return 0, false
}

func (u *UI) micMenuRect() RectI {
	x := u.width/2 - 180
	for _, b := range u.buttons {
		if b.Kind == btnMic {
			x = b.R.X - 280
			break
		}
	}
	if x < 8 { x=8 }
	if x+360 > u.width-8 { x=u.width-368 }
	devs,_ := u.mic.Devices()
	rows := minInt(len(devs), 12)
	return RectI{x,64,360,8+rows*26}
}

func (u *UI) drawMicMenu(d xproto.Drawable) {
	if u.mic == nil { return }
	devs, selected := u.mic.Devices()
	r := u.micMenuRect()
	u.fillRect(d,r,0x111720)
	u.outlineRect(d,r,0x344050,1,false)
	rows := minInt(len(devs),12)
	for i:=0;i<rows;i++ {
		rr:=RectI{r.X+5,r.Y+5+i*26,r.W-10,24}
		bg:=uint32(0x111720); fg:=uint32(0xEAF0F7)
		if i==selected { bg=0x263B55 }
		if rr.contains(u.hoverX,u.hoverY) { bg=0x222C39 }
		u.fillRect(d,rr,bg)
		u.text(d,rr.X+8,rr.Y+16,devs[i].Label,fg,bg)
	}
}

func (u *UI) micMenuHit(x,y int)(int,bool){
	if u.mic==nil{return 0,false}
	r:=u.micMenuRect()
	if !r.contains(x,y){return 0,false}
	devs,_:=u.mic.Devices()
	rows:=minInt(len(devs),12)
	for i:=0;i<rows;i++{
		rr:=RectI{r.X+5,r.Y+5+i*26,r.W-10,24}
		if rr.contains(x,y){return i,true}
	}
	return 0,false
}

func (u *UI) buttonAt(x,y int)(ButtonKind,bool){
	for _,b:=range u.buttons{if b.R.contains(x,y){return b.Kind,true}}
	return 0,false
}

func (u *UI) requestFullscreen(on bool) {
	action:=uint32(0)
	if on { action=1 }
	ev:=xproto.ClientMessageEvent{Format:32,Window:u.win,Type:u.netWmState,
		Data:xproto.ClientMessageDataUnionData32New([]uint32{action,uint32(u.netWmFS),0,1,0})}
	xproto.SendEvent(u.conn,false,u.screen.Root,xproto.EventMaskSubstructureRedirect|xproto.EventMaskSubstructureNotify,string(ev.Bytes()))
	u.fullscreen=on
	u.conn.Flush()
}

func (u *UI) handleButton(k ButtonKind) {
	if u.net==nil{return}
	s:=u.state.snapshot()
	u.palette=false
	u.micMenu=false
	switch k {
	case btnFullscreen:
		u.requestFullscreen(!u.fullscreen)
	case btnClear:
		u.net.Send("CLEAR")
	case btnBG:
		u.palette=true;u.colorTarget=btnBG
	case btnBrush:
		u.palette=true;u.colorTarget=btnBrush
	case btnMode:
		u.state.mu.Lock()
		u.state.gyroMode=!u.state.gyroMode
		g:=u.state.gyroMode
		u.state.gyroDX,u.state.gyroDY,u.state.gyroDown=0,0,false
		u.state.mu.Unlock()
		u.selecting=false;u.areaDrag=false
		u.net.SendMode(g)
		if g{u.net.SendGyroView()}
	case btnRecenter:
		if !s.GyroMode{return}
		u.state.mu.Lock();u.state.gyroDX,u.state.gyroDY=0,0;u.state.mu.Unlock()
		u.net.SendMode(true)
	case btnOrient:
		if s.GyroMode{return}
		land,vx,vy:=u.state.toggleOrientationPreserveCenter()
		u.net.SendOrient(land)
		u.net.Send(fmt.Sprintf("VIEW %.12g %.12g",vx,vy))
	case btnArea:
		if s.GyroMode{return}
		if s.Clip{
			u.state.mu.Lock();u.state.clip=false;u.state.mu.Unlock()
			u.net.SendClip(false,0,0,1,1)
			u.selecting=false
		}else{
			u.selecting=!u.selecting
			u.areaDrag=false
		}
	case btnMic:
		if u.mic!=nil{u.mic.ToggleMute()}
	case btnMicMenu:
		if u.mic!=nil{u.mic.RefreshDevices();u.micMenu=true}
	}
}

func (u *UI) handlePress(e xproto.ButtonPressEvent) {
	x,y:=int(e.EventX),int(e.EventY)
	u.hoverX,u.hoverY=x,y
	if e.Detail==xproto.ButtonIndex4 || e.Detail==xproto.ButtonIndex5 {
		s:=u.state.snapshot(); z:=s.Zoom
		if e.Detail==xproto.ButtonIndex4{z*=1.12}else{z/=1.12}
		u.state.setZoom(z)
		if u.net!=nil{u.net.SendGyroView()}
		return
	}
	if e.Detail!=xproto.ButtonIndex1{return}

	if u.palette {
		if col,ok:=u.paletteHit(x,y);ok{
			if u.colorTarget==btnBG{
				u.state.mu.Lock();u.state.bgColor=col;u.state.mu.Unlock();u.net.SendBG(col)
			}else{
				u.state.mu.Lock();u.state.brushColor=col;u.state.mu.Unlock();u.net.SendBrush(col)
			}
			u.palette=false
			return
		}
		u.palette=false
	}
	if u.micMenu{
		if idx,ok:=u.micMenuHit(x,y);ok{u.mic.Select(idx);u.micMenu=false;return}
		u.micMenu=false
	}
	if k,ok:=u.buttonAt(x,y);ok{u.handleButton(k);return}
	s:=u.state.snapshot()
	if u.selecting&&!s.GyroMode{
		u.areaDrag=true;u.selX0,u.selY0=x,y;u.selX1,u.selY1=x,y;return
	}
	u.dragging=true;u.dragX,u.dragY=x,y
}

func (u *UI) handleMotion(e xproto.MotionNotifyEvent){
	x,y:=int(e.EventX),int(e.EventY)
	u.hoverX,u.hoverY=x,y
	if u.areaDrag{
		u.selX1,u.selY1=x,y
		return
	}
	if u.dragging && (e.State&xproto.KeyButMaskButton1)!=0{
		dx,dy:=x-u.dragX,y-u.dragY
		u.dragX,u.dragY=x,y
		s:=u.state.snapshot();scale:=500.0*s.Zoom
		if scale<1{scale=1}
		u.state.panByWorld(-float64(dx)/scale,-float64(dy)/scale)
		if time.Since(u.lastPanSend)>=16*time.Millisecond && u.net!=nil{
			u.net.SendView();u.net.SendGyroView();u.lastPanSend=time.Now()
		}
	}
}

func (u *UI) finishArea(){
	s:=u.state.snapshot()
	fr,_,_,_:=u.phoneFrame(s)
	x0,x1:=u.selX0,u.selX1;y0,y1:=u.selY0,u.selY1
	if x0>x1{x0,x1=x1,x0};if y0>y1{y0,y1=y1,y0}
	x0=maxInt(x0,fr.X);x1=minInt(x1,fr.X+fr.W)
	y0=maxInt(y0,fr.Y);y1=minInt(y1,fr.Y+fr.H)
	u.areaDrag=false;u.selecting=false
	if fr.W<1||fr.H<1||x1-x0<5||y1-y0<5{return}
	l:=clamp(float64(x0-fr.X)/float64(fr.W),0,1)
	t:=clamp(float64(y0-fr.Y)/float64(fr.H),0,1)
	r:=clamp(float64(x1-fr.X)/float64(fr.W),l,1)
	b:=clamp(float64(y1-fr.Y)/float64(fr.H),t,1)
	u.state.mu.Lock();u.state.clip=true;u.state.clipL=l;u.state.clipT=t;u.state.clipR=r;u.state.clipB=b;u.state.mu.Unlock()
	if u.net!=nil{u.net.SendClip(true,l,t,r,b)}
}

func (u *UI) handleRelease(e xproto.ButtonReleaseEvent){
	if e.Detail!=xproto.ButtonIndex1{return}
	if u.areaDrag{u.selX1,u.selY1=int(e.EventX),int(e.EventY);u.finishArea();return}
	if u.dragging{
		u.dragging=false
		if u.net!=nil{u.net.SendView();u.net.SendGyroView()}
	}
}

func (u *UI) Run() error {
	evCh:=make(chan xgb.Event,64)
	errCh:=make(chan error,1)
	go func(){
		for{
			ev,err:=u.conn.WaitForEvent()
			if err!=nil{errCh<-err;return}
			evCh<-ev
		}
	}()
	u.render()
	for{
		select{
		case <-u.redraw:
			u.render()
		case err:=<-errCh:
			return err
		case ev:=<-evCh:
			switch e:=ev.(type){
			case xproto.ExposeEvent:
				if e.Count==0{u.render()}
			case xproto.ConfigureNotifyEvent:
				u.width,u.height=int(e.Width),int(e.Height);u.render()
			case xproto.MotionNotifyEvent:
				u.handleMotion(e);u.render()
			case xproto.ButtonPressEvent:
				u.handlePress(e);u.render()
			case xproto.ButtonReleaseEvent:
				u.handleRelease(e);u.render()
			case xproto.ClientMessageEvent:
				if e.Type==u.wmProtocols && len(e.Data.Data32)>0 && xproto.Atom(e.Data.Data32[0])==u.wmDelete{return nil}
			}
		}
	}
}
