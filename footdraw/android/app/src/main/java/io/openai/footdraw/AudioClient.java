package io.openai.footdraw;

import android.content.Context;
import android.media.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AudioClient implements AutoCloseable {
    private static final String HOST="194.87.97.119";
    private static final int PORT=4950;
    private static final String TOKEN="f2f3a025173941f9cb1d297eba2c0469";
    private volatile boolean running=true;
    private DatagramSocket socket;
    private Thread netThread,playThread;
    private final TreeMap<Long,byte[]> jitter=new TreeMap<>();
    private final Object lock=new Object();
    private long expected=-1,lastPacketMs=0;
    private final AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    AudioClient(Context context){ audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }
    void start(){ requestFocus(); netThread=new Thread(this::net,"FootDraw-UDP");playThread=new Thread(this::play,"FootDraw-Audio");netThread.start();playThread.start(); }

    private void requestFocus(){
        try{
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener(focusChange -> {}).build();
            audioManager.requestAudioFocus(focusRequest);
        }catch(Exception ignored){}
    }

    private void net(){
        try{
            socket=new DatagramSocket();socket.setSoTimeout(120);socket.setReceiveBufferSize(32*1024);
            InetAddress host=InetAddress.getByName(HOST);long lastHello=0;byte[] buf=new byte[4096];
            while(running){
                long now=System.currentTimeMillis();if(now-lastHello>650){byte[] h=("FDH1"+TOKEN).getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(h,h.length,host,PORT));lastHello=now;}
                try{DatagramPacket p=new DatagramPacket(buf,buf.length);socket.receive(p);if(p.getAddress().equals(host))parse(Arrays.copyOf(p.getData(),p.getLength()));}catch(SocketTimeoutException ignored){}
            }
        }catch(Exception ignored){}finally{if(socket!=null)socket.close();}
    }

    private void parse(byte[] d){
        if(d.length<10||d[0]!='F'||d[1]!='D'||d[2]!='A'||d[3]!='1')return;
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);b.position(4);long seq=Integer.toUnsignedLong(b.getInt());int samples=Short.toUnsignedInt(b.getShort());if(samples<=0||d.length!=10+samples*2)return;
        byte[] pcm=Arrays.copyOfRange(d,10,d.length);long now=System.currentTimeMillis();
        synchronized(lock){
            if((lastPacketMs>0&&now-lastPacketMs>300)||(expected>=0&&seq+5<expected)||(expected>=0&&seq>expected+40)){jitter.clear();expected=-1;}
            lastPacketMs=now;if(expected>=0&&seq<expected)return;jitter.put(seq,pcm);
            while(jitter.size()>8)jitter.pollFirstEntry();
            if(jitter.size()>=7){while(jitter.size()>3)jitter.pollFirstEntry();expected=jitter.firstKey();}
            lock.notifyAll();
        }
    }

    private void play(){
        int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<0)min=1024;int bs=Math.max(min,320*4);
        AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat af=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        AudioTrack t=null;
        try{
            AudioTrack.Builder builder=new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM);
            if(android.os.Build.VERSION.SDK_INT>=26)builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            t=builder.build();if(t.getState()!=AudioTrack.STATE_INITIALIZED)return;t.setVolume(1.0f);t.play();byte[] silence=new byte[320];
            while(running){
                byte[] frame=null;
                synchronized(lock){if(expected<0&&jitter.size()>=2)expected=jitter.firstKey();if(expected>=0){frame=jitter.remove(expected);expected++;}}
                if(frame==null)frame=silence;
                int off=0;while(running&&off<frame.length){int n=t.write(frame,off,frame.length-off,AudioTrack.WRITE_BLOCKING);if(n<=0)break;off+=n;}
            }
        }catch(Exception ignored){}finally{if(t!=null){try{t.stop();}catch(Exception ignored){}try{t.release();}catch(Exception ignored){}}}
    }

    @Override public void close(){running=false;if(socket!=null)socket.close();if(netThread!=null)netThread.interrupt();if(playThread!=null)playThread.interrupt();try{if(focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);}catch(Exception ignored){}}
}
