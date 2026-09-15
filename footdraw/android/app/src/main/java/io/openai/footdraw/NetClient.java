package io.openai.footdraw;

import android.content.*;
import java.io.*;
import java.net.*;
import java.util.*;

public final class NetClient implements AutoCloseable {
    interface Listener { void onView(double x,double y); void onClear(long epoch); }
    private static final String HOST="194.87.97.119"; private static final int PORT=4950; private static final String TOKEN="f2f3a025173941f9cb1d297eba2c0469";
    private final Store store; private final String deviceId; private volatile boolean running=true; private Thread thread; private Listener listener; private int width,height;
    NetClient(Context c,Store s){store=s;SharedPreferences p=c.getSharedPreferences("footdraw",Context.MODE_PRIVATE);String id=p.getString("device",null);if(id==null){id=UUID.randomUUID().toString();p.edit().putString("device",id).apply();}deviceId=id;}
    void start(int w,int h,Listener l){width=w;height=h;listener=l;thread=new Thread(this::loop,"FootDraw-TCP");thread.start();}
    private void loop(){int wait=250;while(running){try(Socket sock=new Socket()){sock.connect(new InetSocketAddress(HOST,PORT),4000);sock.setSoTimeout(180);sock.setTcpNoDelay(true);wait=250;runSocket(sock);}catch(Exception ignored){sleep(wait);wait=Math.min(5000,wait*2);}}}
    private void runSocket(Socket s)throws Exception{BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));BufferedWriter out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));write(out,"HELLO PHONE "+TOKEN+" "+deviceId+" "+width+" "+height);long lastSent=0,lastPing=System.currentTimeMillis();while(running&&!s.isClosed()){for(Store.Event e:store.pendingAfter(lastSent,96)){write(out,String.format(Locale.US,"P %d %d %s %s %.9f %.9f %.5f %d",e.seq,e.epoch,e.stroke,e.kind,e.x,e.y,e.p,e.ts));lastSent=e.seq;}try{String line=in.readLine();if(line==null)break;handle(line);}catch(SocketTimeoutException ignored){}if(System.currentTimeMillis()-lastPing>5000){write(out,"PING");lastPing=System.currentTimeMillis();}}}
    private void handle(String line){String[] a=line.trim().split("\\s+");if(a.length==0)return;try{switch(a[0]){case "STATE":if(a.length>=6){long e=Long.parseLong(a[1]);boolean cleared=store.syncEpoch(e);double x=Double.parseDouble(a[2]),y=Double.parseDouble(a[3]);store.setView(x,y);if(cleared&&listener!=null)listener.onClear(e);if(listener!=null)listener.onView(x,y);}break;case "VIEW":if(a.length>=3){double x=Double.parseDouble(a[1]),y=Double.parseDouble(a[2]);store.setView(x,y);if(listener!=null)listener.onView(x,y);}break;case "CLEAR":if(a.length>=2){long e=Long.parseLong(a[1]);store.clearTo(e);if(listener!=null)listener.onClear(e);}break;case "ACKP":if(a.length>=2)store.ack(Long.parseLong(a[1]));break;}}catch(Exception ignored){}}
    private static void write(BufferedWriter w,String s)throws IOException{w.write(s);w.write('\n');w.flush();}private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){}}
    @Override public void close(){running=false;if(thread!=null)thread.interrupt();}
}
