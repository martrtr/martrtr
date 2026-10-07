package io.openai.footdraw;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.net.Network;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Phone/headset microphone -> relay over the explicitly selected cellular network. */
final class PhoneAudioUplink implements AutoCloseable {
    private static final int RATE=16000, SAMPLES=320, FRAME_BYTES=SAMPLES*2;
    private final Context context;
    private final NetworkRouter router;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private volatile DatagramSocket socket;
    private volatile AudioRecord record;
    private Thread thread;
    private int seq=0;

    PhoneAudioUplink(Context c, NetworkRouter r){
        context=c.getApplicationContext();
        router=r;
    }

    void start(){
        if(!running.compareAndSet(false,true))return;
        thread=new Thread(this::loop,"FootDraw-PhoneMic");
        thread.start();
    }

    private boolean permitted(){
        return android.os.Build.VERSION.SDK_INT<23 ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }

    private void loop(){
        try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);}catch(Throwable ignored){}
        long backoff=200;
        while(running.get()){
            if(!permitted()){sleep(500);continue;}
            DatagramSocket s=null;
            AudioRecord r=null;
            try{
                Network cell=router.awaitCellular(10000);
                if(cell==null)throw new java.io.IOException("cellular unavailable");
                s=new DatagramSocket();
                socket=s;
                cell.bindSocket(s);
                s.setSendBufferSize(256*1024);
                try{s.setTrafficClass(0xB8);}catch(Exception ignored){}
                InetAddress host=cell.getByName(NetClient.HOST);

                int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<0)min=FRAME_BYTES*6;
                r=new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(Math.max(min,FRAME_BYTES*8))
                        .build();
                record=r;
                if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new java.io.IOException("AudioRecord init failed");
                r.startRecording();
                backoff=200;

                byte[] cur=new byte[FRAME_BYTES], prev=null;
                while(running.get()&&!s.isClosed()){
                    int off=0;
                    while(off<FRAME_BYTES&&running.get()){
                        int n=r.read(cur,off,FRAME_BYTES-off,AudioRecord.READ_BLOCKING);
                        if(n<0)throw new java.io.IOException("AudioRecord read "+n);
                        if(n==0)continue;
                        off+=n;
                    }
                    if(off!=FRAME_BYTES)continue;
                    int prevLen=prev==null?0:FRAME_BYTES;
                    byte[] inner=new byte[4+2+FRAME_BYTES+2+prevLen];
                    ByteBuffer ib=ByteBuffer.wrap(inner).order(ByteOrder.BIG_ENDIAN);
                    ib.put((byte)'F').put((byte)'D').put((byte)'R').put((byte)'2');
                    ib.putShort((short)FRAME_BYTES).put(cur).putShort((short)prevLen);
                    if(prevLen!=0)ib.put(prev);

                    byte[] pkt=new byte[42+inner.length];
                    ByteBuffer pb=ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN);
                    pb.put((byte)'F').put((byte)'D').put((byte)'P').put((byte)'1');
                    pb.put(NetClient.TOKEN.getBytes(StandardCharsets.US_ASCII));
                    pb.putInt(++seq).putShort((short)(inner.length/2)).put(inner);
                    s.send(new DatagramPacket(pkt,pkt.length,host,NetClient.UDP_PORT));
                    prev=cur.clone();
                }
            }catch(Throwable ignored){
                sleep(backoff);backoff=Math.min(2500,backoff*2);
            }finally{
                if(r!=null){
                    try{r.stop();}catch(Throwable ignored){}
                    try{r.release();}catch(Throwable ignored){}
                }
                if(record==r)record=null;
                if(s!=null)s.close();
                if(socket==s)socket=null;
            }
        }
    }

    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){}}

    @Override public void close(){
        running.set(false);
        AudioRecord r=record;
        if(r!=null)try{r.stop();}catch(Throwable ignored){}
        DatagramSocket s=socket;
        if(s!=null)s.close();
        if(thread!=null)thread.interrupt();
    }
}
