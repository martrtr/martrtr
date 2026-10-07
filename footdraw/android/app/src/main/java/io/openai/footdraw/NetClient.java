package io.openai.footdraw;

import android.content.*;
import android.net.Network;
import java.io.*;
import java.net.*;
import java.util.*;

public final class NetClient implements AutoCloseable {
    static final String HOST = "31.77.251.51";
    static final int PORT = 4950;
    static final int UDP_PORT = PORT;
    static final String TOKEN = "f2f3a025173941f9cb1d297eba2c0469";

    interface Listener {
        void onView(double x,double y);
        void onClear(long epoch);
        void onConfig(int bg,int brush,boolean landscape,boolean clip,float l,float t,float r,float b);
        void onMode(boolean gyro);
        void onGyroView(double cx,double cy,double worldPerPixel);
    }

    private final Store store;
    private final MainActivity activity;
    private final NetworkRouter router;
    private final String deviceId;
    private volatile boolean running=true;
    private Thread thread;
    private Listener listener;
    private int width,height;
    private volatile Socket activeSocket;
    private volatile String pendingGyroCursor;

    NetClient(Context c, Store s, NetworkRouter router){
        store=s;
        this.router=router;
        activity=(c instanceof MainActivity)?(MainActivity)c:null;
        SharedPreferences p=c.getSharedPreferences("footdraw",Context.MODE_PRIVATE);
        String id=p.getString("device",null);
        if(id==null||id.isEmpty()){
            String androidId=null;
            try{androidId=android.provider.Settings.Secure.getString(
                    c.getContentResolver(),android.provider.Settings.Secure.ANDROID_ID);}catch(Exception ignored){}
            id=(androidId!=null&&!androidId.isEmpty())?"android-"+androidId:UUID.randomUUID().toString();
            try{p.edit().putString("device",id).apply();}catch(Exception ignored){}
        }
        deviceId=id;
    }

    String deviceId(){ return deviceId; }

    void start(int w,int h,Listener l){
        width=w;height=h;listener=l;
        thread=new Thread(this::loop,"FootDraw-TCP");
        store.setWake(()->{Thread t=thread;if(t!=null)t.interrupt();});
        thread.start();
    }

    void sendGyroCursor(float dx,float dy,boolean down){
        pendingGyroCursor=String.format(Locale.US,"GC %.8f %.8f %d",dx,dy,down?1:0);
        Thread t=thread;if(t!=null)t.interrupt();
    }

    private void loop(){
        int wait=120;
        while(running){
            try{
                Network cell=router.awaitCellular(10000);
                if(cell==null)throw new IOException("cellular unavailable");
                try(Socket sock=cell.getSocketFactory().createSocket()){
                    activeSocket=sock;
                    sock.connect(new InetSocketAddress(HOST,PORT),3500);
                    sock.setTcpNoDelay(true);
                    sock.setKeepAlive(true);
                    sock.setSoTimeout(0);
                    wait=120;
                    runSocket(sock);
                }
            }catch(Exception ignored){
                sleep(wait);
                wait=Math.min(3000,wait*2);
            }finally{
                activeSocket=null;
            }
        }
    }

    private void runSocket(Socket s)throws Exception{
        BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
        write(out,"HELLO PHONE "+TOKEN+" "+deviceId+" "+width+" "+height);
        long lastSent=0,lastPing=System.currentTimeMillis();

        while(running&&!s.isClosed()){
            List<Store.Event> batch=store.pendingAfter(lastSent,256);
            for(Store.Event e:batch){
                write(out,String.format(Locale.US,
                        "P %d %d %s %s %.9f %.9f %.5f %d %06x",
                        e.seq,e.epoch,e.stroke,e.kind,e.x,e.y,e.p,e.ts,e.color&0xFFFFFF));
                lastSent=e.seq;
            }

            String gc=pendingGyroCursor;
            if(gc!=null){
                pendingGyroCursor=null;
                write(out,gc);
            }

            while(in.ready()){
                String line=in.readLine();
                if(line==null)return;
                handle(line);
            }

            long now=System.currentTimeMillis();
            if(now-lastPing>1800){
                write(out,"PING");
                lastPing=now;
            }
            sleep(8);
        }
    }

    private void handle(String line){
        String[] a=line.trim().split("\\s+");
        if(a.length==0)return;
        try{
            switch(a[0]){
                case "STATE":
                    if(a.length>=6){
                        long e=Long.parseLong(a[1]);
                        boolean cleared=store.syncEpoch(e);
                        double x=Double.parseDouble(a[2]),y=Double.parseDouble(a[3]);
                        store.setView(x,y);
                        if(cleared&&listener!=null)listener.onClear(e);
                        if(listener!=null)listener.onView(x,y);
                    }
                    break;
                case "VIEW":
                    if(a.length>=3){
                        double x=Double.parseDouble(a[1]),y=Double.parseDouble(a[2]);
                        store.setView(x,y);
                        if(listener!=null)listener.onView(x,y);
                    }
                    break;
                case "CFG":
                    if(a.length>=9&&listener!=null){
                        int bg=(int)Long.parseLong(a[1],16),br=(int)Long.parseLong(a[2],16);
                        boolean land=!a[3].equals("0"),clip=!a[4].equals("0");
                        float l=Float.parseFloat(a[5]),t=Float.parseFloat(a[6]),
                                r=Float.parseFloat(a[7]),b=Float.parseFloat(a[8]);
                        listener.onConfig(bg,br,land,clip,l,t,r,b);
                    }
                    break;
                case "MODE":
                    if(a.length>=2){
                        boolean gyro=!a[1].equals("0");
                        if(listener!=null)listener.onMode(gyro);
                        if(gyro&&activity!=null)activity.recalibrateGyro();
                    }
                    break;
                case "GVIEW":
                    if(a.length>=4&&listener!=null)
                        listener.onGyroView(Double.parseDouble(a[1]),
                                Double.parseDouble(a[2]),Double.parseDouble(a[3]));
                    break;
                case "CLEAR":
                    if(a.length>=2){
                        long e=Long.parseLong(a[1]);
                        store.clearTo(e);
                        if(listener!=null)listener.onClear(e);
                    }
                    break;
                case "ACKP":
                    if(a.length>=2)store.ack(Long.parseLong(a[1]));
                    break;
            }
        }catch(Exception ignored){}
    }

    private static void write(BufferedWriter w,String s)throws IOException{
        w.write(s);w.write('\n');w.flush();
    }
    private static void sleep(long ms){
        try{Thread.sleep(ms);}catch(InterruptedException ignored){}
    }

    @Override public void close(){
        running=false;
        store.setWake(null);
        Socket s=activeSocket;
        if(s!=null)try{s.close();}catch(Exception ignored){}
        if(thread!=null)thread.interrupt();
    }
}
