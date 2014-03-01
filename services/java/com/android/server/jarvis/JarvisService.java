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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IJarvisService;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
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
    private static final String PACKAGE_NAME = "de.firtecy.voiceredirectgoogle";
    /** and the connected service */
    private static final String PACKAGE_SERVICE_NAME = "de.firtecy.voiceredirectgoogle.ProxyService";
    
    private static final String DEFAULT_NAME = "Jarvis";
    private static final String DEFAULT_SLOT = "@Name";
    private static final String BASE_G2G_FILE = "/system/usr/srec/config/en.us/grammars/grammar_base.g2g";
    //Now all properties have finished

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
                    String meaning = result.getString(GrammarRecognizer.KEY_MEANING, "");
                    if(meaning.contains(DEFAULT_NAME)) {
                        if(!isScreenOn()) {
                            log("We are waking the device from sleeping.");
                            wakeUpDevice();
                        }
                        
                        if(mKeyguardManager.isKeyguardLocked()) {
                            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                        }
                    }
                    
                    if(mChannel != null && mChannel.isConnected())
                        mBlockFromService = mChannel.onReceivedWakeUp(list.get(1));
                } else {
                    log("Confidence was too low to be a valid result confidence='" + con + "'");
                }
            } catch (Exception ex) {
                log("Failed to parse result of recognizing.", ex);
            }
            mIsListening = false;
        }

        @Override
        public void onRecognitionFailure() {
            mIsListening = false;
        }

        @Override
        public void onRecognitionError(String reason) {
            mIsListening = false;
            log("Failed to listen: " + reason);
        }

        @Override
        public void onVoiceEnded() {
            //Will be used later
        }

        @Override
        public void onRecognizerFinished() {
            if(shouldListen())
                listen(false);
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
                    Settings.System.JARVIS_SERVICE_LISTEN_ENABLED), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            final int userID = ActivityManager.getCurrentUser();
            mShakeThreshold =
                    Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.JARVIS_SERVICE_SHAKE_THRESHOLD, SHAKE_THRESHOLD,
                            userID);
            mOnShakeListen = mShakeThreshold > 0; 
            mListenMode =
                    Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.JARVIS_SERVICE_LISTEN_WAKE_UP, 3,
                            userID);
            mListenSOn = (mListenMode & 1) == 1;
            mListenP = (mListenMode & 2) == 2;
            mListenEverytime = (mListenMode & 4) == 4;
            
            mIsEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.JARVIS_SERVICE_LISTEN_ENABLED, 1, userID) == 1;
            
            String service = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.JARVIS_SERVICE_KEYS, userID);
            
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

    /**
     * System Server context
     */
    private Context mContext;
    
    private Thread mMainThread;

    /** 
     * SettingsObserver to listen for changes in the listen modes
     */
    private SettingsObserver mObserver;
    
    /** 
     * Power manager to determine the screen on/off state and control over the screen
     */
    private IPowerManager mPM;
    
    /** 
     * The device keyguard manager to bypass the lock screen
     */
    private KeyguardManager mKeyguardManager;
    
    private IJarvisPolicy mPolicy;
    /** 
     * Accelaration sensor to determine a shake for listening
     */
    private Sensor mAccelerometer;
    
    /** 
     * Broadcast receiver for installed services
     */
    private BroadcastReceiver mReceiver;
    
    /** 
     * App Channel to communicate with the service 
     */
    private AppChannel mChannel;
    
    /**
     * Recognizer for listening
     */
    private GrammarRecognizer mRecognizer;
    
    private Handler mMainHandler;
    
    /** 
     * Vibrator for giving haptic feedback
     */
    private Vibrator mVibrator;
    
    private VolumeThresholdSensor mVolumeListener;
    
    /**
     * Boolean value to indicate if we should listen on shake
     */
    private boolean mOnShakeListen;
    
    /**
     * The raw listen mode data that we get from
     * the system settings
     */
    private int mListenMode;
    
    /**
     * Boolean value to enable listening, when the
     * screen is turned on
     */
    private boolean mListenSOn;
    
    /**
     * Boolean value to enable listening, when the phone
     * is plugged in. (when it is charging)
     */
    private boolean mListenP;
    
    /**
     * Boolean value to enable listen everytime mode
     */
    private boolean mListenEverytime;
    
    /**
     * Boolean value to indicate if the service is enabled or not
     */
    private boolean mIsEnabled;
    
    /**
     * The package name of the service, that should handle the 
     * queries
     */
    private String mServicePackage;
    
    /**
     * The service name that should handle all queries
     */
    private String mServiceName;
    
    /**
     * The shake threshold that will be used to determine
     * if we should start listening <br>
     * When this value is 0 on shake listening will be 
     * disabled
     */
    private int mShakeThreshold;
    
    /**
     * Boolean value to indicate if we are listening
     */
    private boolean mIsListening;
    
    /**
     * Boolean value to indicate if we successfully added
     * the acceleration sensor listener
     */
    private boolean mAddedAcceleratorListener;
    
    /**
     * Boolean value to indicate if the service blocks all
     * inputs
     */
    private boolean mBlockFromService;

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
        mChannel = null;
        mMainHandler = null;
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
        
        //Get the power manager and keyguard manager
        mPM = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        mKeyguardManager = (KeyguardManager) con.getSystemService(Context.KEYGUARD_SERVICE);
        
        mVolumeListener = new VolumeThresholdSensor();
        //TODO: We need a listener to make sure we don't need to listen
        //      everytime through the recognizer
        
        //Init the settings:
        mOnShakeListen = true;
        mListenEverytime = false;
        mListenSOn = false;
        mListenP = false;
        mServicePackage = PACKAGE_NAME;
        mServiceName = PACKAGE_SERVICE_NAME;
        mShakeThreshold = SHAKE_THRESHOLD;
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
        
        if(!mIsEnabled) {
            disable();
            return;
        }
        
        if(mOnShakeListen && !mAddedAcceleratorListener)
            enableShakeListener(true);
        else if(!mOnShakeListen && mAddedAcceleratorListener)
            enableShakeListener(false);
        
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
        } else newService = true;

        //Now connected to new service
        if(newService && init()) {
            log("Successful switched to new service.");
        }
        
        if(shouldListen())
            listen(false);
    }
    
    private void disable() {
        if(isConnectedToService()) {
            try {
                mChannel.disconnect();
            } catch (RemoteException ex) {
                log("Failed to disconnect from service", ex);
            }
            mChannel = null;
        }
        
        if(mAddedAcceleratorListener) {
            enableShakeListener(false);
        }
    }
    
    private boolean isConnectedToService() {
        return mChannel != null && mChannel.isConnected();
    }

    private void prepare() {
        if(!JarvisFileUtils.accessJarvisFileLocation())
            log("No write permission to Jarvis File Location.");

        mVibrator = (Vibrator)mContext.getSystemService(Context.VIBRATOR_SERVICE);

        //Look for settings changes
        mObserver = new SettingsObserver(mMainHandler);
        mObserver.observe();
        
        try {
            while(ActivityManagerNative.getDefault().getCurrentUser() == null) {
                Thread.sleep(25);
            }
            //Wait a bit...
            Thread.sleep(25);
        } catch (Exception ex) {
            log("Failed to wait till there is a valid user.", ex);
        }
        
        if(isAppInstalled(mServicePackage)) {
            //We have the support app installed so proceed
            log("Service package is installed. package=" + mServicePackage);
        }
        
        if(mReceiver == null) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            mReceiver = new SettingsReceiver();
            mContext.registerReceiver(mReceiver, filter);
        }
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

        if(base == null || !base.exists()) {
            log("There is no valid grammar file avaible.");
            return false;
        }
        
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
            //We are waiting a bit..
            Thread.sleep(20);
            sServiceComponentName = mChannel.getComponentName();
        } catch (Exception ex) {
            log("Catched a remote exception while waiting till Service is ready. Cancel.", ex);
            return false;
        }
        try {
            mRecognizer.loadGrammar(base);
        } catch (IOException ex) {
            log("Failed to load grammar.", ex);
            return false;
        }
        
        if(shouldListen())
            listen(false);
        
        return true;
    }

    /**
     * Call this to start listening. When the listening has not 
     * finished yet this will do nothing.
     * @param boolean If we should vibrate to send feedback
     */
    private synchronized void listen(boolean v) {
        if(!mIsEnabled) {
            log("Couldn't listen because the service is disabled.");
            return;
        }
        if(mBlockFromService) {
            log("Couldn't listen because we are blocked from service");
            return;
        }
        if(mIsListening) {
            log("Couldn't listen because we are already listening.");
            return;
        }
        if(!isConnectedToService()) {
            log("Couldn't listen because no Service is connected.");
            return;
        }
        
        if(v)mVibrator.vibrate(200);
        mIsListening = true;
        mRecognizer.recognize();
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
        mIsListening = false;
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
        //In case we want to listen everytime, start at least one time!
        if(t && !mIsListening)
            listen(false);
    }
    
    private void checkModes() {
        if(shouldListen() && !mIsListening)
            listen(false);
        
        if(mOnShakeListen)
            enableShakeListener(true);
    }
    
    private boolean shouldListen() {
        if(!mIsEnabled)
            return false;
        
        if(mBlockFromService)
            return false;
        
        if(isScreenOn() && mListenSOn)
            return true;
        
        if(mListenEverytime)
            return true;
        
        if(isCharging() && mListenP)
            return true;
        
        return false;
    }

    private void onShake() {
        listen();
    }
    

    void blockFromService() {
        log("Service set a block.");
        mBlockFromService = true;
    }

    void releaseBlockFromService() {
        log("Service tried to release the block.");
        if(mBlockFromService) {
            mBlockFromService = false;
            if(shouldListen())
                listen(false);
        }
    }

    private void enableShakeListener(boolean b) {
        if(b == mAddedAcceleratorListener)
            return;
        
        SensorManager sensorMgr = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        //Remove the sensor listener if not needed, it will only keep the device active
        if(b && mOnShakeListen) {
            mAddedAcceleratorListener = sensorMgr.registerListener(mShakeDetector, mAccelerometer, 
                    SensorManager.SENSOR_DELAY_NORMAL);
            log("Trying to enable(" + b + ") ShakeListener -> register. The operation was "
                    + mAddedAcceleratorListener);
        } else if (!b) {
            log("Trying to enable(" + b + ") ShakeListener -> unregister.");
            sensorMgr.unregisterListener(mShakeDetector);
            mAddedAcceleratorListener = false;
        }
    }
    
    private void wakeUpDevice() {
        try {
            mPM.wakeUp(SystemClock.uptimeMillis());
        } catch (RemoteException e) {
            //Not much we can do here..
        }
    }
    
    private void turnScreenOff() {
        try {
            mPM.goToSleep(SystemClock.uptimeMillis(), 0);
        } catch (RemoteException e) {
            //Not much we can do here..
        }
    }
    
    private boolean isScreenOn() {
        try {
            return mPM.isScreenOn();
        } catch (RemoteException e) {
        }
        return false;
    }

    private void wakeUpAndLaunch(String packagename, String actClass) {
        wakeUpDevice();
        
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packagename, actClass));
        if (!mKeyguardManager.isKeyguardSecure()) {
            try {
                // Dismiss the lock screen when Settings starts.
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        mContext.startActivity(intent);
    }

    private boolean isCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        
        // Are we charging / charged?
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public class SettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                log("Got boot up action.");
                
                mObserver.onChange(false);
                
                if(isAppInstalled(mServicePackage)) {
                    //We have the support app installed so proceed
                    log("Service package is installed. package=" + mServicePackage);
                    return;
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
            } else checkModes();
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
                case AppChannel.BUMP_ACQUIRE_BLOCK:
                    mService.blockFromService();
                    break;
                case AppChannel.BUMP_RELEASE_BLOCK:
                    mService.releaseBlockFromService();
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
                    mMainHandler = new JarvisHandler(mWorkerHandler.getLooper(), JarvisService.this);
                    prepare();
                }
            });
            Log.i(TAG, "Prepared Looper for Jarvis Service");
            Looper.loop();
        }
    }
}
