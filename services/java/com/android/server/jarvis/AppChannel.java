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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.speech.jarvis.IWakeUpServiceCallback;
import android.speech.jarvis.IWakeUpService;
import android.util.Log;

public class AppChannel {
    private static final String TAG = "AppChannel";
    
    public static final int BUMP_ACQUIRE_BLOCK  = 1;
    public static final int BUMP_RELEASE_BLOCK  = 2;
    
    private ServiceConnection mConnection = new ServiceConnection() {
        // Called when the connection with the service is established
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Following the example above for an AIDL interface,
            // this gets an instance of the IWakeUpService, which we can use to call on the service
            mIWakeUpService = IWakeUpService.Stub.asInterface(service);
            mName = className;
            
            // We want to monitor the service for as long as we are
            // connected to it. This will give the IWakeUpService
            // a possibility to communicate to the remote (system server)
            try {
                mIWakeUpService.registerCallback(mCallback);
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
            mIWakeUpService = null;
        }
    };
    private IWakeUpService mIWakeUpService;
    private Context mContext;
    private Intent mStartIntent;
    private ComponentName mName;
    private final Handler mHandler;
    
    public AppChannel(Context con, String packageName, String serviceAddress, Handler h) {
        mContext = con;
        mIWakeUpService = null;
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
        return mIWakeUpService != null;
    }
    
    public void connect() {
        if(mIWakeUpService == null) {
            mContext.bindService(mStartIntent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }
    
    public void disconnect() throws RemoteException {
        if(mIWakeUpService != null) {
            mIWakeUpService.unregisterCallback(mCallback);
            mContext.unbindService(mConnection);
        }
    }
    
    public boolean onReceivedWakeUp(String input) {
        try {
            return mIWakeUpService.onReceivedWakeUp(input);
        } catch (RemoteException ex) {
            return false;
        }
    }
    
    /* Callbacks from remote service */
    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private IWakeUpServiceCallback mCallback = new IWakeUpServiceCallback.Stub() {
        public void acquireBlock() {
           mHandler.sendEmptyMessage(BUMP_ACQUIRE_BLOCK);
        }

        public void releaseBlock() {
            mHandler.sendEmptyMessage(BUMP_RELEASE_BLOCK);
        }
    };
}
