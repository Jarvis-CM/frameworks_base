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

import android.content.Context;

import com.android.internal.policy.IJarvisPolicy;

/**
 * {@hide}
 */

// Simple implementation of the policy interface that spawns the right
// set of objects and is a interface between framework and app
public class JarvisPolicy implements IJarvisPolicy {
    private static final String TAG = "JarvisPolicy";

    public JarvisPolicy() {
        
    }

    public IJarvisPolicy checkPolicy(Context con) {
        //TODO: Check for context and security..
        return this;
    }

    public boolean dispatchOnClickEvent(View v) {
       return true; //For now we only have a dummy implementation
    }

    public boolean dispatchLongOnClickEvent(View v) {
       return true; //For now we only have a dummy implementation
    }
}

