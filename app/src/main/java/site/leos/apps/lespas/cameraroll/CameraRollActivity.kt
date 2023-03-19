/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.cameraroll

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools

class CameraRollActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Launch MainActivity
        super.onCreate(savedInstanceState)
        Intent().apply {
            setClass(this@CameraRollActivity, MainActivity::class.java)
            action = MainActivity.LAUNCH_CAMERAROLL
            data = intent.data
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_HISTORY)
            overridePendingTransition(0, 0)
            finish()
            overridePendingTransition(0, 0)
            startActivity(this)
        }

        return
    }
}