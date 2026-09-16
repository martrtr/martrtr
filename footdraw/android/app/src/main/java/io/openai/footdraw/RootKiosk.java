package io.openai.footdraw;

import android.app.Activity;
import android.os.Process;
import java.nio.charset.StandardCharsets;

public final class RootKiosk {
    private volatile boolean active=false;
    private java.lang.Process watchdog;

    void start(Activity a){
        final int pid=Process.myPid();
        new Thread(()->{
            try{
                java.lang.Process check=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();
                String text=new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                int rc=check.waitFor();
                if(rc!=0||!text.contains("uid=0"))return;

                active=true;
                String pkg=a.getPackageName();
                String script=
                    "G0=$(settings get system three_finger_gesture 2>/dev/null); "+
                    "G1=$(settings get system three_gesture_down 2>/dev/null); "+
                    "G2=$(settings get system three_gesture_long_press 2>/dev/null); "+
                    "G3=$(settings get system three_finger_screenshot 2>/dev/null); "+
                    "G4=$(settings get secure three_finger_gesture 2>/dev/null); "+
                    "disable3(){ "+
                    "settings put system three_finger_gesture 0 >/dev/null 2>&1; "+
                    "settings put system three_gesture_down none >/dev/null 2>&1; "+
                    "settings put system three_gesture_long_press none >/dev/null 2>&1; "+
                    "settings put system three_finger_screenshot 0 >/dev/null 2>&1; "+
                    "settings put secure three_finger_gesture 0 >/dev/null 2>&1; }; "+
                    "restore(){ "+
                    "if [ \"$G0\" = null ] || [ -z \"$G0\" ]; then settings delete system three_finger_gesture >/dev/null 2>&1; else settings put system three_finger_gesture \"$G0\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G1\" = null ] || [ -z \"$G1\" ]; then settings delete system three_gesture_down >/dev/null 2>&1; else settings put system three_gesture_down \"$G1\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G2\" = null ] || [ -z \"$G2\" ]; then settings delete system three_gesture_long_press >/dev/null 2>&1; else settings put system three_gesture_long_press \"$G2\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G3\" = null ] || [ -z \"$G3\" ]; then settings delete system three_finger_screenshot >/dev/null 2>&1; else settings put system three_finger_screenshot \"$G3\" >/dev/null 2>&1; fi; "+
                    "if [ \"$G4\" = null ] || [ -z \"$G4\" ]; then settings delete secure three_finger_gesture >/dev/null 2>&1; else settings put secure three_finger_gesture \"$G4\" >/dev/null 2>&1; fi; }; "+
                    "disable3; cmd statusbar collapse >/dev/null 2>&1; "+
                    "while [ -d /proc/"+pid+" ]; do "+
                    // Infinity X exposes Settings.System.THREE_FINGER_GESTURE. Reapply it because
                    // GameSpace/SystemUI can rewrite gesture settings when focus changes.
                    "disable3; "+
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
            try{new ProcessBuilder("su","-c","cmd statusbar send-disable-flag none; cmd statusbar collapse").start().waitFor();}catch(Exception ignored){}
        },"FootDraw-RootRestore").start();
    }
}
