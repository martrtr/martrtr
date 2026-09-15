package io.openai.footdraw;

import android.media.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AudioClient implements AutoCloseable {
    private static final String HOST="194.87.97.119";private static final int PORT=4950;private static final String TOKEN="f2f3a025173941f9cb1d297eba2c0469";
    private volatile boolean running=true;private DatagramSocket socket;private Thread netThread,playThread;private final TreeMap<Long,byte[]> jitter=new TreeMap<>();private final Object lock=new Object();private long expected=-1;
    void start(){netThread=new Thread(this::net,"FootDraw-UDP");playThread=new Thread(this::play,"FootDraw-Audio");netThread.start();playThread.start();}
    private void net(){try{socket=new DatagramSocket();socket.setSoTimeout(250);InetAddress host=InetAddress.getByName(HOST);long lastHello=0;byte[] buf=new byte[1600];while(running){long now=System.currentTimeMillis();if(now-lastHello>1500){byte[] h=("FDH1"+TOKEN).getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(h,h.length,host,PORT));lastHello=now;}try{DatagramPacket p=new DatagramPacket(buf,buf.length);socket.receive(p);parse(Arrays.copyOf(p.getData(),p.getLength()));}catch(SocketTimeoutException ignored){}}}catch(Exception ignored){}finally{if(socket!=null)socket.close();}}
    private void parse(byte[] d){if(d.length<10||d[0]!='F'||d[1]!='D'||d[2]!='A'||d[3]!='1')return;ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);b.position(4);long seq=Integer.toUnsignedLong(b.getInt());int samples=Short.toUnsignedInt(b.getShort());if(d.length!=10+samples*2)return;byte[] pcm=Arrays.copyOfRange(d,10,d.length);synchronized(lock){if(expected>=0&&(seq+200<expected||seq>expected+2000)){jitter.clear();expected=-1;}jitter.put(seq,pcm);while(jitter.size()>100)jitter.pollFirstEntry();lock.notifyAll();}}
    private void play(){int min=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);int bs=Math.max(min,640*8);AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();AudioFormat af=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();AudioTrack t=new AudioTrack(aa,af,bs,AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);t.play();byte[] silence=new byte[640];try{while(running){byte[] frame=null;synchronized(lock){if(expected<0&&jitter.size()>=3)expected=jitter.firstKey();if(expected>=0){frame=jitter.remove(expected);expected++;}}if(frame==null)frame=silence;t.write(frame,0,frame.length,AudioTrack.WRITE_BLOCKING);}}finally{try{t.stop();}catch(Exception ignored){}t.release();}}
    @Override public void close(){running=false;if(socket!=null)socket.close();if(netThread!=null)netThread.interrupt();if(playThread!=null)playThread.interrupt();}
}
