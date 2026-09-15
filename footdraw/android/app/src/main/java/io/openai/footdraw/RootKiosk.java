package io.openai.footdraw;

import android.app.Activity;
import android.os.Process;
import java.nio.charset.StandardCharsets;

public final class RootKiosk {
    private volatile boolean active=false;private java.lang.Process watchdog;
    void start(Activity a){final int pid=Process.myPid();new Thread(()->{try{java.lang.Process check=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();String text=new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8);int rc=check.waitFor();if(rc!=0||!text.contains("uid=0"))return;active=true;String pkg=a.getPackageName();String script="cmd statusbar collapse >/dev/null 2>&1; "+"while [ -d /proc/"+pid+" ]; do "+"cmd statusbar send-disable-flag home recents statusbar-expansion quick-settings notification-peek notification-icons system-icons clock >/dev/null 2>&1; "+"F=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus); case \"$F\" in *"+pkg+"*) ;; *) am start -n "+pkg+"/.MainActivity >/dev/null 2>&1 ;; esac; sleep 0.35; done; "+"cmd statusbar send-disable-flag none >/dev/null 2>&1";watchdog=new ProcessBuilder("su","-c",script).start();}catch(Exception ignored){}},"FootDraw-RootKiosk").start();}
    void stop(){if(!active)return;active=false;new Thread(()->{try{new ProcessBuilder("su","-c","cmd statusbar send-disable-flag none; cmd statusbar collapse").start().waitFor();}catch(Exception ignored){}},"FootDraw-RootRestore").start();}
}
