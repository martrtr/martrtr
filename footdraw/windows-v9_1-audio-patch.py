from pathlib import Path


def rep(s, old, new, label):
    if old not in s:
        raise SystemExit(f'missing patch anchor: {label}')
    return s.replace(old, new, 1)

# Keep the v9.1 redundant low-loss voice transport.
p=Path('net.go')
s=p.read_text()
start=s.index('func audioSenderLoop')
new_audio=r'''func audioSenderLoop(ch <-chan []byte) {
	const frameBytes = 640 // 20 ms @ 16 kHz mono PCM16
	addr, _ := net.ResolveUDPAddr("udp", serverAddr)
	var prev []byte
	var accum []byte
	for {
		c, err := net.DialUDP("udp", nil, addr)
		if err != nil { time.Sleep(300 * time.Millisecond); continue }
		_ = c.SetWriteBuffer(256 * 1024)
		for block := range ch {
			if len(block) == 0 { continue }
			if isMicMuted() { accum = accum[:0]; prev = nil; continue }
			accum = append(accum, block...)
			for len(accum) >= frameBytes {
				cur := append([]byte(nil), accum[:frameBytes]...)
				accum = accum[frameBytes:]
				prevLen := 0
				if len(prev) == frameBytes { prevLen = frameBytes }
				inner := make([]byte, 4+2+frameBytes+2+prevLen)
				copy(inner[:4], []byte("FDR2"))
				binary.BigEndian.PutUint16(inner[4:6], uint16(frameBytes))
				copy(inner[6:6+frameBytes], cur)
				o := 6 + frameBytes
				binary.BigEndian.PutUint16(inner[o:o+2], uint16(prevLen))
				if prevLen != 0 { copy(inner[o+2:], prev) }
				seq := atomic.AddUint32(&audioSeq, 1)
				pkt := make([]byte, 42+len(inner))
				copy(pkt[:4], []byte("FDO1")); copy(pkt[4:36], []byte(sharedToken))
				binary.BigEndian.PutUint32(pkt[36:40], seq)
				binary.BigEndian.PutUint16(pkt[40:42], uint16(len(inner)/2))
				copy(pkt[42:], inner)
				if _, err = c.Write(pkt); err != nil { _ = c.Close(); break }
				prev = cur
			}
			if err != nil { break }
		}
	}
}
'''
s=s[:start]+new_audio

# Gyro protocol state on the operator.
s=rep(s,
'''var ignoreNextOrientState atomic.Bool
''',
'''var ignoreNextOrientState atomic.Bool
var gyroMode bool
var gyroDX, gyroDY float64
var gyroDown bool
''','net globals')
s=rep(s,
'''func sendClip(enabled bool, l, t, r, b float64) {
''',
'''func sendMode(v bool) {
	if v { sendTCP("MODE 1") } else { sendTCP("MODE 0") }
}

func sendGyroView() {
	stateMu.RLock()
	ww, wh := worldDims(phoneLandscape, phoneW, phoneH)
	cx := phoneVX + ww/2
	cy := phoneVY + wh/2
	z := pcZoom
	stateMu.RUnlock()
	if z < 0.001 { z = 0.001 }
	wpp := 1.0 / (500.0 * z)
	sendTCP(fmt.Sprintf("GVIEW %.12g %.12g %.12g", cx, cy, wpp))
}

func sendClip(enabled bool, l, t, r, b float64) {
''','send gyro funcs')
s=rep(s,
'''	case "RESET":
''',
'''	case "MODE":
		if len(a) >= 2 {
			stateMu.Lock()
			gyroMode = a[1] != "0"
			if !gyroMode { gyroDX, gyroDY, gyroDown = 0, 0, false }
			stateMu.Unlock()
			invalidate()
		}
	case "GC":
		if len(a) >= 4 {
			dx, _ := strconv.ParseFloat(a[1], 64)
			dy, _ := strconv.ParseFloat(a[2], 64)
			stateMu.Lock(); gyroDX = dx; gyroDY = dy; gyroDown = a[3] != "0"; stateMu.Unlock()
			invalidate()
		}
	case "GVIEW":
		// Operator is the authority for this mapping; the echoed value is only for reconnect sync.
	case "RESET":
''','handle gyro lines')
p.write_text(s)

p=Path('main_windows.go')
s=p.read_text()
s=rep(s,
'''	btnBrushTarget
	btnOrient
''',
'''	btnBrushTarget
	btnMode
	btnOrient
''','button enum')
s=rep(s,
'''widths := []int32{big, big, big, big, big, big, big + small}''',
'''widths := []int32{big, big, big, big, big, big, big, big + small}''','toolbar widths')
s=rep(s,
'''	addBig(btnBrushTarget)
	addBig(btnOrient)
''',
'''	addBig(btnBrushTarget)
	addBig(btnMode)
	addBig(btnOrient)
''','toolbar mode button')
s=rep(s,
'''	case btnOrient:
''',
'''	case btnMode:
		stateMu.Lock()
		gyroMode = !gyroMode
		gm := gyroMode
		gyroDX, gyroDY, gyroDown = 0, 0, false
		stateMu.Unlock()
		sendMode(gm)
		if gm { sendGyroView() }
		invalidate()
	case btnOrient:
''','mode handler')
s=rep(s,
'''	case btnArea:
		stateMu.RLock()
		ce := clipEnabled
		stateMu.RUnlock()
''',
'''	case btnArea:
		stateMu.RLock()
		ce := clipEnabled
		gm := gyroMode
		stateMu.RUnlock()
		if gm { return }
''','disable area in gyro')
s=rep(s,
'''			sendView(false)
			invalidate()
''',
'''			sendView(false)
			sendGyroView()
			invalidate()
''','pan gyro mapping')
s=rep(s,
'''		invalidate()
		return 0
	case WM_SIZE:
''',
'''		sendGyroView()
		invalidate()
		return 0
	case WM_SIZE:
''','zoom gyro mapping')
# Send final exact mapping when a drag ends too.
s=rep(s,
'''		if dragging {
			dragging = false
			sendView(true)
			pReleaseCapture.Call()
		}
''',
'''		if dragging {
			dragging = false
			sendView(true)
			sendGyroView()
			pReleaseCapture.Call()
		}
''','drag end gyro mapping')

# Mode button visual state and custom icon.
s=rep(s,
'''	if b.kind == btnOrient && land {
		base = 0x263B55
	}
''',
'''	if b.kind == btnOrient && land { base = 0x263B55 }
	if b.kind == btnMode {
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if gm { base = 0xF2F5F8; fg = 0x10151D }
	}
''','mode active style')
s=rep(s,
'''	case btnOrient:
		drawCentered(hdc, b.r, "↻", fg)
''',
'''	case btnMode:
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if gm {
			// Gyro/crosshair icon.
			outlineCircle(hdc, RECT{cx-11, cy-11, cx+11, cy+11}, fg, 2)
			outlineCircle(hdc, RECT{cx-5, cy-5, cx+5, cy+5}, fg, 2)
			drawCircle(hdc, RECT{cx-2, cy-2, cx+2, cy+2}, fg)
		} else {
			// Direct-drawing stylus icon.
			pen, _, _ := pCreatePen.Call(PS_SOLID, 5, uintptr(rgbHexToColorRef(fg)))
			op, _, _ := pSelect.Call(hdc, pen)
			pMove.Call(hdc, uintptr(cx-9), uintptr(cy+9), 0); pLine.Call(hdc, uintptr(cx+8), uintptr(cy-8))
			pSelect.Call(hdc, op); pDelete.Call(pen)
			drawCircle(hdc, RECT{cx+5, cy-11, cx+12, cy-4}, 0x78AFFF)
		}
	case btnOrient:
		drawCentered(hdc, b.r, "↻", fg)
''','mode icon')
s=rep(s,
'''func outlineRect(hdc uintptr, r RECT, color uint32, width int, style int) {
''',
'''func outlineCircle(hdc uintptr, r RECT, color uint32, width int) {
	pen, _, _ := pCreatePen.Call(PS_SOLID, uintptr(width), uintptr(rgbHexToColorRef(color)))
	oldPen, _, _ := pSelect.Call(hdc, pen)
	hollow, _, _ := pGetStock.Call(HOLLOW_BRUSH)
	oldBr, _, _ := pSelect.Call(hdc, hollow)
	pEllipse.Call(hdc, uintptr(r.Left), uintptr(r.Top), uintptr(r.Right), uintptr(r.Bottom))
	pSelect.Call(hdc, oldBr); pSelect.Call(hdc, oldPen); pDelete.Call(pen)
}

func outlineRect(hdc uintptr, r RECT, color uint32, width int, style int) {
''','outline circle helper')

# Snapshot gyro state in renderCanvas.
s=rep(s,
'''	land := phoneLandscape
	stateMu.RUnlock()
''',
'''	land := phoneLandscape
	gm, gdx, gdy, gdown := gyroMode, gyroDX, gyroDY, gyroDown
	stateMu.RUnlock()
''','render gyro snapshot')
old_frame='''	l, t := toScreen(vx, vy)
	r, b := toScreen(vx+ww, vy+wh)
	outlineRect(hdc, RECT{l, t, r, b}, 0x78AFFF, 3, PS_SOLID)
	if ce {
		ar := RECT{int32(float64(l) + float64(r-l)*cl), int32(float64(t) + float64(b-t)*ct), int32(float64(l) + float64(r-l)*cr), int32(float64(t) + float64(b-t)*cb)}
		outlineRect(hdc, ar, 0xFFBE46, 3, PS_SOLID)
	}
	if selecting && areaDrag {
		sr := RECT{selX0, selY0, selX1, selY1}
		if sr.Left > sr.Right {
			sr.Left, sr.Right = sr.Right, sr.Left
		}
		if sr.Top > sr.Bottom {
			sr.Top, sr.Bottom = sr.Bottom, sr.Top
		}
		outlineRect(hdc, sr, 0x6EE7B7, 2, PS_DASH)
	}
'''
new_frame='''	if gm {
		// Gyro mode has no phone viewport. The board moves underneath two screen-fixed overlays:
		// the calibration centre and the live toe-direction cursor.
		ccx, ccy := int32(cx), int32(cy)
		outlineCircle(hdc, RECT{ccx-8, ccy-8, ccx+8, ccy+8}, 0x78AFFF, 2)
		drawCircle(hdc, RECT{ccx-2, ccy-2, ccx+2, ccy+2}, 0x78AFFF)
		px := ccx + int32(gdx*800.0)
		py := ccy + int32(gdy*800.0)
		if gdown {
			drawCircle(hdc, RECT{px-8, py-8, px+8, py+8}, brush)
			outlineCircle(hdc, RECT{px-11, py-11, px+11, py+11}, 0xFFFFFF, 2)
		} else {
			outlineCircle(hdc, RECT{px-8, py-8, px+8, py+8}, brush, 3)
		}
	} else {
		l, t := toScreen(vx, vy)
		r, b := toScreen(vx+ww, vy+wh)
		outlineRect(hdc, RECT{l, t, r, b}, 0x78AFFF, 3, PS_SOLID)
		if ce {
			ar := RECT{int32(float64(l) + float64(r-l)*cl), int32(float64(t) + float64(b-t)*ct), int32(float64(l) + float64(r-l)*cr), int32(float64(t) + float64(b-t)*cb)}
			outlineRect(hdc, ar, 0xFFBE46, 3, PS_SOLID)
		}
		if selecting && areaDrag {
			sr := RECT{selX0, selY0, selX1, selY1}
			if sr.Left > sr.Right { sr.Left, sr.Right = sr.Right, sr.Left }
			if sr.Top > sr.Bottom { sr.Top, sr.Bottom = sr.Bottom, sr.Top }
			outlineRect(hdc, sr, 0x6EE7B7, 2, PS_DASH)
		}
	}
'''
s=rep(s,old_frame,new_frame,'gyro canvas overlays')
# Rename class to avoid stale Windows class registration during testing.
s=s.replace('FootDrawOperatorWindowV8','FootDrawOperatorWindowV10')
p.write_text(s)
