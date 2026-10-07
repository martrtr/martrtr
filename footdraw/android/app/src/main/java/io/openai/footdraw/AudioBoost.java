package io.openai.footdraw;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.util.Log;
import java.util.List;

final class AudioBoost {
    private static final String TAG="FootDraw-AudioRoute";
    private static AudioFocusRequest focus;
    private static int oldMode=AudioManager.MODE_NORMAL;
    private static AudioDeviceInfo oldCommDevice;
    private static AudioManager manager;
    private static AudioDeviceCallback deviceCallback;

    private static int rank(AudioDeviceInfo d){
        if(d==null)return 0;
        switch(d.getType()){
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return 100;
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return 95;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return 90;
            case AudioDeviceInfo.TYPE_USB_HEADSET: return 85;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return 20;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return 10;
            default:return 1;
        }
    }

    static AudioDeviceInfo bestInput(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return null;
            AudioDeviceInfo best=null;
            for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_INPUTS)){
                int t=d.getType();
                if(t!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO && t!=AudioDeviceInfo.TYPE_BLE_HEADSET
                        && t!=AudioDeviceInfo.TYPE_WIRED_HEADSET && t!=AudioDeviceInfo.TYPE_USB_HEADSET)
                    continue;
                if(best==null||rank(d)>rank(best))best=d;
            }
            return best;
        }catch(Throwable ignored){return null;}
    }

    private static void selectCommunicationDevice(AudioManager am){
        if(am==null||Build.VERSION.SDK_INT<31)return;
        try{
            List<AudioDeviceInfo> ds=am.getAvailableCommunicationDevices();
            AudioDeviceInfo best=null;
            for(AudioDeviceInfo d:ds){
                if(best==null||rank(d)>rank(best))best=d;
            }
            if(best!=null){
                am.setCommunicationDevice(best);
                Log.i(TAG,"communication device="+best.getType()+" "+best.getProductName());
            }
        }catch(Throwable e){Log.w(TAG,"communication route",e);}
    }

    static synchronized void prepare(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            manager=am;
            oldMode=am.getMode();
            if(Build.VERSION.SDK_INT>=31)oldCommDevice=am.getCommunicationDevice();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if(Build.VERSION.SDK_INT>=31){
                selectCommunicationDevice(am);
                if(deviceCallback==null){
                    deviceCallback=new AudioDeviceCallback(){
                        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added){selectCommunicationDevice(manager);}
                        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){selectCommunicationDevice(manager);}
                    };
                    am.registerAudioDeviceCallback(deviceCallback,null);
                }
            }else{
                try{am.startBluetoothSco();am.setBluetoothScoOn(true);}catch(Throwable ignored){}
            }

            AudioAttributes attrs=new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(change -> {}).build();
            am.requestAudioFocus(focus);
        }catch(Throwable ignored){}
    }

    static synchronized void release(Context context){
        try{
            AudioManager am=manager!=null?manager:(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            if(deviceCallback!=null){
                try{am.unregisterAudioDeviceCallback(deviceCallback);}catch(Throwable ignored){}
                deviceCallback=null;
            }
            if(focus!=null)am.abandonAudioFocusRequest(focus);
            if(Build.VERSION.SDK_INT>=31){
                if(oldCommDevice!=null)try{am.setCommunicationDevice(oldCommDevice);}catch(Throwable ignored){}
                else try{am.clearCommunicationDevice();}catch(Throwable ignored){}
            }else{
                try{am.setBluetoothScoOn(false);am.stopBluetoothSco();}catch(Throwable ignored){}
            }
            am.setMode(oldMode);
        }catch(Throwable ignored){}
        focus=null;oldCommDevice=null;oldMode=AudioManager.MODE_NORMAL;manager=null;
    }
}
