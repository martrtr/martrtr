package io.openai.footdraw;

import android.content.Context;
import android.media.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AudioClient implements AutoCloseable {
    private static final String HOST="194.87.97.119";
    private static final int PORT=4950;
    private static final int FRAME_BYTES=640;
    private volatile boolean running=true;
    private DatagramSocket socket;
    private Thread netThread,playThread;
    private final TreeMap<Long,byte[]> jitter=new TreeMap<>();
    private final Object lock=new Object();
    private long expected=-1,lastPacketMs=0;
    private final AudioManager audioManager;
    private final NetClient.Listener listener;
    private AudioFocusRequest focusRequest;

    AudioClient(Context context,NetClient.Listener l){audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);listener=l;}
    void start(){requestFocus();netThread=new Thread(this::net,"FootDraw-UDP");playThread=new Thread(this::play,"FootDraw-Audio");netThread.start();playThread.start();}

    private static String token(){try{Field f=NetClient.class.getDeclaredField("TOKEN");f.setAccessible(true);return (String)f.get(null);}catch(Exception e){return "";}}
    private void requestFocus(){try{AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(focusChange -> {}).build();audioManager.requestAudioFocus(focusRequest);}catch(Exception ignored){}}

    private void net(){
        try{
            socket=new DatagramSocket();socket.setSoTimeout(100);socket.setReceiveBufferSize(128*1024);
            InetAddress host=InetAddress.getByName(HOST);long lastHello=0;byte[] buf=new byte[4096];String tk=token();
            while(running){
                long now=System.currentTimeMillis();
                if(now-lastHello>650){byte[] h=("FDH1"+tk).getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(h,h.length,host,PORT));lastHello=now;}
                try{DatagramPacket p=new DatagramPacket(buf,buf.length);socket.receive(p);if(p.getAddress().equals(host))parse(Arrays.copyOf(p.getData(),p.getLength()));}catch(SocketTimeoutException ignored){}
            }
        }catch(Exception ignored){}finally{if(socket!=null)socket.close();}
    }

    private void parse(byte[] d){
        if(d.length<10||d[0]!='F'||d[1]!='D'||d[2]!='A'||d[3]!='1')return;
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);b.position(4);
        long seq=Integer.toUnsignedLong(b.getInt());int samples=Short.toUnsignedInt(b.getShort());
        if(samples<=0||d.length!=10+samples*2)return;
        byte[] pcm=Arrays.copyOfRange(d,10,d.length);

        if(pcm.length>=28&&pcm[0]=='F'&&pcm[1]=='D'&&pcm[2]=='C'&&pcm[3]=='1'){
            try{int bg=((pcm[4]&255)<<16)|((pcm[5]&255)<<8)|(pcm[6]&255);int br=((pcm[7]&255)<<16)|((pcm[8]&255)<<8)|(pcm[9]&255);boolean land=pcm[10]!=0,clip=pcm[11]!=0;ByteBuffer c=ByteBuffer.wrap(pcm,12,16).order(ByteOrder.BIG_ENDIAN);float l=c.getFloat(),t=c.getFloat(),r=c.getFloat(),bt=c.getFloat();if(listener!=null)listener.onConfig(bg,br,land,clip,l,t,r,bt);}catch(Exception ignored){}return;
        }

        long now=System.currentTimeMillis();
        synchronized(lock){
            if((lastPacketMs>0&&now-lastPacketMs>1200)||(expected>=0&&seq+32<expected)||(expected>=0&&seq>expected+128)){
                jitter.clear();expected=-1;
            }
            lastPacketMs=now;
            if(expected>=0&&seq<expected)return;
            jitter.put(seq,pcm);
            if(jitter.size()>14){while(jitter.size()>6)jitter.pollFirstEntry();expected=jitter.firstKey();}
            lock.notifyAll();
        }
    }

    private static byte[] conceal(byte[] last,int lossCount){
        if(last==null||last.length==0)return new byte[FRAME_BYTES];
        if(lossCount>=3)return new byte[last.length];
        double gain=lossCount==1?0.72:0.42;
        byte[] out=new byte[last.length];
        ByteBuffer src=ByteBuffer.wrap(last).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dst=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        while(src.remaining()>=2){short s=src.getShort();int v=(int)Math.round(s*gain);if(v>32767)v=32767;if(v<-32768)v=-32768;dst.putShort((short)v);}return out;
    }

    private void play(){
        int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<0)min=1920;
        int bs=Math.max(min,FRAME_BYTES*6);
        AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat af=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        AudioTrack t=null;
        try{
            AudioTrack.Builder builder=new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM);
            if(android.os.Build.VERSION.SDK_INT>=26)builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            t=builder.build();if(t.getState()!=AudioTrack.STATE_INITIALIZED)return;
            try{int cap=t.getBufferCapacityInFrames();int want=Math.min(cap,1280);if(want>=640)t.setBufferSizeInFrames(want);}catch(Exception ignored){}
            if(android.os.Build.VERSION.SDK_INT>=31){try{t.setStartThresholdInFrames(Math.min(640,t.getBufferCapacityInFrames()));}catch(Exception ignored){}}
            t.setVolume(1.0f);t.play();

            byte[] lastFrame=null;int consecutiveLoss=0;
            while(running){
                byte[] frame=null;
                synchronized(lock){
                    while(running&&expected<0){if(jitter.size()>=3){expected=jitter.firstKey();break;}try{lock.wait(10);}catch(InterruptedException ignored){}}
                    if(!running)break;
                    if(expected>=0){
                        frame=jitter.remove(expected);
                        if(frame==null){
                            long deadline=System.currentTimeMillis()+32;
                            while(running&&frame==null&&System.currentTimeMillis()<deadline){
                                long remain=Math.max(1,deadline-System.currentTimeMillis());
                                try{lock.wait(remain);}catch(InterruptedException ignored){}
                                frame=jitter.remove(expected);
                            }
                        }
                        expected++;
                    }
                }
                if(frame==null){consecutiveLoss++;frame=conceal(lastFrame,consecutiveLoss);}else{consecutiveLoss=0;lastFrame=frame;}
                int off=0;while(running&&off<frame.length){int n=t.write(frame,off,frame.length-off,AudioTrack.WRITE_BLOCKING);if(n<=0)break;off+=n;}
            }
        }catch(Exception ignored){}finally{if(t!=null){try{t.stop();}catch(Exception ignored){}try{t.release();}catch(Exception ignored){}}}
    }

    @Override public void close(){running=false;if(socket!=null)socket.close();synchronized(lock){lock.notifyAll();}if(netThread!=null)netThread.interrupt();if(playThread!=null)playThread.interrupt();try{if(focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);}catch(Exception ignored){}}
}
