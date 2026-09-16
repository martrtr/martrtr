package io.openai.footdraw;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Converts the phone's relative orientation into a 2D cursor offset.
 * The baseline is captured every time gyro mode is enabled, so the
 * operator's centre point corresponds to the foot's initial direction.
 */
final class GyroController implements SensorEventListener, AutoCloseable {
    private final SensorManager sm;
    private final Sensor sensor;
    private final DrawingView drawing;
    private final float[] base = new float[4];
    private boolean enabled=false, haveBase=false;
    private float smoothX=0f, smoothY=0f;
    private long lastDispatch=0;

    GyroController(Context c, DrawingView d){
        drawing=d;
        sm=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s=null;
        if(sm!=null)s=sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if(s==null&&sm!=null)s=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor=s;
    }

    synchronized void setEnabled(boolean on){
        if(enabled==on)return;
        enabled=on;
        haveBase=false;
        smoothX=smoothY=0f;
        lastDispatch=0;
        if(sm==null||sensor==null)return;
        if(on)sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);
        else sm.unregisterListener(this);
    }

    synchronized void recalibrate(){
        haveBase=false;
        smoothX=smoothY=0f;
        lastDispatch=0;
    }

    @Override public void onSensorChanged(SensorEvent e){
        synchronized(this){if(!enabled)return;}
        float[] q=new float[4];
        try{SensorManager.getQuaternionFromVector(q,e.values);}catch(Throwable ignored){return;}
        normalize(q);
        synchronized(this){
            if(!haveBase){
                System.arraycopy(q,0,base,0,4);
                haveBase=true;
                smoothX=smoothY=0f;
                drawing.post(()->drawing.onGyroOrientation(0f,0f));
                return;
            }
        }

        // qRel = inverse(base) * current. Android's quaternion layout is [w,x,y,z].
        float bw, bx, by, bz;
        synchronized(this){bw=base[0];bx=-base[1];by=-base[2];bz=-base[3];}
        float cw=q[0],cx=q[1],cy=q[2],cz=q[3];
        float rw=bw*cw-bx*cx-by*cy-bz*cz;
        float rx=bw*cx+bx*cw+by*cz-bz*cy;
        float ry=bw*cy-bx*cz+by*cw+bz*cx;
        float rz=bw*cz+bx*cy-by*cx+bz*cw;
        if(rw<0f){rw=-rw;rx=-rx;ry=-ry;rz=-rz;}
        float n=(float)Math.sqrt(rw*rw+rx*rx+ry*ry+rz*rz);
        if(n<1e-6f)return;
        rw/=n;rx/=n;ry/=n;rz/=n;
        rw=Math.max(-1f,Math.min(1f,rw));
        float angle=2f*(float)Math.acos(rw);
        float s=(float)Math.sqrt(Math.max(0f,1f-rw*rw));
        float rvx,rvy,rvz;
        if(s<1e-4f){rvx=2f*rx;rvy=2f*ry;rvz=2f*rz;}
        else {rvx=rx/s*angle;rvy=ry/s*angle;rvz=rz/s*angle;}

        // Phone is mounted in the sole with the toe toward the top of the handset.
        // Z rotation steers left/right; X rotation steers up/down. Roll around the
        // foot's long axis (Y) intentionally does not move the cursor.
        float rawX=rvz;
        float rawY=-rvx;
        final float fx,fy;
        synchronized(this){
            // Enough smoothing to remove foot tremor without making the cursor laggy.
            final float a=0.28f;
            smoothX+=a*(rawX-smoothX);
            smoothY+=a*(rawY-smoothY);
            long now=SystemClock.uptimeMillis();
            if(now-lastDispatch<14)return;
            lastDispatch=now;
            fx=smoothX;fy=smoothY;
        }
        drawing.post(()->drawing.onGyroOrientation(fx,fy));
    }

    private static void normalize(float[] q){
        float n=(float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
        if(n<1e-6f){q[0]=1;q[1]=q[2]=q[3]=0;return;}
        for(int i=0;i<4;i++)q[i]/=n;
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    @Override public void close(){setEnabled(false);}
}
