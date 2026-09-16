package io.openai.footdraw;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AudioClient implements AutoCloseable {
    private static final String HOST="194.87.97.119";
    private static final int PORT=4950;
    private static final int FRAME_BYTES=640; // 20 ms @ 16 kHz mono PCM16
    private static final int START_FRAMES=6;  // 120 ms network reserve
    private static final int TRACK_FRAMES=8;  // 160 ms AudioTrack capacity target
    private static final int HARD_MAX_FRAMES=50;

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
    private void requestFocus(){
        try{
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(focusChange -> {}).build();
            audioManager.requestAudioFocus(focusRequest);
        }catch(Exception ignored){}
    }

    private void net(){
        try{
            try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);}catch(Exception ignored){}
            socket=new DatagramSocket();
            socket.setSoTimeout(80);
            socket.setReceiveBufferSize(512*1024);
            try{socket.setTrafficClass(0xB8);}catch(Exception ignored){}
            InetAddress host=InetAddress.getByName(HOST);long lastHello=0;byte[] buf=new byte[4096];String tk=token();
            while(running){
                long now=System.currentTimeMillis();
                if(now-lastHello>650){byte[] h=("FDH1"+tk).getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(h,h.length,host,PORT));lastHello=now;}
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);socket.receive(p);
                    if(p.getAddress().equals(host))parse(Arrays.copyOf(p.getData(),p.getLength()));
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception ignored){}finally{if(socket!=null)socket.close();}
    }

    private void parse(byte[] d){
        if(d.length<10||d[0]!='F'||d[1]!='D'||d[2]!='A'||d[3]!='1')return;
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);b.position(4);
        long seq=Integer.toUnsignedLong(b.getInt());int samples=Short.toUnsignedInt(b.getShort());
        if(samples<=0||d.length!=10+samples*2)return;
        byte[] payload=Arrays.copyOfRange(d,10,d.length);

        // Control packet tunneled through the same UDP relay.
        if(payload.length>=28&&payload[0]=='F'&&payload[1]=='D'&&payload[2]=='C'&&payload[3]=='1'){
            try{
                int bg=((payload[4]&255)<<16)|((payload[5]&255)<<8)|(payload[6]&255);
                int br=((payload[7]&255)<<16)|((payload[8]&255)<<8)|(payload[9]&255);
                boolean land=payload[10]!=0,clip=payload[11]!=0;
                ByteBuffer c=ByteBuffer.wrap(payload,12,16).order(ByteOrder.BIG_ENDIAN);
                float l=c.getFloat(),t=c.getFloat(),r=c.getFloat(),bt=c.getFloat();
                if(listener!=null)listener.onConfig(bg,br,land,clip,l,t,r,bt);
            }catch(Exception ignored){}
            return;
        }

        long now=System.currentTimeMillis();

        // v9.1 audio packet: current 20 ms frame + previous 20 ms frame.
        // The server is deliberately unaware of this wrapper and simply relays it.
        if(payload.length>=8&&payload[0]=='F'&&payload[1]=='D'&&payload[2]=='R'&&payload[3]=='2'){
            try{
                ByteBuffer r=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);r.position(4);
                int curLen=Short.toUnsignedInt(r.getShort());
                if(curLen<=0||curLen>2048||r.remaining()<curLen+2)return;
                byte[] cur=new byte[curLen];r.get(cur);
                int prevLen=Short.toUnsignedInt(r.getShort());
                if(prevLen<0||prevLen>2048||r.remaining()!=prevLen)return;
                byte[] prev=null;if(prevLen>0){prev=new byte[prevLen];r.get(prev);}
                if(cur.length!=FRAME_BYTES)return;
                enqueuePacket(seq,cur,prev,now);
            }catch(Exception ignored){}
            return;
        }

        // Backward compatibility with v8/v9 Windows clients.
        if(payload.length==FRAME_BYTES)enqueuePacket(seq,payload,null,now);
    }

    private void enqueuePacket(long seq,byte[] current,byte[] previous,long now){
        synchronized(lock){
            if((lastPacketMs>0&&now-lastPacketMs>1800)||(expected>=0&&seq+64<expected)||(expected>=0&&seq>expected+256)){
                jitter.clear();expected=-1;
            }
            lastPacketMs=now;

            // Recover seq-1 from the redundant copy before inserting current frame.
            if(previous!=null&&previous.length==FRAME_BYTES&&seq>0){
                long ps=seq-1;
                if((expected<0||ps>=expected)&&!jitter.containsKey(ps))jitter.put(ps,previous);
            }
            if(expected<0||seq>=expected)jitter.put(seq,current);

            // Do not trim normal bursts. Earlier versions aggressively threw away a
            // chunk of speech whenever the queue briefly grew, which sounded like clipping.
            // Recenter only after a truly stale ~1 s backlog.
            if(jitter.size()>HARD_MAX_FRAMES){
                while(jitter.size()>10)jitter.pollFirstEntry();
                expected=jitter.isEmpty()?-1:jitter.firstKey();
            }
            lock.notifyAll();
        }
    }

    private byte[] takeNextFrame(int missingWaitMs){
        synchronized(lock){
            while(running&&expected<0){
                if(jitter.size()>=START_FRAMES){expected=jitter.firstKey();break;}
                try{lock.wait(8);}catch(InterruptedException ignored){}
            }
            if(!running)return null;

            long deadline=SystemClock.elapsedRealtime()+missingWaitMs;
            while(running){
                while(!jitter.isEmpty()&&jitter.firstKey()<expected)jitter.pollFirstEntry();
                byte[] f=jitter.remove(expected);
                if(f!=null){expected++;return f;}

                long left=deadline-SystemClock.elapsedRealtime();
                if(left<=0){expected++;return null;}
                try{lock.wait(Math.min(left,6));}catch(InterruptedException ignored){}
            }
            return null;
        }
    }

    private static short getSample(byte[] a,int index){int o=index*2;if(a==null||o+1>=a.length)return 0;return (short)((a[o]&255)|((a[o+1]&255)<<8));}
    private static void putSample(byte[] a,int index,int v){if(v>32767)v=32767;if(v<-32768)v=-32768;int o=index*2;if(o+1>=a.length)return;a[o]=(byte)(v&255);a[o+1]=(byte)((v>>8)&255);}

    private static byte[] conceal(byte[] last,int lossCount){
        if(last==null||last.length!=FRAME_BYTES)return new byte[FRAME_BYTES];
        if(lossCount>=3)return new byte[FRAME_BYTES];
        // Single/double unrecoverable loss only. Most one-packet losses are recovered
        // from FDR2. Use a short, smoothly fading continuation instead of hard silence.
        double startGain=lossCount==1?0.72:0.34;
        double endGain=lossCount==1?0.40:0.08;
        int n=last.length/2;byte[] out=new byte[last.length];
        for(int i=0;i<n;i++){
            double k=n<=1?1.0:(double)i/(double)(n-1);
            double g=startGain+(endGain-startGain)*k;
            putSample(out,i,(int)Math.round(getSample(last,i)*g));
        }
        return out;
    }

    private static byte[] softenResume(byte[] previous,byte[] current){
        if(previous==null||current==null)return current;
        byte[] out=Arrays.copyOf(current,current.length);
        int n=Math.min(48,out.length/2); // 3 ms, enough to remove a click without eating consonants
        short from=getSample(previous,Math.max(0,previous.length/2-1));
        for(int i=0;i<n;i++){
            double k=(double)(i+1)/(double)n;
            putSample(out,i,(int)Math.round(from*(1.0-k)+getSample(current,i)*k));
        }
        return out;
    }

    private static boolean writeAll(AudioTrack t,byte[] frame){
        int off=0;
        while(off<frame.length){
            int n=t.write(frame,off,frame.length-off,AudioTrack.WRITE_BLOCKING);
            if(n<=0)return false;
            off+=n;
        }
        return true;
    }

    private void play(){
        try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);}catch(Exception ignored){}
        int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<0)min=FRAME_BYTES*4;
        int bs=Math.max(min,FRAME_BYTES*TRACK_FRAMES);
        AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat af=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        AudioTrack t=null;
        try{
            // Deliberately do not force PERFORMANCE_MODE_LOW_LATENCY here. On several
            // custom ROM audio HALs it makes the hardware buffer too aggressive for
            // internet voice and produces periodic underruns.
            t=new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(t.getState()!=AudioTrack.STATE_INITIALIZED)return;
            try{
                int cap=t.getBufferCapacityInFrames();
                int want=Math.min(cap,(FRAME_BYTES/2)*TRACK_FRAMES);
                if(want>0)t.setBufferSizeInFrames(want);
            }catch(Exception ignored){}
            t.setVolume(1.0f);

            byte[] lastGood=null,lastOutput=null;
            int consecutiveLoss=0;

            // Real prebuffer: fill AudioTrack first, only then start playback.
            // Previous builds called play() on an empty track and waited for UDP afterwards.
            for(int i=0;i<START_FRAMES&&running;i++){
                byte[] frame=takeNextFrame(28);
                if(frame==null){consecutiveLoss++;frame=conceal(lastGood,consecutiveLoss);}else{consecutiveLoss=0;lastGood=frame;}
                lastOutput=frame;
                if(!writeAll(t,frame))return;
            }
            if(!running)return;
            t.play();

            while(running){
                // 28 ms is enough for the next UDP packet to deliver the redundant
                // copy of a missing 20 ms frame, while the AudioTrack prebuffer hides the wait.
                byte[] frame=takeNextFrame(28);
                if(frame==null){
                    consecutiveLoss++;
                    frame=conceal(lastGood,consecutiveLoss);
                }else{
                    if(consecutiveLoss>0)frame=softenResume(lastOutput,frame);
                    consecutiveLoss=0;lastGood=frame;
                }
                lastOutput=frame;
                if(!writeAll(t,frame))break;
            }
        }catch(Exception ignored){}finally{
            if(t!=null){try{t.pause();}catch(Exception ignored){}try{t.flush();}catch(Exception ignored){}try{t.stop();}catch(Exception ignored){}try{t.release();}catch(Exception ignored){}}
        }
    }

    @Override public void close(){
        running=false;if(socket!=null)socket.close();synchronized(lock){lock.notifyAll();}
        if(netThread!=null)netThread.interrupt();if(playThread!=null)playThread.interrupt();
        try{if(focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);}catch(Exception ignored){}
    }
}
