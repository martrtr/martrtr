from pathlib import Path
import re

p = Path('app/src/main/java/io/openai/footdraw/AudioClient.java')
s = p.read_text()
constants = re.search(r'    private static final String HOST=.*?;private static final int PORT=.*?;private static final String TOKEN=.*?;\n', s).group(0)

new = '''package io.openai.footdraw;

import android.media.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AudioClient implements AutoCloseable {
''' + constants + '''    private volatile boolean running=true;
    private DatagramSocket socket;
    private Thread netThread,playThread;
    private final TreeMap<Long,byte[]> jitter=new TreeMap<>();
    private final Object lock=new Object();
    private long expected=-1,lastPacketMs=0;

    void start(){netThread=new Thread(this::net,"FootDraw-UDP");playThread=new Thread(this::play,"FootDraw-Audio");netThread.start();playThread.start();}

    private void net(){
        try{socket=new DatagramSocket();socket.setSoTimeout(250);socket.setReceiveBufferSize(64*1024);InetAddress host=InetAddress.getByName(HOST);long lastHello=0;byte[] buf=new byte[4096];
            while(running){long now=System.currentTimeMillis();if(now-lastHello>1000){byte[] h=("FDH1"+TOKEN).getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(h,h.length,host,PORT));lastHello=now;}
                try{DatagramPacket packet=new DatagramPacket(buf,buf.length);socket.receive(packet);if(packet.getAddress().equals(host))parse(Arrays.copyOf(packet.getData(),packet.getLength()));}catch(SocketTimeoutException ignored){}}
        }catch(Exception ignored){}finally{if(socket!=null)socket.close();}
    }

    private void parse(byte[] d){
        if(d.length<10||d[0]!='F'||d[1]!='D'||d[2]!='A'||d[3]!='1')return;
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);b.position(4);long seq=Integer.toUnsignedLong(b.getInt());int samples=Short.toUnsignedInt(b.getShort());if(samples<=0||d.length!=10+samples*2)return;
        byte[] pcm=Arrays.copyOfRange(d,10,d.length);long now=System.currentTimeMillis();
        synchronized(lock){if((lastPacketMs>0&&now-lastPacketMs>650)||(expected>=0&&seq+8<expected)||(expected>=0&&seq>expected+80)){jitter.clear();expected=-1;}lastPacketMs=now;if(expected>=0&&seq<expected)return;jitter.put(seq,pcm);while(jitter.size()>120)jitter.pollFirstEntry();lock.notifyAll();}
    }

    private void play(){
        int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<0)min=4096;int bs=Math.max(min,640*12);
        AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat af=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        AudioTrack t=null;try{t=new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af).setBufferSizeInBytes(bs).setTransferMode(AudioTrack.MODE_STREAM).build();if(t.getState()!=AudioTrack.STATE_INITIALIZED)return;t.setVolume(1.0f);t.play();byte[] silence=new byte[640];
            while(running){byte[] frame=null;synchronized(lock){if(expected<0&&jitter.size()>=3)expected=jitter.firstKey();if(expected>=0){frame=jitter.remove(expected);expected++;}}if(frame==null)frame=silence;int off=0;while(running&&off<frame.length){int n=t.write(frame,off,frame.length-off,AudioTrack.WRITE_BLOCKING);if(n<=0)break;off+=n;}}
        }catch(Exception ignored){}finally{if(t!=null){try{t.stop();}catch(Exception ignored){}try{t.release();}catch(Exception ignored){}}}
    }

    @Override public void close(){running=false;if(socket!=null)socket.close();if(netThread!=null)netThread.interrupt();if(playThread!=null)playThread.interrupt();}
}
'''
p.write_text(new)
