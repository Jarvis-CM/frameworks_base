/*
 * Copyright (C) 2014 Firtecy
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.jarvis;

import java.util.List;
import android.speech.jarvis.IJarvisCallback;

/** {@hide} Jarvis service interface */
interface IJarvis {
    
    int getTargetedApi();

    boolean isReady();
    
    long getLastChange();
    
    int getCountWords();
    
    String getWordAt(int i);

    boolean handleStrings(in List<String>results);
    
    boolean registerCallback(IJarvisCallback cb);
    
    boolean unregisterCallback(IJarvisCallback cb);
}
