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
    // Using LocalDateTime in database is a BAD BAD idea base on a wrong interpretation of "without a time-zone", although the timestamp in Long is correctly converted and saved
    // In order to copy timestamp correctly, must make sure using the same ZoneId when converting from source then to target
    // Cases of copying timestamp
    //      from EXIF to database when acquiring media
    //      from database to writing content meta json file
    //      from database to patching server's WebDAV property
    //      from Android MediaStore column to Photo POJO
    @TypeConverter
    fun fromLong(value: Long): LocalDateTime {
        return try {
            if (value > 9999999999) Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime() else Instant.ofEpochSecond(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: Exception) { LocalDateTime.now() }
    }

    @TypeConverter
    fun toLong(date: LocalDateTime): Long {
        //
        return date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}