package io.openai.footdraw;

import android.content.Context;
import android.media.*;
import android.os.Build;
import java.util.List;

final class AudioBoost {
    private static AudioFocusRequest focus;
    private static int oldMode=AudioManager.MODE_NORMAL;
    private static AudioDeviceInfo oldCommDevice;

    static void prepare(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            oldMode=am.getMode();
            if(Build.VERSION.SDK_INT>=31)oldCommDevice=am.getCommunicationDevice();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if(Build.VERSION.SDK_INT>=31){
                List<AudioDeviceInfo> ds=am.getAvailableCommunicationDevices();
                AudioDeviceInfo best=null;
                for(AudioDeviceInfo d:ds){
                    int t=d.getType();
                    if(t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t==AudioDeviceInfo.TYPE_BLE_HEADSET){best=d;break;}
                    if(best==null&&(t==AudioDeviceInfo.TYPE_WIRED_HEADSET||t==AudioDeviceInfo.TYPE_USB_HEADSET))best=d;
                }
                if(best!=null)try{am.setCommunicationDevice(best);}catch(Throwable ignored){}
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

    static void release(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            if(focus!=null)am.abandonAudioFocusRequest(focus);
            if(Build.VERSION.SDK_INT>=31){
                if(oldCommDevice!=null)try{am.setCommunicationDevice(oldCommDevice);}catch(Throwable ignored){}
                else try{am.clearCommunicationDevice();}catch(Throwable ignored){}
            }else{
                try{am.setBluetoothScoOn(false);am.stopBluetoothSco();}catch(Throwable ignored){}
            }
            am.setMode(oldMode);
        }catch(Throwable ignored){}
        focus=null;oldCommDevice=null;oldMode=AudioManager.MODE_NORMAL;
    }
}
