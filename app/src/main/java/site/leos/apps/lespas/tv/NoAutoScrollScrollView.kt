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

package site.leos.apps.lespas.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

class NoAutoScrollScrollView(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int): ScrollView(context, attrs, defStyleAttr, defStyleRes) {
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int): this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet): this(context, attrs, 0, 0)

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}