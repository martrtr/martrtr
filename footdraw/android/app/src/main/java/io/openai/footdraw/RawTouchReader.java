package io.openai.footdraw;

import android.app.Activity;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class RawTouchReader {
    private final Activity activity;
    private final DrawingView drawing;
    private volatile java.lang.Process proc;
    private volatile boolean closed=false;

    RawTouchReader(Activity a, DrawingView d){activity=a;drawing=d;}

    private File install() throws Exception {
        File f=new File(activity.getFilesDir(),"foot_raw_helper");
        try(InputStream in=activity.getResources().openRawResource(R.raw.foot_raw);
            FileOutputStream out=new FileOutputStream(f,false)){
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            out.getFD().sync();
        }
        f.setReadable(true,true); f.setExecutable(true,true);
        return f;
    }

    void start(){
        new Thread(()->{
            try{
                File src=install();
                int pid=Process.myPid();
                String dst="/data/local/tmp/footdraw-raw";
                String cmd="cp '"+src.getAbsolutePath()+"' "+dst+" >/dev/null 2>&1 && chmod 755 "+dst+" && exec "+dst+" "+pid;
                proc=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
                try(BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))){
                    String line;
                    while(!closed&&(line=br.readLine())!=null){
                        line=line.trim();
                        if(line.equals("READY")){
                            activity.runOnUiThread(()->drawing.setRawMode(true));
                            continue;
                        }
                        if(line.startsWith("ERR"))break;
                        String[] p=line.split("\\s+");
                        if(p.length<5)continue;
                        char kind=p[0].charAt(0);
                        int id=Integer.parseInt(p[1]);
                        float nx=Float.parseFloat(p[2]);
                        float ny=Float.parseFloat(p[3]);
                        float pressure=Float.parseFloat(p[4]);
                        activity.runOnUiThread(()->drawing.onRawTouch(kind,id,nx,ny,pressure));
                    }
                }
            }catch(Throwable ignored){}
            activity.runOnUiThread(()->drawing.setRawMode(false));
        },"FootDraw-RawTouch").start();
    }

    void close(){
        closed=true;
        try{if(proc!=null)proc.destroy();}catch(Throwable ignored){}
        drawing.setRawMode(false);
    }
}
