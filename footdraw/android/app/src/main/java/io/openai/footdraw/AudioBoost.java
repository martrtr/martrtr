package io.openai.footdraw;

import android.content.Context;
import android.media.*;

final class AudioBoost {
    private static AudioFocusRequest focus;
    private static int oldVolume=-1;
    private static boolean volumeChanged=false;

    static void prepare(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            AudioAttributes attrs=new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(change -> {}).build();
            am.requestAudioFocus(focus);
            oldVolume=am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if(oldVolume<=0 && max>0){
                am.setStreamVolume(AudioManager.STREAM_MUSIC,Math.max(1,(int)Math.ceil(max*0.6)),0);
                volumeChanged=true;
            }
        }catch(Exception ignored){}
    }

    static void release(Context context){
        try{
            AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(am==null)return;
            if(volumeChanged&&oldVolume>=0)am.setStreamVolume(AudioManager.STREAM_MUSIC,oldVolume,0);
            if(focus!=null)am.abandonAudioFocusRequest(focus);
        }catch(Exception ignored){}
        volumeChanged=false;oldVolume=-1;focus=null;
    }
}
