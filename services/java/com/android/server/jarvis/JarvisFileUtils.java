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

import android.os.Build;

public class JarvisFileUtils {
    
    private static final String LOCATION = "/data/misc/jarvis/";
    private static final File JARVIS_LOCATION = new File(LOCATION);
    
    public static final boolean accessJarvisLocation() {
        File file = JARVIS_LOCATION;
        if(!file.exists())
            file.mkdirs();
        if(file.canRead() && file.canWrite())
            return true;
        return false;
    }
    
    public static final File getJarvisLocation() {
        return JARVIS_LOCATION;
    }
    
    public static final File getSrecGrammarFile(String ident) {
        return new File(LOCATION + "grammar_" + Build.JARVIS_VERSION + "_" + ident.hashCode() + ".g2g");
    }
    
    public static final File getBaseGrammarFile() {
        return new File(LOCATION + "grammar_base.g2g");
    }
}
