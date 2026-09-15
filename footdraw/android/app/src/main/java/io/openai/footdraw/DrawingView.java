package io.openai.footdraw;

import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.*;

public final class DrawingView extends View implements NetClient.Listener {
    private final Store store; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final ArrayList<Store.Event> pts=new ArrayList<>();
    private volatile double vx,vy; private String activeStroke; private float lastX,lastY; private long lastMove;
    DrawingView(Context c,Store s){super(c);store=s;setBackgroundColor(Color.BLACK);setKeepScreenOn(true);paint.setColor(Color.rgb(235,238,244));paint.setStrokeWidth(8);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);synchronized(pts){pts.addAll(store.points());}vx=store.vx();vy=store.vy();}
    @Override protected void onDraw(Canvas c){super.onDraw(c); float w=getWidth(); if(w<=0)return; HashMap<String,PointF> prev=new HashMap<>(); synchronized(pts){ for(Store.Event e:pts){ float x=(float)((e.x-vx)*w), y=(float)((e.y-vy)*w); PointF q=prev.get(e.stroke); if(q!=null)c.drawLine(q.x,q.y,x,y,paint); if("U".equals(e.kind))prev.remove(e.stroke); else prev.put(e.stroke,new PointF(x,y)); } } }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){ if(getWidth()<=0)return true; int a=e.getActionMasked(); if(a==MotionEvent.ACTION_DOWN){activeStroke=UUID.randomUUID().toString().replace("-",""); add("D",e);lastX=e.getX();lastY=e.getY();lastMove=android.os.SystemClock.uptimeMillis();return true;} if(a==MotionEvent.ACTION_MOVE&&activeStroke!=null){float dx=e.getX()-lastX,dy=e.getY()-lastY;long now=android.os.SystemClock.uptimeMillis();if(dx*dx+dy*dy>=4||now-lastMove>=12){add("M",e);lastX=e.getX();lastY=e.getY();lastMove=now;}return true;} if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)&&activeStroke!=null){add("U",e);activeStroke=null;return true;} return true; }
    private void add(String kind,MotionEvent m){ double x=vx+m.getX()/getWidth(), y=vy+m.getY()/getWidth(), p=Math.max(.05,m.getPressure()); Store.Event e=store.add(activeStroke,kind,x,y,p); synchronized(pts){pts.add(e);} invalidate(); }
    @Override public void onView(double x,double y){vx=x;vy=y;postInvalidate();}
    @Override public void onClear(long epoch){synchronized(pts){pts.clear();}activeStroke=null;postInvalidate();}
}
