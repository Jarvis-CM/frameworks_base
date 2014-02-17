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
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IJarvisService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.jarvis.JarvisConstants;
import android.util.Log;

/* ToDo list:
 * TODO: make more settings
 * TODO: Fix
 * E/SharedPreferencesImpl( 2197): Couldn't create directory for SharedPreferences file shared_prefs/Jarvis.xml
 * E/SharedPreferencesImpl( 2197): Couldn't create directory for SharedPreferences file shared_prefs/Jarvis.xml
 */

/**
 * The Jarvis Service that runs in the system server. 
 * NOTICE! If any type of Exception occurs and it is not catched then we will end up in 
 * a soft reboot or bootloop so catch every Exception and log them only!
 * @author Firtecy
 */
public class JarvisService extends IJarvisService.Stub {
    private static final boolean DEBUG = true;
    private static final String TAG = "JarvisService";

    private static final int SHAKE_THRESHOLD = 800;
    /** in seconds */
    private static final int SERVICE_NOT_READY_TIMEOUT = 45;
    private static final int MIN_CONFIDENCE = 500;

    /** That is my own support package */
    private static final String PACKAGE_NAME = "de.firtecy.jarvisbasic";
    /** and the connected service */
    private static final String PACKAGE_SERVICE_NAME = "de.firtecy.jarvisbasic.JarvisService";
    
    private static final String DEFAULT_NAME = "Jarvis";
    private static final String DEFAULT_SLOT = "@Name";
    private static final String BASE_G2G_FILE = "/system/usr/srec/config/en.us/grammars/grammar_base.g2g";
    //Now all properties have finished

    public static enum State {
        DISABLED,
        IDLE,
        LISTENING
    }

    private static ComponentName sServiceComponentName;
    public static final ComponentName getServiceComponentName() {
        return sServiceComponentName;
    }
    
    private SensorEventListener mShakeDetector = new SensorEventListener() {
        private float lastX, lastY, lastZ;
        private long lastUpdate;

        @Override
        public void onAccuracyChanged (Sensor sensor, int accury) { }

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
            try {
                Bundle result = results.get(0);
                log("Got something: " + result.getString(GrammarRecognizer.KEY_LITERAL) + " -> " + result.getString(GrammarRecognizer.KEY_MEANING) + " : " + result.getString(GrammarRecognizer.KEY_CONFIDENCE));

                //First check for the right state. If we are in idle mode we will send the results directly to the service
                if(mState != State.IDLE) {
                    //Strings are in format: 0[literal]
                    //                       1[meaning]
                    //                       2[confidence]
                    ArrayList<String>list = new ArrayList<String>();
                    list.add(result.getString(GrammarRecognizer.KEY_LITERAL, ""));
                    list.add(result.getString(GrammarRecognizer.KEY_MEANING, ""));
                    list.add(result.getString(GrammarRecognizer.KEY_CONFIDENCE, ""));

                    //Check if the confidence is higher than the min confidence
                    int con = Integer.parseInt(list.get(2));
                    if(con > MIN_CONFIDENCE) {
                        if(mChannel != null && mChannel.isConnected())
                            mChannel.sendString(list);
                    } else {
                        log("Confidence was too low to be a valid result confidence='" + con + "'");
                    }
                } else {
                    String meaning = result.getString(GrammarRecognizer.KEY_MEANING, "");
                    if(meaning.equals(DEFAULT_NAME)) {
                        moveToState(State.LISTENING, null);

                        //Parse again to make sure the service gets notified as-well
                        onRecognitionSuccess(results);
                    }
                }
            } catch (Exception ex) {
                log("Failed to parse result of recognizing.", ex);
            }
            resetListening();
        }

        @Override
        public void onRecognitionFailure() {
            resetListening();
        }

        @Override
        public void onRecognitionError(String reason) {
            resetListening();
            log("Failed to listen: " + reason);
        }

        private void resetListening() {
            if(mState == State.LISTENING) listen(false);
            mIsListening = false;
        }

        @Override
        public void onVoiceEnded() {
            //Will be used later
        }
    }; //End of grammar listener
    
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
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.JARVIS_SERVICE_LISTEN_LOCK), false, this);
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
            int m = Settings.System.getInt(mContext.getContentResolver(), Settings.System.JARVIS_SERVICE_LISTEN_LOCK, 1);
            switch(m) {
                case 0: moveToState(State.LISTENING, null); break;
                case 1: moveToState(State.IDLE, null); break;
                case 2: moveToState(State.DISABLED, null); break;
            }
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

    private SettingsObserver mObserver;
    
    private IJarvisPolicy mPolicy;
    private Sensor mAccelerometer;
    private BroadcastReceiver mReceiver;
    
    private AppChannel mChannel;
    
    private GrammarRecognizer mRecognizer;
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
    private long mLastChange;
    private SharedPreferences mPreferences;
    private ActionProvider mAProvider;
    private State mState;
    private boolean mIsListening;
    private long mBlockTill;

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
        mState = State.DISABLED;
        mChannel = null;
        mMainHandler = null;
        mGrammar = new GrammarMap();
        //Used for detection
        mAccelerometer = ((SensorManager)mContext.getSystemService(Context.SENSOR_SERVICE))
                .getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        try {
            mRecognizer = new GrammarRecognizer(con);
            mRecognizer.setListener(mListener);
        } catch (IOException ex) {
            Log.e(TAG, "Failed to generate GrammarRecognizer", ex);
        }
        log("Created Jarvis Service");
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
        mPreferences = mContext.getSharedPreferences(DEFAULT_NAME, 0);
        mLastChange = mPreferences.getLong("last_checked_words", -1);
    }

    protected void moveToState(State s, Bundle data) {
        switch(s) {
            case DISABLED:
                log("Moving to state disabled.");
                enableListenEverytime(false);
                enableShakeListener(false);
                break;
            case IDLE:
                log("Moving to state idle.");
                enableListenEverytime(false);
                enableShakeListener(true);
                break;
            case LISTENING:
                log("Moving to state listening");
                enableListenEverytime(true);
                enableShakeListener(true);
                break;
        }
        mState = s;
    }
    
    /**
     * Call this when system server is ready
     */
    public void systemReady() {
        log("System Server is ready, now start the main thread");
        mMainThread.start();
    }
    
    /**
     * If one of the settings changed call this.
     * It will reset all settings
     */
    private void resetSettings() {
        log("Resetting settings.");
        boolean newService = false;
        if(mChannel != null) {
            ComponentName c = mChannel.getComponentName();
            if(!(c.getPackageName().equals(mServicePackage) 
                    && c.getClassName().equals(mServiceName)) 
                    && isAppInstalled(mServicePackage)) {
                newService = true;
                log("Disconnected from service.");
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
        if(isCharging) onPowerConnect();
        else onPowerDisconnect();

        //Now connected to new service
        if(newService && init()) {
            log("Successful switched to new service.");
        }
    }

    private void prepare() {
        if(!JarvisFileUtils.accessJarvisFileLocation())
            log("No write permission to Jarvis File Location.");

        mVibrator = (Vibrator)mContext.getSystemService(Context.VIBRATOR_SERVICE);

        //Look for settings changes
        mObserver = new SettingsObserver(mMainHandler);
        mObserver.observe();
        mObserver.onChange(false);
        
        if(isAppInstalled(mServicePackage)) {
            //We have the support app installed so proceed
            log("Service package is installed. package=" + mServicePackage);
            if(init())
                return;//We succeded and shouldn't continue
        }
        
        //This means there is no package installed. So look if we get a compatible package
        log("Service package is not installed. package=" + mServicePackage);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
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
    private File getBaseOrCopy() {
        File base = JarvisFileUtils.getBaseGrammarFile();
        if(!base.exists()) {
            try {
                //Get a base grammar file
                File orig = new File(BASE_G2G_FILE);
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
        
        File base = getBaseOrCopy();

        //Now start binding with the service
        log("Connecting to service " + mServiceName + " in package " + mServicePackage);
        mChannel = new AppChannel(mContext, mServicePackage, mServiceName, mMainHandler);
        if(!isCallableService(mChannel.getIntent())) {
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
            //Wait till the service is ready, but kill it when it needs to long. > SERVICE_NOT_READY_TIMEOUT in s
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
        } catch (Exception ex) {
            log("Catched a remote exception while waiting till Service is ready. Cancel.", ex);
            return false;
        }
        
        if(!lookForUpdate(base))
            return false;
        
        if(mReceiver == null) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            mReceiver = new SettingsReceiver();
            mContext.registerReceiver(mReceiver, filter);
        }
        onScreenOn();
        return true;
    }
    
    private boolean lookForUpdate(File base) {
        File out = JarvisFileUtils.getSrecGrammarFile(mServiceName);
        //The last update of words from the service
        long last = mChannel.getLastUpdate();
        
        final int length = mChannel.getCountWords();
        int num = mPreferences.getInt("last_count", -1);
        
        boolean update = mLastChange < last || length != num;
        if(!update && out.exists()) log("We don't have to update.");
        else update = true;
        
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

                        //Now add it to the grammar
                        mGrammar.addWord(DEFAULT_SLOT, word, pron, 5, literal);
                        log("Added word: " + word + " from '" + t + "'");
                        num++;
                    } else {
                        log("Word at position " + i + " had wrong format('" + t + "') Skipping.");
                    }
                } catch (Exception ex) {
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
     * Call this to start listening. When the listening has not 
     * finished yet this will do nothing.
     * @param boolean If we should vibrate to send feedback
     */
    private synchronized void listen(boolean v) {
        if(mState != State.DISABLED && !isBlocked() && !mIsListening) {
            if(v)mVibrator.vibrate(200);
            mIsListening = true;
            mRecognizer.recognize();
        } else log("Was not able to start listening.");
    }
    
    private synchronized void listen() {
        listen(true);
    }

    /**
     * Call this to interrupt listening. If the service doesn't listen, this will do nothing.
     */
    private synchronized void stop() {
        if(mIsListening && mRecognizer != null) {
            mRecognizer.stop();
        }
        if(mState != State.DISABLED) {
            moveToState(State.IDLE, null);
        }
    }
    
    /**
     * When we receive a query from the service, this method will handle the query
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

    //TODO: implement v
    public boolean hasJarvisService() {
         return true;
    }

    public int getStatus(int uid, String packageName, IBinder token) {
        return 0;
    }

    public void doAction(int uid, String packageName, long code, Bundle data, IBinder token) {

    }
    //TODO: implement ^

    /**
     * Check if the given Intent is a callable service
     * @param Intent the intent to check
     * @return boolean whether it is callable or not
     */
    private boolean isCallableService(Intent intent) {
         return mContext.getPackageManager().queryIntentServices(intent, 0).size() > 0;
    }

    /**
     * Check if the given package is installed or not.
     * @param String uri the package uri
     * @return boolean whether the app is installed or not
     */
    private boolean isAppInstalled(String uri) {
        try {
            mContext.getPackageManager().getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private void enableListenEverytime(boolean t) {
        mState = State.LISTENING;
        listen(false);
    }
    
    //Events that can occur
    
    private void onScreenOff() {
        log("Turnend screen off.");
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
        log("Turnend screen on.");
        if(mListenSOn)
            enableListenEverytime(true);
        if(mOnShakeListen)
            enableShakeListener(true);
    }
    
    private void onPowerDisconnect() {
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

    private boolean isBlocked() {
        return mBlockTill > SystemClock.uptimeMillis(); 
    }

    private void setBlock(long till) {
        log("Block queried till=" + till + " now we are at " + SystemClock.uptimeMillis());
        mBlockTill = till;
        if(till < SystemClock.uptimeMillis()) {
            //reapply current state so we can be sure that all values get applied
            moveToState(mState, null);             //min   sec  milli = 10 min
        } else if(till > mBlockTill && till - SystemClock.uptimeMillis() < 10 * 60 * 1000) {
            mMainHandler.removeCallbacks(mResetBlock);
            mMainHandler.postDelayed(mResetBlock, till - SystemClock.uptimeMillis());
        }
    }

    private final Runnable mResetBlock = new Runnable() {
        @Override
        public void run() {
            mMainHandler.sendMessage(mMainHandler.obtainMessage(-1));
        }
    };

    private void enableShakeListener(boolean b) {
        SensorManager sensorMgr = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        //Remove the sensor listener if not needed, it will only keep the device active
        if(b && mOnShakeListen && mState != State.DISABLED) {
            log("Trying to enable(" + b + ") ShakeListener -> register. The operation was " + 
                    sensorMgr.registerListener(mShakeDetector, mAccelerometer, 
                            SensorManager.SENSOR_DELAY_NORMAL));
        } else if (!b) {
            log("Trying to enable(" + b + ") ShakeListener -> unregister.");
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
     * Handler for Jarvis. Used for AppChannel connection
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
                case -1:
                    mService.setBlock(0);
                    break;
                case AppChannel.BUMP_UPDATE_WORDS:
                    mService.lookForUpdate(mService.getBaseOrCopy());
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
                case AppChannel.BUMP_BLOCK_TILL:
                    mService.setBlock(((Long)msg.obj).longValue());
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * A worker Thread that will call the JarvisService.prepare() method after it has been started.
     */
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
