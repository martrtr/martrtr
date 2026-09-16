from pathlib import Path

p=Path('main_windows.go')
s=p.read_text()

def must_replace(old,new,label):
    global s
    if old not in s:
        raise SystemExit('missing v11 patch anchor: '+label)
    s=s.replace(old,new,1)

# New mode-parameter button.
must_replace('\tbtnMode\n\tbtnOrient\n','\tbtnMode\n\tbtnRecenter\n\tbtnOrient\n','enum recenter')

# Dynamic grouped toolbar layout:
# fullscreen | eraser/bg/brush | mode | mode parameters | microphone+selector
start=s.index('func layoutButtons(')
end=s.index('func palettePanelRect',start)
layout=r'''func layoutButtons(width int32) {
	const big int32 = 46
	const small int32 = 26
	const gap int32 = 8
	const groupGap int32 = 18
	stateMu.RLock()
	gm := gyroMode
	stateMu.RUnlock()
	type tbItem struct { kind int; w int32; after int32 }
	items := []tbItem{
		{btnFullscreen,big,groupGap},
		{btnClear,big,gap},{btnBGTarget,big,gap},{btnBrushTarget,big,groupGap},
		{btnMode,big,groupGap},
	}
	if gm {
		items=append(items,tbItem{btnRecenter,big,groupGap})
	} else {
		items=append(items,tbItem{btnOrient,big,gap},tbItem{btnArea,big,groupGap})
	}
	items=append(items,tbItem{btnMicToggle,big,0},tbItem{btnMicMenu,small,0})
	total:=int32(0)
	for i,it:=range items { total+=it.w; if i<len(items)-1 { total+=it.after } }
	x:=(width-total)/2
	if x<8 { x=8 }
	y:=int32(12)
	buttons=buttons[:0]
	for i,it:=range items {
		buttons=append(buttons,uiButton{RECT{x,y,x+it.w,y+big},it.kind,0})
		x+=it.w
		if i<len(items)-1 { x+=it.after }
	}
}

'''
s=s[:start]+layout+s[end:]

# Mode switching + recenter + mode-only orientation.
old=r'''	case btnMode:
		stateMu.Lock()
		gyroMode = !gyroMode
		gm := gyroMode
		gyroDX, gyroDY, gyroDown = 0, 0, false
		stateMu.Unlock()
		sendMode(gm)
		if gm { sendGyroView() }
		invalidate()
	case btnOrient:
		stateMu.Lock()
'''
new=r'''	case btnMode:
		stateMu.Lock()
		gyroMode = !gyroMode
		gm := gyroMode
		gyroDX, gyroDY, gyroDown = 0, 0, false
		stateMu.Unlock()
		sendMode(gm)
		if gm { sendGyroView() }
		var rc RECT
		pGetClient.Call(hwndMain, uintptr(unsafe.Pointer(&rc)))
		layoutButtons(rc.Right-rc.Left)
		invalidate()
	case btnRecenter:
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if !gm { return }
		stateMu.Lock(); gyroDX, gyroDY = 0, 0; stateMu.Unlock()
		// Server v4 broadcasts MODE 1 even when already active. Android v11 treats
		// repeated MODE 1 as an explicit gyro re-calibration request.
		sendMode(true)
		invalidate()
	case btnOrient:
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if gm { return }
		stateMu.Lock()
'''
must_replace(old,new,'mode/recenter handler')

# Gyro mode and orientation state are communicated by icon shape, not button inversion.
s=s.replace('\tif b.kind == btnOrient && land { base = 0x263B55 }\n','',1)
old=r'''	if b.kind == btnMode {
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if gm { base = 0xF2F5F8; fg = 0x10151D }
	}
'''
if old in s: s=s.replace(old,'',1)

# Fullscreen toggle: inactive corners open inward, active icon is visibly reversed/outward.
start=s.index('func drawFullscreenIcon(')
end=s.index('func drawButtonIcon',start)
fullscreen=r'''func drawFullscreenIcon(hdc uintptr, r RECT, fg uint32, active bool) {
	pad:=int32(11)
	x1,y1,x2,y2:=r.Left+pad,r.Top+pad,r.Right-pad,r.Bottom-pad
	pen,_,_:=pCreatePen.Call(PS_SOLID,2,uintptr(rgbHexToColorRef(fg)))
	op,_,_:=pSelect.Call(hdc,pen)
	if !active {
		// Normal fullscreen symbol: four outer corners opening toward the centre.
		pMove.Call(hdc,uintptr(x1+7),uintptr(y1),0); pLine.Call(hdc,uintptr(x1),uintptr(y1)); pLine.Call(hdc,uintptr(x1),uintptr(y1+7))
		pMove.Call(hdc,uintptr(x2-7),uintptr(y1),0); pLine.Call(hdc,uintptr(x2),uintptr(y1)); pLine.Call(hdc,uintptr(x2),uintptr(y1+7))
		pMove.Call(hdc,uintptr(x1+7),uintptr(y2),0); pLine.Call(hdc,uintptr(x1),uintptr(y2)); pLine.Call(hdc,uintptr(x1),uintptr(y2-7))
		pMove.Call(hdc,uintptr(x2-7),uintptr(y2),0); pLine.Call(hdc,uintptr(x2),uintptr(y2)); pLine.Call(hdc,uintptr(x2),uintptr(y2-7))
	} else {
		// Active fullscreen: reverse every corner so it points outward.
		ix1,iy1,ix2,iy2:=x1+3,y1+3,x2-3,y2-3
		pMove.Call(hdc,uintptr(ix1),uintptr(iy1+7),0); pLine.Call(hdc,uintptr(ix1+7),uintptr(iy1+7)); pLine.Call(hdc,uintptr(ix1+7),uintptr(iy1))
		pMove.Call(hdc,uintptr(ix2),uintptr(iy1+7),0); pLine.Call(hdc,uintptr(ix2-7),uintptr(iy1+7)); pLine.Call(hdc,uintptr(ix2-7),uintptr(iy1))
		pMove.Call(hdc,uintptr(ix1),uintptr(iy2-7),0); pLine.Call(hdc,uintptr(ix1+7),uintptr(iy2-7)); pLine.Call(hdc,uintptr(ix1+7),uintptr(iy2))
		pMove.Call(hdc,uintptr(ix2),uintptr(iy2-7),0); pLine.Call(hdc,uintptr(ix2-7),uintptr(iy2-7)); pLine.Call(hdc,uintptr(ix2-7),uintptr(iy2))
	}
	pSelect.Call(hdc,op); pDelete.Call(pen)
}

'''
s=s[:start]+fullscreen+s[end:]

# Replace the mode/orientation icon block and add recenter icon.
start=s.index('\tcase btnMode:',s.index('func drawButtonIcon'))
end=s.index('\tcase btnFullscreen:',start)
icons=r'''	case btnMode:
		stateMu.RLock(); gm := gyroMode; stateMu.RUnlock()
		if gm {
			// Gyro mode: compact crosshair/aim icon. No inverted button background.
			pen,_,_:=pCreatePen.Call(PS_SOLID,2,uintptr(rgbHexToColorRef(fg))); op,_,_:=pSelect.Call(hdc,pen)
			pMove.Call(hdc,uintptr(cx-10),uintptr(cy),0); pLine.Call(hdc,uintptr(cx-3),uintptr(cy))
			pMove.Call(hdc,uintptr(cx+3),uintptr(cy),0); pLine.Call(hdc,uintptr(cx+10),uintptr(cy))
			pMove.Call(hdc,uintptr(cx),uintptr(cy-10),0); pLine.Call(hdc,uintptr(cx),uintptr(cy-3))
			pMove.Call(hdc,uintptr(cx),uintptr(cy+3),0); pLine.Call(hdc,uintptr(cx),uintptr(cy+10))
			pSelect.Call(hdc,op); pDelete.Call(pen); drawCircle(hdc,RECT{cx-2,cy-2,cx+2,cy+2},0x78AFFF)
		} else {
			// Direct-drawing mode: stylus.
			pen,_,_:=pCreatePen.Call(PS_SOLID,5,uintptr(rgbHexToColorRef(fg))); op,_,_:=pSelect.Call(hdc,pen)
			pMove.Call(hdc,uintptr(cx-9),uintptr(cy+9),0); pLine.Call(hdc,uintptr(cx+8),uintptr(cy-8))
			pSelect.Call(hdc,op); pDelete.Call(pen); drawCircle(hdc,RECT{cx+5,cy-11,cx+12,cy-4},0x78AFFF)
		}
	case btnRecenter:
		// Re-centre target: crosshair plus central dot.
		pen,_,_:=pCreatePen.Call(PS_SOLID,2,uintptr(rgbHexToColorRef(fg))); op,_,_:=pSelect.Call(hdc,pen)
		pMove.Call(hdc,uintptr(cx-11),uintptr(cy),0); pLine.Call(hdc,uintptr(cx-4),uintptr(cy))
		pMove.Call(hdc,uintptr(cx+4),uintptr(cy),0); pLine.Call(hdc,uintptr(cx+11),uintptr(cy))
		pMove.Call(hdc,uintptr(cx),uintptr(cy-11),0); pLine.Call(hdc,uintptr(cx),uintptr(cy-4))
		pMove.Call(hdc,uintptr(cx),uintptr(cy+4),0); pLine.Call(hdc,uintptr(cx),uintptr(cy+11))
		pSelect.Call(hdc,op); pDelete.Call(pen); drawCircle(hdc,RECT{cx-3,cy-3,cx+3,cy+3},fg)
	case btnOrient:
		// Icon itself reports the selected phone orientation.
		if land { outlineRect(hdc,RECT{cx-13,cy-8,cx+13,cy+8},fg,2,PS_SOLID) } else { outlineRect(hdc,RECT{cx-8,cy-13,cx+8,cy+13},fg,2,PS_SOLID) }
'''
s=s[:start]+icons+s[end:]

# Live gyro cursor is a small crosshair, not a circle.
old=r'''		px := ccx + int32(gdx*800.0)
		py := ccy + int32(gdy*800.0)
		if gdown {
			drawCircle(hdc, RECT{px-8, py-8, px+8, py+8}, brush)
			outlineCircle(hdc, RECT{px-11, py-11, px+11, py+11}, 0xFFFFFF, 2)
		} else {
			outlineCircle(hdc, RECT{px-8, py-8, px+8, py+8}, brush, 3)
		}
'''
new=r'''		px := ccx + int32(gdx*800.0)
		py := ccy + int32(gdy*800.0)
		cursorColor:=brush
		if gdown { cursorColor=0xFFFFFF }
		pen,_,_:=pCreatePen.Call(PS_SOLID,3,uintptr(rgbHexToColorRef(cursorColor))); op,_,_:=pSelect.Call(hdc,pen)
		pMove.Call(hdc,uintptr(px-9),uintptr(py),0); pLine.Call(hdc,uintptr(px-2),uintptr(py))
		pMove.Call(hdc,uintptr(px+2),uintptr(py),0); pLine.Call(hdc,uintptr(px+9),uintptr(py))
		pMove.Call(hdc,uintptr(px),uintptr(py-9),0); pLine.Call(hdc,uintptr(px),uintptr(py-2))
		pMove.Call(hdc,uintptr(px),uintptr(py+2),0); pLine.Call(hdc,uintptr(px),uintptr(py+9))
		pSelect.Call(hdc,op); pDelete.Call(pen)
		if gdown { drawCircle(hdc,RECT{px-2,py-2,px+2,py+2},brush) }
'''
must_replace(old,new,'gyro crosshair cursor')

# Recompute toolbar every paint so server-side mode restores also swap the parameter buttons.
must_replace('func renderUI(hdc uintptr, rc RECT) {\n','func renderUI(hdc uintptr, rc RECT) {\n\tlayoutButtons(rc.Right-rc.Left)\n','dynamic render layout')

s=s.replace('FootDrawOperatorWindowV10','FootDrawOperatorWindowV11')
p.write_text(s)
