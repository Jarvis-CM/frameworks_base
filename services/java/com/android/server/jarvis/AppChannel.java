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

import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.speech.jarvis.IJarvis;
import android.speech.jarvis.IJarvisCallback;
import android.util.Log;

public class AppChannel {
    private static final String TAG = "AppChannel";
    
    public static final int BUMP_UPDATE_WORDS  = 1;
    public static final int BUMP_LISTEN        = 2;
    public static final int BUMP_STOP          = 3;
    
    private ServiceConnection mConnection = new ServiceConnection() {
        // Called when the connection with the service is established
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Following the example above for an AIDL interface,
            // this gets an instance of the IJarvis, which we can use to call on the service
            mIJarvisService = IJarvis.Stub.asInterface(service);
            mName = className;
            
            // We want to monitor the service for as long as we are
            // connected to it. This will give the IJarvis service
            // a possibility to communicate to the remote (system server)
            try {
                mIJarvisService.registerCallback(mCallback);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
            }
        }

        // Called when the connection with the service disconnects unexpectedly
        public void onServiceDisconnected(ComponentName className) {
            Log.e(TAG, "Service has unexpectedly disconnected");
            mIJarvisService = null;
        }
    };
    private IJarvis mIJarvisService;
    private Context mContext;
    private Intent mStartIntent;
    private ComponentName mName;
    private final Handler mHandler;
    
    public AppChannel(Context con, String packageName, String serviceAddress, Handler h) {
        mContext = con;
        mIJarvisService = null;
        mStartIntent = new Intent();
        mStartIntent.setComponent(new ComponentName(packageName, serviceAddress));
        mStartIntent.setAction(Intent.ACTION_JARVIS_VOICE_CONTROL);
        mHandler = h;
    }
    
    public ComponentName getComponentName() {
        return mName;
    }
    
    Intent getIntent() {
        return mStartIntent;
    }
    
    public boolean isConnected() {
        return mIJarvisService != null;
    }
    
    public void connect() {
        if(mIJarvisService == null) {
            mContext.bindService(mStartIntent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }
    
    public void disconnect() throws RemoteException {
        if(mIJarvisService != null) {
            mIJarvisService.unregisterCallback(mCallback);
            mContext.unbindService(mConnection);
        }
    }
    
    public int getTargetApi() throws RemoteException {
        if(isConnected()) {
            return mIJarvisService.getTargetedApi();
        } else return -1;
    }
    
    public void sendString(List<String> list) throws RemoteException {
        if(isReady()) {
            mIJarvisService.handleStrings(list);
        }
    }
    
    public long getLastUpdate() {
        long l = -1;
        try {
            l = mIJarvisService.getLastChange();
        } finally {
            return l;
        }
    }
    
    public String getWordAt(int i) throws RemoteException {
        return mIJarvisService.getWordAt(i);
    }
    
    public int getCountWords() {
        int i = -1;
        try {
            i = mIJarvisService.getCountWords();
        } finally {
            return i;
        }
    }
    
    public boolean isReady() throws RemoteException {
        return isConnected() && mIJarvisService.isReady();
    }
    
    /* Callbacks from remote service */
    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private IJarvisCallback mCallback = new IJarvisCallback.Stub() {
        /*
         * This is called by the remote service regularly to tell us about
         * new words. Note that IPC calls are dispatched through a thread
         * pool running in each process, so the code executing here will
         * NOT be running in our main thread like most other things -- so,
         * to update the service, we need to use a Handler to hop over there.
         */
        public void newWordsAvailable(long since) {
            mHandler.sendMessage(mHandler.obtainMessage(BUMP_UPDATE_WORDS, new Long(since)));
        }
        
        public void listen(int fortime) {
            mHandler.sendMessage(mHandler.obtainMessage(BUMP_LISTEN, new Integer(fortime)));
        }
        
        public void stop(int fortime) {
            mHandler.sendMessage(mHandler.obtainMessage(BUMP_STOP, new Integer(fortime)));
        }
    };
}
