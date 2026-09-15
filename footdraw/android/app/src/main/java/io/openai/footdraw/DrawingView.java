package io.openai.footdraw;

import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import java.util.*;

public final class DrawingView extends View implements NetClient.Listener {
    private final Store store;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Store.Event> pts=new ArrayList<>();
    private volatile double vx,vy;
    private double strokeVX,strokeVY,pendingVX,pendingVY;
    private boolean hasPendingView=false;
    private String activeStroke;
    private boolean blockedUntilUp=false;
    private float lastX,lastY;
    private long lastMove;
    private volatile int bgColor=0xFF000000, brushColor=0xFFEBEEF4;
    private volatile boolean landscape=false, clipEnabled=false;
    private volatile float clipL=0f,clipT=0f,clipR=1f,clipB=1f;

    DrawingView(Context c,Store s){
        super(c);store=s;setKeepScreenOn(true);setLayerType(View.LAYER_TYPE_HARDWARE,null);
        paint.setStrokeWidth(8);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        clipPaint.setStyle(Paint.Style.STROKE);clipPaint.setStrokeWidth(3);clipPaint.setColor(Color.rgb(255,190,70));
        synchronized(pts){pts.addAll(store.points());}vx=store.vx();vy=store.vy();
    }

    @Override protected void onDraw(Canvas c){
        c.drawColor(bgColor); float w=getWidth(); if(w<=0)return;
        HashMap<String,PointF> prev=new HashMap<>();
        synchronized(pts){
            for(Store.Event e:pts){
                float x=(float)((e.x-vx)*w), y=(float)((e.y-vy)*w); PointF q=prev.get(e.stroke);
                if(q!=null){paint.setColor(0xFF000000|(e.color&0xFFFFFF));c.drawLine(q.x,q.y,x,y,paint);}
                if("U".equals(e.kind))prev.remove(e.stroke); else prev.put(e.stroke,new PointF(x,y));
            }
        }
        if(clipEnabled){float h=getHeight();RectF r=new RectF(clipL*w,clipT*h,clipR*w,clipB*h);c.drawRect(r,clipPaint);}
    }

    private boolean inside(float x,float y){ if(!clipEnabled)return true; float w=getWidth(),h=getHeight(); if(w<=0||h<=0)return false; float nx=x/w,ny=y/h;return nx>=clipL&&nx<=clipR&&ny>=clipT&&ny<=clipB; }
    private float clampX(float x){ if(!clipEnabled)return x;return Math.max(clipL*getWidth(),Math.min(clipR*getWidth(),x)); }
    private float clampY(float y){ if(!clipEnabled)return y;return Math.max(clipT*getHeight(),Math.min(clipB*getHeight(),y)); }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(getWidth()<=0)return true; int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_DOWN){
            blockedUntilUp=!inside(e.getX(),e.getY()); if(blockedUntilUp)return true;
            activeStroke=UUID.randomUUID().toString().replace("-",""); strokeVX=vx;strokeVY=vy;
            addPoint("D",e.getX(),e.getY(),e.getPressure());lastX=e.getX();lastY=e.getY();lastMove=SystemClock.uptimeMillis();return true;
        }
        if(a==MotionEvent.ACTION_MOVE){
            if(blockedUntilUp||activeStroke==null)return true;
            int hc=e.getHistorySize();
            for(int i=0;i<hc;i++)processMove(e.getHistoricalX(i),e.getHistoricalY(i),e.getHistoricalPressure(i),e.getHistoricalEventTime(i));
            processMove(e.getX(),e.getY(),e.getPressure(),e.getEventTime()); return true;
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){
            if(activeStroke!=null){float x=clampX(e.getX()),y=clampY(e.getY());addPoint("U",x,y,e.getPressure());activeStroke=null;}
            blockedUntilUp=false;applyPendingView();return true;
        }
        return true;
    }

    private void processMove(float x,float y,float pressure,long when){
        if(activeStroke==null)return;
        if(!inside(x,y)){
            float cx=clampX(x),cy=clampY(y);addPoint("M",cx,cy,pressure);addPoint("U",cx,cy,pressure);activeStroke=null;blockedUntilUp=true;applyPendingView();return;
        }
        float dx=x-lastX,dy=y-lastY;long now=Math.max(when,SystemClock.uptimeMillis());
        if(dx*dx+dy*dy>=1.0f||now-lastMove>=6){addPoint("M",x,y,pressure);lastX=x;lastY=y;lastMove=now;}
    }

    private void addPoint(String kind,float px,float py,float pressure){
        double x=strokeVX+px/getWidth(), y=strokeVY+py/getWidth(), p=Math.max(.05,pressure);
        Store.Event ev=store.add(activeStroke,kind,x,y,p,brushColor&0xFFFFFF); synchronized(pts){pts.add(ev);} postInvalidateOnAnimation();
    }

    private void applyPendingView(){if(hasPendingView&&activeStroke==null){vx=pendingVX;vy=pendingVY;hasPendingView=false;postInvalidateOnAnimation();}}
    @Override public void onView(double x,double y){if(activeStroke!=null){pendingVX=x;pendingVY=y;hasPendingView=true;}else{vx=x;vy=y;postInvalidateOnAnimation();}}
    @Override public void onClear(long epoch){synchronized(pts){pts.clear();}activeStroke=null;blockedUntilUp=false;hasPendingView=false;postInvalidateOnAnimation();}

    @Override public void onConfig(int bg,int brush,boolean land,boolean clip,float l,float t,float r,float b){
        bgColor=0xFF000000|(bg&0xFFFFFF);brushColor=0xFF000000|(brush&0xFFFFFF);clipEnabled=clip;clipL=l;clipT=t;clipR=r;clipB=b;
        if(landscape!=land){landscape=land;post(()->{Context c=getContext();if(c instanceof Activity){((Activity)c).setRequestedOrientation(land?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);}});}
        postInvalidateOnAnimation();
    }
}
