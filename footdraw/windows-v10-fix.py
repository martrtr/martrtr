from pathlib import Path
p=Path('main_windows.go')
s=p.read_text()
wrong='''\tland := phoneLandscape\n\tgm, gdx, gdy, gdown := gyroMode, gyroDX, gyroDY, gyroDown\n\tstateMu.RUnlock()\n\tww, wh := worldDims(land, pw, ph)'''
right='''\tland := phoneLandscape\n\tstateMu.RUnlock()\n\tww, wh := worldDims(land, pw, ph)'''
if wrong not in s:
    raise SystemExit('missing viewport cleanup anchor')
s=s.replace(wrong,right,1)
anchor='''\tbg := bgColor\n\tce := clipEnabled\n\tcl, ct, cr, cb := clipL, clipT, clipR, clipB\n\tland := phoneLandscape\n\tstateMu.RUnlock()'''
repl='''\tbg := bgColor\n\tbrush := brushColor\n\tce := clipEnabled\n\tcl, ct, cr, cb := clipL, clipT, clipR, clipB\n\tland := phoneLandscape\n\tgm, gdx, gdy, gdown := gyroMode, gyroDX, gyroDY, gyroDown\n\tstateMu.RUnlock()'''
if anchor not in s:
    raise SystemExit('missing renderCanvas state anchor')
s=s.replace(anchor,repl,1)
p.write_text(s)
