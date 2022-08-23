/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class Converter {
    @TypeConverter
    fun fromLong(value: Long): LocalDateTime {
        return try {
            if (value > 9999999999) Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime() else Instant.ofEpochSecond(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: Exception) { LocalDateTime.now() }
    }

    @TypeConverter
    fun toLong(date: LocalDateTime): Long {
        return date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}