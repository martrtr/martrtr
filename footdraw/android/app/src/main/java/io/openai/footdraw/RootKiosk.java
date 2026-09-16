package io.openai.footdraw;

import android.app.Activity;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RootKiosk {
    private volatile boolean active=false;
    private java.lang.Process watchdog;

    private File installTouchHelper(Activity a) throws Exception {
        File outFile=new File(a.getFilesDir(),"foot_touch_helper");
        try(InputStream in=a.getResources().openRawResource(R.raw.foot_touch);
            FileOutputStream out=new FileOutputStream(outFile,false)){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))>0)out.write(buf,0,n);
            out.getFD().sync();
        }
        outFile.setReadable(true,true);
        outFile.setExecutable(true,true);
        return outFile;
    }

    void start(Activity a){
        final int pid=Process.myPid();
        new Thread(()->{
            try{
                File helper=installTouchHelper(a);
                java.lang.Process check=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();
                String text=new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                int rc=check.waitFor();
                if(rc!=0||!text.contains("uid=0"))return;

                active=true;
                String pkg=a.getPackageName();
                String helperPath=helper.getAbsolutePath();
                String script=
                    "G0=$(settings get system three_finger_gesture 2>/dev/null); "+
                    "G1=$(settings get system three_gesture_down 2>/dev/null); "+
                    "G2=$(settings get system three_gesture_long_press 2>/dev/null); "+
                    "G3=$(settings get system three_finger_screenshot 2>/dev/null); "+
                    "G4=$(settings get secure three_finger_gesture 2>/dev/null); "+
                    "PALM=/sys/class/touch/touch_dev/palm_sensor; PALM0=''; [ -r \"$PALM\" ] && PALM0=$(cat \"$PALM\" 2>/dev/null); "+
                    "HELP=/data/local/tmp/footdraw-touch; cp '"+helperPath+"' \"$HELP\" >/dev/null 2>&1 || true; chmod 755 \"$HELP\" >/dev/null 2>&1 || true; "+
                    // Save exactly what the panel used before FootDraw. Xiaomi MT6895 exposes
                    // these modes through /dev/xiaomi-touch. Empty values mean the helper is not supported.
                    "GM=$($HELP get 0 2>/dev/null); ACT=$($HELP get 1 2>/dev/null); UP=$($HELP get 2 2>/dev/null); TOL=$($HELP get 3 2>/dev/null); EDGE=$($HELP get 7 2>/dev/null); "+
                    "disable3(){ "+
                    "settings put system three_finger_gesture 0 >/dev/null 2>&1; "+
                    "settings put system three_gesture_down none >/dev/null 2>&1; "+
                    "settings put system three_gesture_long_press none >/dev/null 2>&1; "+
                    "settings put system three_finger_screenshot 0 >/dev/null 2>&1; "+
                    "settings put secure three_finger_gesture 0 >/dev/null 2>&1; }; "+
                    // Foot Mode: disable palm suppression, disable edge filtering, and use the same
                    // high-touch settings used by Xiaomi high-polling/game implementations.
                    "footMode(){ "+
                    "if [ -e \"$PALM\" ]; then echo 0 > \"$PALM\" 2>/dev/null || true; fi; "+
                    "if [ -x \"$HELP\" ]; then "+
                    "$HELP set 0 1 >/dev/null 2>&1 || true; "+
                    "$HELP set 1 1 >/dev/null 2>&1 || true; "+
                    "$HELP set 2 99 >/dev/null 2>&1 || true; "+
                    "$HELP set 3 5 >/dev/null 2>&1 || true; "+
                    "$HELP set 7 0 >/dev/null 2>&1 || true; fi; }; "+
                    "restoreMode(){ [ -n \"$2\" ] && [ -x \"$HELP\" ] && $HELP set \"$1\" \"$2\" >/dev/null 2>&1 || true; }; "+
                    "restore(){ "+
                    "if [ \"$G0\" = null ] || [ -z \"$G0\" ]; then settings delete system three_finger_gesture >/dev/null 2>&1; else settings put system three_finger_gesture \"$G0\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G1\" = null ] || [ -z \"$G1\" ]; then settings delete system three_gesture_down >/dev/null 2>&1; else settings put system three_gesture_down \"$G1\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G2\" = null ] || [ -z \"$G2\" ]; then settings delete system three_gesture_long_press >/dev/null 2>&1; else settings put system three_gesture_long_press \"$G2\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G3\" = null ] || [ -z \"$G3\" ]; then settings delete system three_finger_screenshot >/dev/null 2>&1; else settings put system three_finger_screenshot \"$G3\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G4\" = null ] || [ -z \"$G4\" ]; then settings delete secure three_finger_gesture >/dev/null 2>&1; else settings put secure three_finger_gesture \"$G4\" >/dev/null 2>&1; fi; "+
                    "restoreMode 0 \"$GM\"; restoreMode 1 \"$ACT\"; restoreMode 2 \"$UP\"; restoreMode 3 \"$TOL\"; restoreMode 7 \"$EDGE\"; "+
                    "if [ -n \"$PALM0\" ] && [ -e \"$PALM\" ]; then echo \"$PALM0\" > \"$PALM\" 2>/dev/null || true; fi; "+
                    "rm -f \"$HELP\" >/dev/null 2>&1 || true; }; "+
                    "disable3; footMode; cmd statusbar collapse >/dev/null 2>&1; "+
                    "N=0; while [ -d /proc/"+pid+" ]; do "+
                    "disable3; N=$((N+1)); if [ $N -ge 5 ]; then footMode; N=0; fi; "+
                    "cmd statusbar send-disable-flag home recents statusbar-expansion quick-settings notification-peek notification-icons system-icons clock >/dev/null 2>&1; "+
                    "F=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus); case \"$F\" in *"+pkg+"*) ;; *) am start -n "+pkg+"/.MainActivity >/dev/null 2>&1 ;; esac; "+
                    "sleep 0.20; done; "+
                    "cmd statusbar send-disable-flag none >/dev/null 2>&1; restore";
                watchdog=new ProcessBuilder("su","-c",script).start();
            }catch(Exception ignored){}
        },"FootDraw-RootKiosk").start();
    }

    void stop(){
        if(!active)return;
        active=false;
        new Thread(()->{
            try{if(watchdog!=null)watchdog.destroy();}catch(Exception ignored){}
            try{new ProcessBuilder("su","-c","cmd statusbar send-disable-flag none; cmd statusbar collapse").start().waitFor();}catch(Exception ignored){}
        },"FootDraw-RootRestore").start();
    }
}
