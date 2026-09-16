from pathlib import Path

root=Path(__file__).resolve().parent
main=root/'app/src/main/java/io/openai/footdraw/MainActivity.java'
draw=root/'app/src/main/java/io/openai/footdraw/DrawingView.java'
manifest=root/'app/src/main/AndroidManifest.xml'

s=draw.read_text()
s=s.replace('if(pointers.get(id)==null&&rejectNewContact(e,i))return true;',
'''if(pointers.get(id)==null&&!inside(e.getX(i),e.getY(i)))return true;''')
s=s.replace('                if(s==null&&rejectNewContact(e,i))continue;\n','')
s=s.replace('                    if(rejectNewContact(e,i)||!inside(x,y))continue;',
'''                    if(!inside(x,y))continue;''')
s=s.replace('float maxDist=Math.max(90f,Math.min(getWidth(),getHeight())*0.18f);',
'''float maxDist=Math.max(140f,Math.min(getWidth(),getHeight())*0.30f);''')
s=s.replace('pendingResumeUntil=SystemClock.uptimeMillis()+240;',
'''pendingResumeUntil=SystemClock.uptimeMillis()+900;''')
s=s.replace('        },250);','        },920);')
s=s.replace('        try{getParent().requestDisallowInterceptTouchEvent(true);}catch(Exception ignored){}\n',
'''        try{getParent().requestDisallowInterceptTouchEvent(true);}catch(Exception ignored){}
        try{if(Build.VERSION.SDK_INT>=21)requestUnbufferedDispatch(e);}catch(Throwable ignored){}
''')

old='''            post(()->{Context c=getContext();if(c instanceof Activity)((Activity)c).setRequestedOrientation(land?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});\n'''
s=s.replace(old,'')
needle='''        postInvalidateOnAnimation();\n    }\n}'''
repl='''        final boolean desiredLand=land;
        post(()->{
            Context c=getContext();
            if(c instanceof MainActivity)((MainActivity)c).setOperatorLandscape(desiredLand);
            else if(c instanceof Activity)((Activity)c).setRequestedOrientation(desiredLand?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
        postInvalidateOnAnimation();
    }

    void setRawMode(boolean enabled){
        if(enabled){
            for(PointerState st:pointers.values())finishStroke(st,1f);
            pointers.clear(); pendingResume=null; pendingResumeSerial++;
        }
        rawMode=enabled;
    }

    void onRawTouch(char kind,int id,float nx,float ny,float pressure){
        if(!rawMode||getWidth()<=0||getHeight()<=0)return;
        float rx=nx,ry=ny;
        int rot=Surface.ROTATION_0;
        try{if(getDisplay()!=null)rot=getDisplay().getRotation();}catch(Throwable ignored){}
        float sx,sy;
        if(rot==Surface.ROTATION_90){sx=(1f-ry)*getWidth();sy=rx*getHeight();}
        else if(rot==Surface.ROTATION_180){sx=(1f-rx)*getWidth();sy=(1f-ry)*getHeight();}
        else if(rot==Surface.ROTATION_270){sx=ry*getWidth();sy=(1f-rx)*getHeight();}
        else {sx=rx*getWidth();sy=ry*getHeight();}
        long now=SystemClock.uptimeMillis();
        if(kind=='U'){
            PointerState st=pointers.get(id);
            if(st!=null){if(inside(sx,sy))processSample(st,sx,sy,pressure,now);finishStroke(st,pressure);pointers.remove(id);}
            return;
        }
        PointerState st=pointers.get(id);
        if(st==null){if(!inside(sx,sy))return;st=createOrResumePointer(id,sx,sy,pressure,now);if(st==null)return;}
        processSample(st,sx,sy,pressure,now);
        if(!inside(sx,sy)&&st.stroke==null)pointers.remove(id);
    }
}'''
assert needle in s
s=s.replace(needle,repl)
# field
s=s.replace('private volatile double vx,vy;','private volatile double vx,vy;\n    private volatile boolean rawMode=false;')
# raw exclusive mode should suppress Android MotionEvent duplicates
s=s.replace('''    @Override public boolean onTouchEvent(MotionEvent e){
        if(getWidth()<=0||getHeight()<=0)return true;''',
'''    @Override public boolean onTouchEvent(MotionEvent e){
        if(rawMode)return true;
        if(getWidth()<=0||getHeight()<=0)return true;''')
draw.write_text(s)

m=main.read_text()
m=m.replace('import android.content.res.Configuration;','import android.content.res.Configuration;\nimport android.content.pm.ActivityInfo;')
m=m.replace('private Store store; private DrawingView drawing; private NetClient net; private AudioClient audio; private RootKiosk kiosk; private long lastVolDown=0; private boolean exiting=false;',
'''private Store store; private DrawingView drawing; private NetClient net; private AudioClient audio; private RootKiosk kiosk; private RawTouchReader rawTouch; private long lastVolDown=0; private boolean exiting=false; private boolean operatorLandscape=false;''')
m=m.replace('''    @Override protected void onCreate(Bundle b){ super.onCreate(b);
        getWindow().addFlags''',
'''    @Override protected void onCreate(Bundle b){ super.onCreate(b);
        operatorLandscape=getSharedPreferences("footdraw",MODE_PRIVATE).getBoolean("operator_landscape",false);
        applyOperatorOrientation();
        getWindow().addFlags''')
m=m.replace('''        AudioBoost.prepare(this); audio=new AudioClient(this,drawing);audio.start(); kiosk=new RootKiosk();kiosk.start(this);
        drawing.post(()->{net=new NetClient(this,store);net.start(Math.max(1,drawing.getWidth()),Math.max(1,drawing.getHeight()),drawing);});''',
'''        AudioBoost.prepare(this); audio=new AudioClient(this,drawing);audio.start(); kiosk=new RootKiosk();kiosk.start(this); rawTouch=new RawTouchReader(this,drawing);rawTouch.start();
        drawing.post(()->{net=new NetClient(this,store);net.start(Math.max(1,drawing.getWidth()),Math.max(1,drawing.getHeight()),drawing);});''')
insert='''    void setOperatorLandscape(boolean land){operatorLandscape=land;getSharedPreferences("footdraw",MODE_PRIVATE).edit().putBoolean("operator_landscape",land).apply();applyOperatorOrientation();}
    private void applyOperatorOrientation(){setRequestedOrientation(operatorLandscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);}
'''
m=m.replace('    private void hideSystemUi(){',insert+'    private void hideSystemUi(){')
m=m.replace('@Override protected void onResume(){super.onResume();getWindow().getDecorView().post(this::hideSystemUi);}',
'''@Override protected void onResume(){super.onResume();applyOperatorOrientation();getWindow().getDecorView().post(this::hideSystemUi);}''')
m=m.replace('@Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);getWindow().getDecorView().post(this::hideSystemUi);}',
'''@Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);applyOperatorOrientation();getWindow().getDecorView().post(this::hideSystemUi);}''')
m=m.replace('if(kiosk!=null)kiosk.stop();if(net!=null)net.close();if(audio!=null)audio.close();',
'''if(rawTouch!=null)rawTouch.close();if(kiosk!=null)kiosk.stop();if(net!=null)net.close();if(audio!=null)audio.close();''')
m=m.replace('@Override protected void onDestroy(){if(net!=null)net.close();if(audio!=null)audio.close();AudioBoost.release(this);super.onDestroy();}',
'''@Override protected void onDestroy(){if(rawTouch!=null)rawTouch.close();if(net!=null)net.close();if(audio!=null)audio.close();AudioBoost.release(this);super.onDestroy();}''')
main.write_text(m)

x=manifest.read_text().replace('android:screenOrientation="unspecified"','android:screenOrientation="portrait"')
manifest.write_text(x)

print('v9.3 patch applied')
