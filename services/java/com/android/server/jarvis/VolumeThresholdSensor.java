package com.android.server.jarvis;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import android.os.Handler;
import android.os.Looper;

public class VolumeThresholdSensor {
    
    private double[] mHistory;
    private int mLocation;
    
    private Handler mHandler;
    private Thread mThread;
    private Timer mTimer;

    private int mUpdateInterval;
    private volatile boolean mPausing;
    private double mThreshold;
    
    private SoundMeter mSoundMeter;
    
    private OnVolumeThresholdListener mListener;
    
    public VolumeThresholdSensor() {
        mSoundMeter = new SoundMeter();
        mThread = new VolumeThread();
        mThread.start();
        mThreshold = 50;
        mHistory = null;
    }
    
    public void start() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if(mTimer == null) {
                    try {
                        mSoundMeter.start();
                    } catch (IllegalStateException ex) {
                        ex.printStackTrace();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    } finally {
                        if(mHistory == null) {
                            double amp = mSoundMeter.getAmplitude();
                            
                            mHistory = new double[10];
                            for(int i = 0;i < 10;i++) {
                                mHistory[i] = amp;
                            }
                        }
                    }
                    mTimer = new Timer();
                    mTimer.scheduleAtFixedRate(new TimerTask() {
                        @Override
                        public void run() {
                            calculateNewValue();
                        }
                    }, 10, mUpdateInterval);
                }
            }
        });
    }
    
    public void pause() {
        mPausing = true;
    }
    
    public void resume() {
        mPausing = false;
    }
    
    public void stop() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if(mTimer != null) {
                    try {
                        mSoundMeter.stop();
                    } catch (IllegalStateException ex) {
                        ex.printStackTrace();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    mTimer.cancel();
                    mTimer = null;
                }
            }
        });
    }
    
    public void setUpdateInterval(int value) {
        mUpdateInterval = value;
    }
    
    public void setThreshold(double value) {
        mThreshold = value;
    }
    
    private void calculateNewValue() {
        double amp = mSoundMeter.getAmplitude();
        
        if(!mPausing) {
            double average = getAverageAmplitude();
            if(amp >= average + mThreshold 
                    && mListener != null) {
                if(mListener.onVolumeHigherAndPause(amp))
                    pause();
                else addAmplitudeToHistory(amp);
            } else addAmplitudeToHistory(amp);
        }
    }
    
    private double getAverageAmplitude() {
        double d = 0.0;
        for(int i = 0;i < mHistory.length;i++)
            d += mHistory[i];
        return d / 10;
    }
    
    private void addAmplitudeToHistory(double value) {
        mLocation = mLocation + 1 % mHistory.length;
        mHistory[mLocation] = value;
    }
    
    public void setOnVolumeThresholdListener(OnVolumeThresholdListener lis) {
        mListener = lis;
    }
    
    private class VolumeThread extends Thread {
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new Handler();
            Looper.loop();
        }
    }
    
    public static interface OnVolumeThresholdListener {
        public boolean onVolumeHigherAndPause(double amplitude);
    }
}
