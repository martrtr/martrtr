package io.openai.footdraw;

import android.app.*;
import android.os.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.*;
import android.window.OnBackInvokedDispatcher;

public final class MainActivity extends Activity {
    private Store store;
    private DrawingView drawing;
    private NetClient net;
    private AudioClient audio;
    private RootKiosk kiosk;
    private RawTouchReader rawTouch;
    private GyroController gyro;
    private long lastVolDown=0;
    private boolean exiting=false;
    private boolean operatorLandscape=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        operatorLandscape=getSharedPreferences("footdraw",MODE_PRIVATE).getBoolean("operator_landscape",false);
        applyOperatorOrientation();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        store=new Store(this);
        drawing=new DrawingView(this,store);
        gyro=new GyroController(this,drawing);
        setContentView(drawing);
        getWindow().getDecorView().post(this::hideSystemUi);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{});
        AudioBoost.prepare(this);
        audio=new AudioClient(this,drawing);audio.start();
        kiosk=new RootKiosk();kiosk.start(this);
        rawTouch=new RawTouchReader(this,drawing);rawTouch.start();
        drawing.post(()->{
            net=new NetClient(this,store);
            drawing.setNetClient(net);
            net.start(Math.max(1,drawing.getWidth()),Math.max(1,drawing.getHeight()),drawing);
        });
    }

    void setOperatorLandscape(boolean land){
        operatorLandscape=land;
        getSharedPreferences("footdraw",MODE_PRIVATE).edit().putBoolean("operator_landscape",land).apply();
        applyOperatorOrientation();
    }
    private void applyOperatorOrientation(){
        setRequestedOrientation(operatorLandscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
    void setGyroEnabled(boolean on){
        runOnUiThread(()->{if(gyro!=null)gyro.setEnabled(on);});
    }
    void recalibrateGyro(){
        runOnUiThread(()->{if(gyro!=null)gyro.recalibrate();});
    }

    private void hideSystemUi(){
        try{
            View d=getWindow().getDecorView();
            if(Build.VERSION.SDK_INT>=30){
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c=d.getWindowInsetsController();
                if(c!=null){
                    c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }else d.setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }catch(Exception ignored){}
    }
    @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)hideSystemUi();}
    @Override protected void onResume(){super.onResume();applyOperatorOrientation();getWindow().getDecorView().post(this::hideSystemUi);}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);applyOperatorOrientation();getWindow().getDecorView().post(this::hideSystemUi);}
    @Override public void onBackPressed(){}
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        int k=e.getKeyCode();
        if(k==KeyEvent.KEYCODE_VOLUME_UP)return true;
        if(k==KeyEvent.KEYCODE_VOLUME_DOWN){
            if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){
                long now=SystemClock.elapsedRealtime();
                if(now-lastVolDown<=700)gracefulExit();else lastVolDown=now;
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
    private void gracefulExit(){
        if(exiting)return;exiting=true;
        if(gyro!=null)gyro.close();
        if(rawTouch!=null)rawTouch.close();
        if(kiosk!=null)kiosk.stop();
        if(net!=null)net.close();
        if(audio!=null)audio.close();
        AudioBoost.release(this);
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            finishAndRemoveTask();
            new Handler(Looper.getMainLooper()).postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),200);
        },180);
    }
    @Override protected void onDestroy(){
        if(gyro!=null)gyro.close();
        if(rawTouch!=null)rawTouch.close();
        if(net!=null)net.close();
        if(audio!=null)audio.close();
        AudioBoost.release(this);
        super.onDestroy();
    }
}
