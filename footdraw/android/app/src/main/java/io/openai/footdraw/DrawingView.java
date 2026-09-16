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
    private String activeStroke;
    private boolean fingerDown=false;
    private float lastX,lastY;
    private long lastMove;
    private boolean havePrevSample=false,prevInside=false;
    private float prevSampleX,prevSampleY;

    private volatile int bgColor=0xFF000000, brushColor=0xFFEBEEF4;
    private volatile boolean landscape=false, clipEnabled=false;
    private volatile float clipL=0f,clipT=0f,clipR=1f,clipB=1f;

    DrawingView(Context c,Store s){
        super(c);store=s;setKeepScreenOn(true);setLayerType(View.LAYER_TYPE_HARDWARE,null);
        paint.setStrokeWidth(8);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        clipPaint.setStyle(Paint.Style.STROKE);clipPaint.setStrokeWidth(3);clipPaint.setColor(Color.rgb(255,190,70));
        synchronized(pts){pts.addAll(store.points());}vx=store.vx();vy=store.vy();
    }

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

    private boolean inside(float x,float y){if(!clipEnabled)return true;float w=getWidth(),h=getHeight();if(w<=0||h<=0)return false;float nx=x/w,ny=y/h;return nx>=clipL&&nx<=clipR&&ny>=clipT&&ny<=clipB;}
    private float leftPx(){return clipL*getWidth();}private float rightPx(){return clipR*getWidth();}private float topPx(){return clipT*getHeight();}private float bottomPx(){return clipB*getHeight();}

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

    @Override public boolean onTouchEvent(MotionEvent e){
        if(getWidth()<=0||getHeight()<=0)return true;int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_DOWN){
            fingerDown=true;activeStroke=null;havePrevSample=false;processSample(e.getX(),e.getY(),e.getPressure(),e.getEventTime());return true;
        }
        if(a==MotionEvent.ACTION_MOVE&&fingerDown){
            int hc=e.getHistorySize();for(int i=0;i<hc;i++)processSample(e.getHistoricalX(i),e.getHistoricalY(i),e.getHistoricalPressure(i),e.getHistoricalEventTime(i));
            processSample(e.getX(),e.getY(),e.getPressure(),e.getEventTime());return true;
        }
        if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)&&fingerDown){
            if(a==MotionEvent.ACTION_UP)processSample(e.getX(),e.getY(),e.getPressure(),e.getEventTime());
            if(activeStroke!=null){addPoint("U",lastX,lastY,e.getPressure());activeStroke=null;}
            fingerDown=false;havePrevSample=false;return true;
        }
        return true;
    }

    private void beginStroke(float x,float y,float pressure,long when){
        activeStroke=String.format(Locale.US,"c%06x_%s",brushColor&0xFFFFFF,UUID.randomUUID().toString().replace("-",""));
        addPoint("D",x,y,pressure);lastX=x;lastY=y;lastMove=when;
    }

    private void processSample(float x,float y,float pressure,long when){
        boolean in=inside(x,y);long now=Math.max(when,SystemClock.uptimeMillis());
        if(in){
            if(activeStroke==null){
                float sx=x,sy=y;
                if(havePrevSample&&!prevInside&&clipEnabled){PointF[] seg=clipSegment(prevSampleX,prevSampleY,x,y);if(seg!=null){sx=seg[0].x;sy=seg[0].y;}}
                beginStroke(sx,sy,pressure,now);
                float dx=x-sx,dy=y-sy;if(dx*dx+dy*dy>=1f){addPoint("M",x,y,pressure);lastX=x;lastY=y;lastMove=now;}
            }else{
                float dx=x-lastX,dy=y-lastY;if(dx*dx+dy*dy>=2.25f||now-lastMove>=8){addPoint("M",x,y,pressure);lastX=x;lastY=y;lastMove=now;}
            }
        }else if(activeStroke!=null){
            float ex=lastX,ey=lastY;PointF[] seg=clipSegment(lastX,lastY,x,y);if(seg!=null){ex=seg[1].x;ey=seg[1].y;}
            float dx=ex-lastX,dy=ey-lastY;if(dx*dx+dy*dy>0.25f)addPoint("M",ex,ey,pressure);addPoint("U",ex,ey,pressure);activeStroke=null;
        }
        prevSampleX=x;prevSampleY=y;prevInside=in;havePrevSample=true;
    }

    private void addPoint(String kind,float px,float py,float pressure){
        double ww=worldW(),wh=worldH();double x=vx+(px/Math.max(1,getWidth()))*ww,y=vy+(py/Math.max(1,getHeight()))*wh,p=Math.max(.05,pressure);
        Store.Event ev=store.add(activeStroke,kind,x,y,p,brushColor&0xFFFFFF);synchronized(pts){pts.add(ev);}postInvalidateOnAnimation();
    }

    @Override public void onView(double x,double y){vx=x;vy=y;postInvalidateOnAnimation();}
    @Override public void onClear(long epoch){synchronized(pts){pts.clear();}activeStroke=null;fingerDown=false;havePrevSample=false;postInvalidateOnAnimation();}

    @Override public void onConfig(int bg,int brush,boolean land,boolean clip,float l,float t,float r,float b){
        bgColor=0xFF000000|(bg&0xFFFFFF);brushColor=0xFF000000|(brush&0xFFFFFF);clipEnabled=clip;clipL=Math.max(0f,Math.min(1f,l));clipT=Math.max(0f,Math.min(1f,t));clipR=Math.max(clipL,Math.min(1f,r));clipB=Math.max(clipT,Math.min(1f,b));
        if(landscape!=land){double oldW=worldW(),oldH=worldH(),cx=vx+oldW/2,cy=vy+oldH/2;landscape=land;double newW=worldW(),newH=worldH();vx=cx-newW/2;vy=cy-newH/2;post(()->{Context c=getContext();if(c instanceof Activity)((Activity)c).setRequestedOrientation(land?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});}
        postInvalidateOnAnimation();
    }
}
