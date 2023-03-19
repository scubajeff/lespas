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

package site.leos.apps.lespas.search

import android.graphics.RectF

data class Classification (
    val photoId: String,        // Photo ID in photo table
    //val albumId: String,        // Photo album ID in album table
    //val date: LocalDateTime,    // Photo taken date
    val type: Int,              // Face or object
    val classId: String,        // For object, it's object's id; for face, it's face's id
    val objectIndex: Int,       // object index in label file
    val similarity: Float,      // For object, the higher the more similar; for face, the lower the more similar
    val location: RectF,        // Face or object location in photo
) {
    companion object {
        const val TABLE_NAME = "classifications"

        const val TYPE_UNKNOWN = -1
        const val TYPE_FACE = 0
        const val TYPE_OBJECT = 1

        const val OBJECT_UNKNOWN = 0
        const val OBJECT_ANIMAL = 1
        const val OBJECT_PLANT = 2
        const val OBJECT_FOOD = 3
        const val OBJECT_VEHICLE = 4
    }
}