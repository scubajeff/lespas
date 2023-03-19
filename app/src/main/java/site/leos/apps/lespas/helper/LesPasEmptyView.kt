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

package site.leos.apps.lespas.helper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R
import java.lang.Integer.max

class LesPasEmptyView(private val icon: Drawable): RecyclerView.ItemDecoration() {
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        if (parent.adapter?.itemCount == 0) {
            val size = max(parent.width, parent.height) / 5
            val mPaint = Paint().apply {
                isAntiAlias = true
                color = ContextCompat.getColor(parent.context, R.color.color_on_primary)
                alpha = 64
                style = Paint.Style.FILL_AND_STROKE
            }
            c.drawBitmap(icon.toBitmap(size, size), (parent.width - size) / 2.0f, (parent.height - size) / 2.0f, mPaint)
        }
    }
}