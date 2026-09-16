package io.openai.footdraw;

import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.*;
import java.util.*;

public final class DrawingView extends View implements NetClient.Listener {
    private final Store store;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Store.Event> pts=new ArrayList<>();

    private volatile double vx,vy;

    private static final class PointerState {
        String stroke;
        int color;
        float lastX,lastY;
        long lastMove;
        boolean havePrev,prevInside;
        float prevX,prevY;
    }
    // Important: only pointers that are currently relevant to the drawable area live here.
    // Touches that stay outside the selected area are never registered as drawing pointers.
    private final HashMap<Integer,PointerState> pointers=new HashMap<>();

    private volatile int bgColor=0xFF000000, brushColor=0xFFEBEEF4;
    private volatile boolean landscape=false, clipEnabled=false;
    private volatile float clipL=0f,clipT=0f,clipR=1f,clipB=1f;

    DrawingView(Context c,Store s){
        super(c);store=s;setKeepScreenOn(true);setLayerType(View.LAYER_TYPE_HARDWARE,null);
        paint.setStrokeWidth(8);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        clipPaint.setStyle(Paint.Style.STROKE);clipPaint.setStrokeWidth(3);clipPaint.setColor(Color.rgb(255,190,70));
        synchronized(pts){pts.addAll(store.points());}vx=store.vx();vy=store.vy();
        if(Build.VERSION.SDK_INT>=29)post(this::excludeSystemGestures);
    }

    private void excludeSystemGestures(){
        if(Build.VERSION.SDK_INT<29||getWidth()<=0||getHeight()<=0)return;
        try{setSystemGestureExclusionRects(Collections.singletonList(new Rect(0,0,getWidth(),getHeight())));}catch(Exception ignored){}
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);excludeSystemGestures();}

    private double aspect(){int w=Math.max(1,getWidth()),h=Math.max(1,getHeight());return (double)Math.max(w,h)/(double)Math.min(w,h);}
    private double worldW(){return landscape?aspect():1.0;}
    private double worldH(){return landscape?1.0:aspect();}

    @Override protected void onDraw(Canvas c){
        c.drawColor(bgColor);float sw=getWidth(),sh=getHeight();if(sw<=0||sh<=0)return;
        double ww=worldW(),wh=worldH();
        HashMap<String,PointF> prev=new HashMap<>();
        synchronized(pts){
            for(Store.Event e:pts){
                float x=(float)(((e.x-vx)/ww)*sw),y=(float)(((e.y-vy)/wh)*sh);PointF q=prev.get(e.stroke);
                if(q!=null){paint.setColor(0xFF000000|(e.color&0xFFFFFF));c.drawLine(q.x,q.y,x,y,paint);}
                if("U".equals(e.kind))prev.remove(e.stroke);else prev.put(e.stroke,new PointF(x,y));
            }
        }
        if(clipEnabled){RectF r=new RectF(clipL*sw,clipT*sh,clipR*sw,clipB*sh);c.drawRect(r,clipPaint);}
    }

    private boolean inside(float x,float y){
        if(!clipEnabled)return true;
        float w=getWidth(),h=getHeight();if(w<=0||h<=0)return false;
        float nx=x/w,ny=y/h;return nx>=clipL&&nx<=clipR&&ny>=clipT&&ny<=clipB;
    }
    private float leftPx(){return clipL*getWidth();}
    private float rightPx(){return clipR*getWidth();}
    private float topPx(){return clipT*getHeight();}
    private float bottomPx(){return clipB*getHeight();}

    private PointF[] clipSegment(float x0,float y0,float x1,float y1){
        if(!clipEnabled)return new PointF[]{new PointF(x0,y0),new PointF(x1,y1)};
        float l=leftPx(),r=rightPx(),t=topPx(),b=bottomPx();float dx=x1-x0,dy=y1-y0;float u0=0f,u1=1f;
        float[] p={-dx,dx,-dy,dy};float[] q={x0-l,r-x0,y0-t,b-y0};
        for(int i=0;i<4;i++){
            if(Math.abs(p[i])<1e-6f){if(q[i]<0)return null;continue;}
            float u=q[i]/p[i];
            if(p[i]<0){if(u>u1)return null;if(u>u0)u0=u;}else{if(u<u0)return null;if(u<u1)u1=u;}
        }
        return new PointF[]{new PointF(x0+u0*dx,y0+u0*dy),new PointF(x0+u1*dx,y0+u1*dy)};
    }

    private PointerState createInsidePointer(int id){
        PointerState s=new PointerState();pointers.put(id,s);return s;
    }

    private void beginStroke(PointerState s,float x,float y,float pressure,long when){
        s.color=brushColor&0xFFFFFF;
        s.stroke=String.format(Locale.US,"c%06x_%s",s.color,UUID.randomUUID().toString().replace("-",""));
        addPoint(s,"D",x,y,pressure);s.lastX=x;s.lastY=y;s.lastMove=when;
    }

    private void finishStroke(PointerState s,float pressure){
        if(s!=null&&s.stroke!=null){addPoint(s,"U",s.lastX,s.lastY,pressure);s.stroke=null;}
    }

    private void processSample(PointerState s,float x,float y,float pressure,long when){
        boolean in=inside(x,y);long now=Math.max(when,SystemClock.uptimeMillis());
        if(in){
            if(s.stroke==null){
                float sx=x,sy=y;
                if(s.havePrev&&!s.prevInside&&clipEnabled){PointF[] seg=clipSegment(s.prevX,s.prevY,x,y);if(seg!=null){sx=seg[0].x;sy=seg[0].y;}}
                beginStroke(s,sx,sy,pressure,now);
                float dx=x-sx,dy=y-sy;if(dx*dx+dy*dy>=1f){addPoint(s,"M",x,y,pressure);s.lastX=x;s.lastY=y;s.lastMove=now;}
            }else{
                float dx=x-s.lastX,dy=y-s.lastY;if(dx*dx+dy*dy>=2.25f||now-s.lastMove>=8){addPoint(s,"M",x,y,pressure);s.lastX=x;s.lastY=y;s.lastMove=now;}
            }
        }else if(s.stroke!=null){
            float ex=s.lastX,ey=s.lastY;PointF[] seg=clipSegment(s.lastX,s.lastY,x,y);if(seg!=null){ex=seg[1].x;ey=seg[1].y;}
            float dx=ex-s.lastX,dy=ey-s.lastY;if(dx*dx+dy*dy>0.25f)addPoint(s,"M",ex,ey,pressure);s.lastX=ex;s.lastY=ey;finishStroke(s,pressure);
        }
        s.prevX=x;s.prevY=y;s.prevInside=in;s.havePrev=true;
    }

    private PointerState processPointerSample(int id,float x,float y,float pressure,long when){
        PointerState s=pointers.get(id);
        if(s==null){
            // Outside touches are intentionally invisible to the drawing subsystem.
            // A pointer only becomes drawable at the first sample that is actually inside.
            if(!inside(x,y))return null;
            s=createInsidePointer(id);
        }
        processSample(s,x,y,pressure,when);
        // The moment it leaves the selected area and the stroke is finished, forget it again.
        // If the same finger later re-enters, it will start a fresh stroke from that entry point.
        if(!inside(x,y)&&s.stroke==null){pointers.remove(id);return null;}
        return s;
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(getWidth()<=0||getHeight()<=0)return true;
        try{getParent().requestDisallowInterceptTouchEvent(true);}catch(Exception ignored){}
        int action=e.getActionMasked();

        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int i=e.getActionIndex();int id=e.getPointerId(i);
            processPointerSample(id,e.getX(i),e.getY(i),e.getPressure(i),e.getEventTime());
            return true;
        }

        if(action==MotionEvent.ACTION_MOVE){
            int hc=e.getHistorySize();
            for(int i=0;i<e.getPointerCount();i++){
                int id=e.getPointerId(i);
                PointerState s=pointers.get(id);
                for(int h=0;h<hc;h++){
                    float x=e.getHistoricalX(i,h),y=e.getHistoricalY(i,h),p=e.getHistoricalPressure(i,h);long when=e.getHistoricalEventTime(h);
                    if(s==null){if(!inside(x,y))continue;s=createInsidePointer(id);}
                    processSample(s,x,y,p,when);
                    if(!inside(x,y)&&s.stroke==null){pointers.remove(id);s=null;}
                }
                float x=e.getX(i),y=e.getY(i),p=e.getPressure(i);long when=e.getEventTime();
                if(s==null){if(!inside(x,y))continue;s=createInsidePointer(id);}
                processSample(s,x,y,p,when);
                if(!inside(x,y)&&s.stroke==null)pointers.remove(id);
            }
            return true;
        }

        if(action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_UP){
            int i=e.getActionIndex();int id=e.getPointerId(i);PointerState s=pointers.get(id);
            if(s!=null){processSample(s,e.getX(i),e.getY(i),e.getPressure(i),e.getEventTime());finishStroke(s,e.getPressure(i));pointers.remove(id);}
            if(action==MotionEvent.ACTION_UP)pointers.clear();return true;
        }

        if(action==MotionEvent.ACTION_CANCEL){
            for(PointerState s:pointers.values())finishStroke(s,1f);
            pointers.clear();return true;
        }
        return true;
    }

    private void addPoint(PointerState s,String kind,float px,float py,float pressure){
        if(s==null||s.stroke==null)return;
        double ww=worldW(),wh=worldH();double x=vx+(px/Math.max(1,getWidth()))*ww,y=vy+(py/Math.max(1,getHeight()))*wh,p=Math.max(.05,pressure);
        Store.Event ev=store.add(s.stroke,kind,x,y,p,s.color);synchronized(pts){pts.add(ev);}postInvalidateOnAnimation();
    }

    @Override public void onView(double x,double y){vx=x;vy=y;postInvalidateOnAnimation();}
    @Override public void onClear(long epoch){synchronized(pts){pts.clear();}for(PointerState s:pointers.values())s.stroke=null;pointers.clear();postInvalidateOnAnimation();}

    @Override public void onConfig(int bg,int brush,boolean land,boolean clip,float l,float t,float r,float b){
        bgColor=0xFF000000|(bg&0xFFFFFF);brushColor=0xFF000000|(brush&0xFFFFFF);clipEnabled=clip;clipL=Math.max(0f,Math.min(1f,l));clipT=Math.max(0f,Math.min(1f,t));clipR=Math.max(clipL,Math.min(1f,r));clipB=Math.max(clipT,Math.min(1f,b));
        if(landscape!=land){
            double oldW=worldW(),oldH=worldH(),cx=vx+oldW/2.0,cy=vy+oldH/2.0;
            landscape=land;
            double newW=worldW(),newH=worldH();
            vx=cx-newW/2.0;vy=cy-newH/2.0;
            post(()->{Context c=getContext();if(c instanceof Activity)((Activity)c).setRequestedOrientation(land?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        }
        postInvalidateOnAnimation();
    }
}
