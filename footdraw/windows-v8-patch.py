from pathlib import Path
import re
p=Path('main_windows.go')
s=p.read_text()
s=s.replace('if b.kind == btnArea && ce {','if b.kind == btnArea && (ce || selecting) {')
# Remove the old green underline used while selection mode is armed.
s=re.sub(r'\n\tif selecting && !ce \{.*?\n\t\}\n\tif paletteOpen \{','\n\tif paletteOpen {',s,flags=re.S)
# Replace the old angular eraser glyph with a cleaner two-tone eraser.
start=s.index('\tcase btnClear:')
end=s.index('\tcase btnBGTarget:',start)
new='''\tcase btnClear:\n\t\t// Clean two-tone eraser, tilted like a real drawing eraser.\n\t\tbody := [4]POINT{{cx - 12, cy + 6}, {cx + 2, cy - 10}, {cx + 12, cy - 1}, {cx - 2, cy + 14}}\n\t\tbr := solidBrush(0xF5F7FB)\n\t\toldBr, _, _ := pSelect.Call(hdc, br)\n\t\tpen, _, _ := pCreatePen.Call(PS_SOLID, 1, uintptr(rgbHexToColorRef(0xDDE5F0)))\n\t\toldPen, _, _ := pSelect.Call(hdc, pen)\n\t\tpPolygon.Call(hdc, uintptr(unsafe.Pointer(&body[0])), 4)\n\t\tpSelect.Call(hdc, oldPen); pSelect.Call(hdc, oldBr); pDelete.Call(pen); pDelete.Call(br)\n\t\ttip := [4]POINT{{cx - 12, cy + 6}, {cx - 6, cy - 1}, {cx + 4, cy + 8}, {cx - 2, cy + 14}}\n\t\tbr2 := solidBrush(0xFF6F91)\n\t\tob2, _, _ := pSelect.Call(hdc, br2)\n\t\tpen2, _, _ := pCreatePen.Call(PS_SOLID, 1, uintptr(rgbHexToColorRef(0xFF6F91)))\n\t\top2, _, _ := pSelect.Call(hdc, pen2)\n\t\tpPolygon.Call(hdc, uintptr(unsafe.Pointer(&tip[0])), 4)\n\t\tpSelect.Call(hdc, op2); pSelect.Call(hdc, ob2); pDelete.Call(pen2); pDelete.Call(br2)\n'''
s=s[:start]+new+s[end:]
s=s.replace('FootDrawOperatorWindowV6','FootDrawOperatorWindowV8')
p.write_text(s)
