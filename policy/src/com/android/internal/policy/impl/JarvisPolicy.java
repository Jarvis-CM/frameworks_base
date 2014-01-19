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

package com.android.internal.policy.impl;

import java.lang.reflect.Method;

import android.content.ComponentName;
import android.content.Context;
import android.view.View;

import com.android.internal.policy.IJarvisPolicy;

/**
 * {@hide}
 */

// Simple implementation of the policy interface that spawns the right
// set of objects and is a interface between framework and app
public class JarvisPolicy implements IJarvisPolicy {
    private static final String TAG = "JarvisPolicy";

    private Object mJarvisService;

    public JarvisPolicy() {
        //TODO: Check for initialization stuff
    }

    @Override
    public boolean isConnected() {
        return mJarvisService != null;
    }

    public void assignSystemService(Object o) {
        try {
            if(Class.forName("com.android.server.jarvis.JarvisService").isInstance(o))
                mJarvisService = o;
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public IJarvisPolicy checkPolicy(Context con) {
        //TODO: Check for context and security..
        return this;
    }

    @Override
    public boolean dispatchOnClickEvent(View v) {
       return true; //For now we only have a dummy implementation
    }

    @Override
    public boolean dispatchOnLongClickEvent(View v) {
       return true; //For now we only have a dummy implementation
    }

    public ComponentName getConnectedServiceComponentName() {
        //First check if it is even running
        if(mJarvisService != null) {
            try {
                Method m = Class.forName("com.android.server.jarvis.JarvisService").getMethod("getServiceComponentName", null);
                Object o = m.invoke(null, null);
                if(o instanceof ComponentName) {
                    return (ComponentName)o;
                }
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }
}

