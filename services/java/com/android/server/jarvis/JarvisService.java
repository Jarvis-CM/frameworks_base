/*
 * Copyright (C) 2014 Firtecy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.jarvis;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.android.internal.policy.*;
import com.android.server.jarvis.GrammarRecognizer.GrammarMap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.jarvis.JarvisConstants;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

/* ToDo list:
 * TODO: make jarvis a service registered in service manager
 * TODO: implement a better main thread (to generate proper Handlers)
 * TODO: make more settings
 */

/**
 * The Jarvis Service that runs in the system server. 
 * NOTICE! If any type of Exception occurs and it is not catched then we will end up in 
 * a bootloop so catch every Exception and Log them!
 * @author Firtecy
 */
public class JarvisService {
    private static final boolean DEBUG = true;
    //Disabled for now because it is causing too much problems
    private static final boolean ENABLE_TOAST = false;
    private static final String TAG = "JarvisService";

    private static final int SHAKE_THRESHOLD = 800;
    private static final int AMPLITUDE_THRESHOLD = 40;
    private static final int SERVICE_NOT_READY_TIMEOUT = 45;
    private static final int MIN_CONFIDENCE = 250;

    /** That is my own support package */
    private static final String PACKAGE_NAME = "de.firtecy.jarvisbasic";
    /** and the connected service */
    private static final String PACKAGE_SERVICE_NAME = "de.firtecy.jarvisbasic.JarvisService";
    
    private static final String DEFAULT_NAME = "Jarvis";
    private static final String DEFAULT_SLOT = "@Name";
    private static final String BASE_G2G_FILE = "/system/usr/srec/config/en.us/grammars/grammar_base.g2g";
    private static final String FALLBACK_G2G_FILE = "/system/usr/srec/config/en.us/grammars/VoiceDialer.g2g";
    //Now all properties have finished

    private static ComponentName sServiceComponentName;
    public static final ComponentName getServiceComponentName() {
        return sServiceComponentName;
    }
    
    private SensorEventListener mShakeDetector = new SensorEventListener() {
        private float lastX, lastY, lastZ;
        private long lastUpdate;

        @Override
        public void onAccuracyChanged (Sensor sensor, int accury) {
            //Nothing...
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                long curTime = System.currentTimeMillis();
                // only allow one update every 100ms. Otherwise it would be too much
                if ((curTime - lastUpdate) > 100) {
                    long diffTime = (curTime - lastUpdate);
                    lastUpdate = curTime;

                    float x = event.values[SensorManager.DATA_X];
                    float y = event.values[SensorManager.DATA_Y];
                    float z = event.values[SensorManager.DATA_Z];

                    float speed = Math.abs(x+y+z - (lastX + lastY + lastZ)) / diffTime * 10000;

                    if (speed > mShakeThreshold) {
                        onShake();
                    }
                    lastX = x;
                    lastY = y;
                    lastZ = z;
                }
            }
        }
    };
    
    private GrammarRecognizer.GrammarListener mListener = new GrammarRecognizer.GrammarListener() {
        @Override
        public void onRecognitionSuccess(ArrayList<Bundle> results) {
            mStoppedListening = true;
            try {
                Bundle result = results.get(0);
                log("Got something: " + result.getString(GrammarRecognizer.KEY_LITERAL) + " -> " + result.getString(GrammarRecognizer.KEY_MEANING) + " : " + result.getString(GrammarRecognizer.KEY_CONFIDENCE));
                //Strings are in format: 0[literal] 
                //                       1[meaning]
                //                       2[confidence]
                ArrayList<String>list = new ArrayList<String>();
                list.add(result.getString(GrammarRecognizer.KEY_LITERAL, ""));
                list.add(result.getString(GrammarRecognizer.KEY_MEANING, ""));
                list.add(result.getString(GrammarRecognizer.KEY_CONFIDENCE, ""));
                //Check if the confidence
                int con = Integer.parseInt(list.get(2));
                if(con > MIN_CONFIDENCE) {
                    if(mChannel != null && mChannel.isConnected())
                        mChannel.sendString(list);
                } else {
                    log("Confidence was too low to be a valid result confidence='" + con + "'");
                }
                if(ENABLE_TOAST)
                    Toast.makeText(mContext, "Got something: " + result.getString(GrammarRecognizer.KEY_LITERAL), Toast.LENGTH_SHORT);
            } catch (Exception ex) {
                log("Failed to parse result of recognizing.", ex);
            }
            if(mListenEx) {
                listen(false);
            }
        }
        
        @Override
        public void onRecognitionFailure() {
            mStoppedListening = true;
            if(mListenEx) {
                listen(false);
            }
        }
        
        @Override
        public void onRecognitionError(String reason) {
            mStoppedListening = true;
            log("Failed to recognize because of: " + reason);
            if(mListenEx) {
                listen(false);
            }
        }
    };
    
    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.JARVIS_SERVICE_KEYS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.JARVIS_SERVICE_LISTEN_WAKE_UP), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.JARVIS_SERVICE_SHAKE_THRESHOLD), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mShakeThreshold =
                    Settings.System.getInt(mContext.getContentResolver(), Settings.System.JARVIS_SERVICE_SHAKE_THRESHOLD, SHAKE_THRESHOLD);
            mOnShakeListen = mShakeThreshold > 0; 
            mListenMode =
                    Settings.System.getInt(mContext.getContentResolver(), Settings.System.JARVIS_SERVICE_LISTEN_WAKE_UP, 3);
            mListenSOn = (mListenMode & 1) == 1;
            mListenP = (mListenMode & 2) == 2;
            mListenEverytime = (mListenMode & 4) == 4;
            String service =
                    Settings.System.getString(mContext.getContentResolver(), Settings.System.JARVIS_SERVICE_KEYS);
            if(service != null) {
                String[] c = service.split(";");
                if(c.length == 2) {
                    mServicePackage = c[0];
                    mServiceName = c[1];
                }
            }
            if (!selfChange) {
                resetSettings();
            }
        }
    }

    private Context mContext;
    private Thread mMainThread;

    private boolean mSRunning;
    private boolean mListenEx;
    private SoundMeter mSMeter;
    private SettingsObserver mObserver;
    
    private IJarvisPolicy mPolicy;
    private boolean mStoppedListening;
    private Sensor mAccelerometer;
    
    private AppChannel mChannel;
    
    private GrammarRecognizer mRecognizer;
    private Thread mVolumeThread;
    private boolean mVolume;
    private GrammarMap mGrammar;
    private Handler mMainHandler;
    private Vibrator mVibrator;
    
    //Now the specifi settings
    private boolean mOnShakeListen;
    private int mListenMode;
    private boolean mListenSOn, mListenP, mListenEverytime;
    private String mServicePackage;
    private String mServiceName;
    private int mShakeThreshold;
    private int mAmplitudeThreshold;
    private long mLastChange;
    private SharedPreferences mPreferences;
    private ActionProvider mAProvider;
    private boolean mReady;

    private static final void log(String s) {
        if(DEBUG)
            Log.i(TAG, s);
    }
    
    private static final void log(String s, Throwable t) {
        if(DEBUG)
            Log.w(TAG, s, t);
    }
    
    public JarvisService(Context con, Handler wmHandler) {
        mContext = con;
        mStoppedListening = true;
        mChannel = null;
        mMainHandler = null;
        mGrammar = new GrammarMap();
        try {
            mRecognizer = new GrammarRecognizer(con);
            mRecognizer.setListener(mListener);
        } catch (IOException ex) {
            Log.e(TAG, "Failed to generate GrammarRecognizer", ex);
        }
        log("Created Jarvis Service");
        mListenEx = false;
        mMainThread = new WorkerThread("JarvisServiceThread");
        mAProvider = new ActionProvider(con, this);
        
        //Init the settings:
        mOnShakeListen = true;
        mListenEverytime = false;
        mListenSOn = false;
        mListenP = false;
        mServicePackage = PACKAGE_NAME;
        mServiceName = PACKAGE_SERVICE_NAME;
        mShakeThreshold = SHAKE_THRESHOLD;
        mAmplitudeThreshold = AMPLITUDE_THRESHOLD;
        mPreferences = mContext.getSharedPreferences(DEFAULT_NAME, 0);
        mLastChange = mPreferences.getLong("last_checked_words", -1);
        mReady = false;
    }
    
    /**
     * Call this when system server is ready
     */
    public void systemReady() {
        log("System Server is ready, now start the main thread");
        mMainThread.start();
    }
    
    //TODO: make a better implementation
    /**
     * Currently disabled because it needs a better implementation
     *
     */
    private void startVolumeThread() {
        if(mVolumeThread != null)
            stopVolumeThread();
        mVolume = true;
        mVolumeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(mVolume && !Thread.interrupted()) {
                    if(mSMeter.getAmplitude() > mAmplitudeThreshold) {
                        log("Volume was higher then: " + mAmplitudeThreshold + " it was :" + mSMeter.getAmplitude());
                        listen(true);
                    }
                }
            }
        });
        mVolumeThread.start();
    }
    
    private void stopVolumeThread() {
        mVolume = false;
        if(mVolumeThread != null)
            mVolumeThread.interrupt();
        mVolumeThread = null;
    }
    
    /**
     * If one of the settings changed call this.
     * It will reset all settings
     */
    private void resetSettings() {
        boolean newService = false;
        if(mChannel != null) {
            ComponentName c = mChannel.getComponentName();
            if(!(c.getPackageName().equals(mServicePackage) 
                    && c.getClassName().equals(mServiceName)) 
                    && isAppInstalled(mServicePackage)) {
                newService = true;
                try {
                    mChannel.disconnect();
                } catch (Exception ex) {
                    log("Failed to disconnect from service.",ex);
                }
                mChannel = null;
            }
        }
        //Go through the events to reset settings and receivers
        if(wasScreenOn) onScreenOn();
        else onScreenOff();
        if(isCharging)onPowerConnect();
        else onPowerDisconnect();
        
        //Now connected to new service
        if(newService && init()) {
            log("Successful switched to new service.");
        }
    }

    private void prepare() {
        if(!JarvisFileUtils.accessJarvisLocation())
            log("No write permission ot Jarvis Location");
        
        //Look for settings changes
        mObserver = new SettingsObserver(mMainHandler);
        mObserver.observe();
        mObserver.onChange(false);
        
        //Used for detection
        mAccelerometer = ((SensorManager)mContext.getSystemService(Context.SENSOR_SERVICE)).getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mVibrator = (Vibrator)mContext.getSystemService(Context.VIBRATOR_SERVICE);
        
        if(isAppInstalled(mServicePackage)) {
            //We have the support app installed so proceed
            log("Service package is installed. package=" + mServicePackage);
            if(init())
                return;//We succeded and shouldn't continue
        }
        
        //This means there is no package installed 
        log("Service package is not installed. package=" + mServicePackage);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        //intentFilter.addAction(Intent.ACTION_PACKAGE_INSTALL);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(isAppInstalled(mServicePackage)) {
                    if(init())//If it was successful we can remove the receiver
                        mContext.unregisterReceiver(this);
                }
            }
        }, intentFilter);
    }
    
    /**
     * Get the Base Grammar file to compile new grammar files.
     * This will copy a valid precompiled grammar to the data partition.
     * @return File the grammar file to use as a bases
     */
    private File getBase() {
        File base = JarvisFileUtils.getBaseGrammarFile();
        if(!base.exists()) {
            try {
                //Get a base grammar file
                File orig = new File(BASE_G2G_FILE);
                
                //The fallback grammar file will cause issues and wrong results
                //TODO: Search for a better replacement
                /*if(!orig.exists()) {
                    log("No file in default location. Going back to fallback.");
                    orig = new File(FALLBACK_G2G_FILE);
                }*/
                
                InputStream in = new FileInputStream(orig);
                FileOutputStream out = new FileOutputStream(base);
                
                byte[] buffer = new byte[1024];
                int count = 0;
                
                while ((count = in.read(buffer)) > 0)
                    out.write(buffer, 0, count);
                in.close();
                out.close();
            } catch (IOException e) {
                log("Failed to copy base grammar to data.", e);
                return null;
            }
        }
        return base;
    }

    private boolean init() {
        mPolicy = PolicyManager.getJarvisPolicy(mContext);
        mPolicy.assignSystemService(this);
        
        File base = getBase();

        //Now start binding with the service
        log("Connecting to service " + mServiceName + " in package " + mServicePackage);
        mChannel = new AppChannel(mContext, mServicePackage, mServiceName, mMainHandler);
        if(!isCallable(mChannel.getIntent())) {
            log("A connection to the service won't work. Cancel.");
            return false;
        }
        
        try {
            mChannel.connect();
        } catch (SecurityException e) {
            log("Failed to connect to service. Cancel.", e);
            return false;
        }
        
        try {
            final long start = SystemClock.uptimeMillis();
            //Wait till it is ready, but kill it when it needs to long. > SERVICE_NOT_READY_TIMEOUT s
            while(!mChannel.isReady()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex){}
                if((SystemClock.uptimeMillis() - start) > SERVICE_NOT_READY_TIMEOUT * 1000) {
                    mChannel.disconnect();
                    mChannel = null;
                    throw new RemoteException("Service took to long to get ready. Over " + SERVICE_NOT_READY_TIMEOUT + " s.");
                }
            }
            log("Now connected to service that is ready. (Took " + (SystemClock.uptimeMillis() - start) + " ms)");
            sServiceComponentName = mChannel.getComponentName();
            log("Service targets API: " + mChannel.getTargetApi() + " framework supports " + Build.JARVIS_VERSION);
        } catch (RemoteException ex) {
            log("Catched a remote exception while waiting till Service is ready. Cancel.", ex);
            return false;
        } catch (SecurityException e) {
            log("Failed to connect to service. Cancel.", e);
            return false;
        } catch (Throwable t) {
            //Just to be sure. We don't want to end in a bootloop
            log("Failed to connect to the service. Cancel", t);
        }
        
        if(!lookForUpdate(base))
            return false;
        
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        BroadcastReceiver mReceiver = new SettingsReceiver();
        mContext.registerReceiver(mReceiver, filter);

        mSMeter = new SoundMeter();
        mReady = true;
        onScreenOn();
        return true;
    }
    
    private boolean lookForUpdate(File base) {
        File out = JarvisFileUtils.getSrecGrammarFile(mServiceName);
        //The last update of words from the service
        long last = mChannel.getLastUpdate();
        
        final int length = mChannel.getCountWords();
        int num = mPreferences.getInt("last_count", -1);
        
        boolean update = !out.exists() || mLastChange < last || length != num;
        
        if(update) {//If we need an update add all words again
            mPreferences.edit().putLong("last_checked_words", last).commit();
            //Predefined words
            mGrammar.addWord(DEFAULT_SLOT, DEFAULT_NAME, null, 1, "V='Jarvis'");
            mGrammar.addWord(DEFAULT_SLOT, "Okay", null, 1, "V='ok'");
            mGrammar.addWord(DEFAULT_SLOT, "Ok", null, 1, "V='ok'");
            mGrammar.addWord(DEFAULT_SLOT, "Okay " + DEFAULT_NAME, null, 1, "V='Jarvis'");
            log("Now fetching words from service.");
            final long start = SystemClock.uptimeMillis();
            num = 0;
            int i = 0;
            //Loop through all words from service
            for(;i < length;i++) {
                try {
                    String t = mChannel.getWordAt(i);
                    if(t == null || t.length() == 0) {
                        log("Word at position " + i + " had no content. Skipping. " + t);
                        continue;
                    }
                    String[] s = t.split(";");
                    //Stored as: [slot];[word];[pron];[literal];[priority]
                    if(s.length == 5 && s[1] != null && s[1].length() > 0) {
                        String word = s[1];
                        String pron = s[2];
                        if(pron != null && pron.length() == 0)
                            pron = null;
                        String literal = "V='";
                        if(s[3] != null && s[3].length() > 0)
                            literal += s[3];
                        else literal += word.toLowerCase();
                        literal += "'";
                        mGrammar.addWord(DEFAULT_SLOT, word, pron, 1, literal);
                        log("Added word: " + word + " from '" + t + "'");
                        num++;
                    } else {
                        log("Word at position " + i + " had wrong format('" + t + "') Skipping.");
                    }
                } catch (RemoteException ex) {
                    log("Failed to fetch word at: " + i, ex);
                } catch (NullPointerException ex) {
                    log("Failed to fetch word at: " + i, ex);
                } catch (IndexOutOfBoundsException ex) {
                    log("Failed to fetch word at: " + i, ex);
                }
            }
            mPreferences.edit().putInt("last_count", num).commit();
            log("Fetched " + num + " words from total count of " + i + ". (Took " + (SystemClock.uptimeMillis() - start) + " ms)");
        }
        try {
            if(update)
                mRecognizer.compileGrammar(mGrammar, base, out);
            mRecognizer.loadGrammar(out);
            log("Saved grammar to " + out.getAbsolutePath());
        } catch (Exception ex) {
            log("Failed to compile command grammar.", ex);
            return false;
        }
        return true;
    }

    /**
     * Call this to start listening. When the listening is not 
     * finished yet this will do nothing.
     * @param If we should vibrate to send feedback
     */
    private synchronized void listen(boolean v) {
        if(mStoppedListening && mReady) {
            if(v)mVibrator.vibrate(200);
            mStoppedListening = false;
            mRecognizer.recognize();
        }
    }
    
    private synchronized void listen() {
        listen(true);
    }

    /**
     * Call this to interrupt listening. If the service doesn't listen, it does nothing.
     */
    private synchronized void stop() {
        if(!mStoppedListening && mReady) {
            mRecognizer.stop();
        }
    }
    
    /**
     * When we receive a query from the service, here it get handled
     */
    private void queryAction(final int action, Bundle data) {
        try {
            switch(action) {
                case JarvisConstants.TOGGLE_LAST_APP:
                    mAProvider.toggleLastApp();
                    break;
            }
        } catch (Throwable t) {
            log("Failed to execute action " + action, t);
        }
    }
    
    /**
     * Check if the given Intent is a callable service
     * @param intent the intent to check
     * @return whether it is callable
     */
    private boolean isCallable(Intent intent) {
         List<ResolveInfo> list = mContext.getPackageManager().queryIntentServices(intent, 0);
         return list.size() > 0;
    }

    /**
     * Check if the given package is installed or not.
     * @param uri the package uri
     * @return whether the app is installed or not
     */
    private boolean isAppInstalled(String uri) {
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private void enableListenEverytime(boolean t) {
        mListenEx = t;
        listen();
    }
    
    //Events that can occur
    
    private void onScreenOff() {
        if(mListenP && isCharging) {
            enableListenEverytime(true);
        } else if (mListenEverytime) {
            enableListenEverytime(true);
        } else if(mListenSOn) {
            enableListenEverytime(false);
        }
        if(mOnShakeListen)
            enableShakeListener(isCharging);
    }

    private void onScreenOn() {
        if(mListenSOn)
            enableListenEverytime(true);
        if(mOnShakeListen)
            enableShakeListener(true);
    }
    
    private void onPowerDisconnect () {
        log("Power disconnected. Checking modes.");
        if ((wasScreenOn && mListenSOn) || mListenEverytime) {
            enableListenEverytime(true);
        } else {
            enableListenEverytime(false);
        }
    }
    
    private void onPowerConnect() {
        log("Power connected. Checking modes.");
        if (mListenP) {
            enableListenEverytime(true);
        }
        if(mOnShakeListen)
            enableShakeListener(true);
    }

    private void onShake() {
        listen();
    }

    private void enableShakeListener(boolean b) {
        SensorManager sensorMgr = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        //Remove the sensor listener if not needed, it will only keep the device active
        if(b && !mSRunning && mOnShakeListen) {
            sensorMgr.registerListener(mShakeDetector, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else if (!b && mSRunning) {
            sensorMgr.unregisterListener(mShakeDetector);
        }
    }

    public static boolean wasScreenOn = true;
    public static boolean isCharging = false;

    public class SettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                onScreenOff();
                wasScreenOn = false;
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                onScreenOn();
                wasScreenOn = true;
            } else if (intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
                isCharging = true;
                onPowerConnect();
            } else if (intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
                isCharging = false;
                onPowerDisconnect();
            }
        }
    }

    /**
     * handler for Jarvis. Used for AppChannel conection
     */
    static final class JarvisHandler extends Handler {
        private final JarvisService mService;
        public JarvisHandler(Looper looper, JarvisService s) {
            super(looper, null, true /*async*/);
            mService = s;
        }

        @Override 
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AppChannel.BUMP_UPDATE_WORDS:
                    mService.lookForUpdate(mService.getBase());
                    break;
                case AppChannel.BUMP_LISTEN:
                    boolean b = (msg.obj instanceof Boolean 
                            && ((Boolean)msg.obj).booleanValue());
                    mService.listen(b);
                    break;
                case AppChannel.BUMP_STOP:
                    mService.stop();
                    break;
                case AppChannel.BUMP_ACTION_QUERIED:
                    mService.queryAction(msg.arg1, (Bundle)msg.obj);  
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private final class WorkerThread extends Thread {

        private Handler mWorkerHandler;

        public WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Looper.prepare();
            mWorkerHandler = new Handler();
            mWorkerHandler.post(new Runnable(){
                @Override
                public void run() {
                    new JarvisHandler(mWorkerHandler.getLooper(), JarvisService.this);
                    prepare();
                }
            });
            Log.i(TAG, "Prepared Looper for Jarvis Service");
            Looper.loop();
        }
    }
}
