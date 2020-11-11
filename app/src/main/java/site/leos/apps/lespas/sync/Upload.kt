package site.leos.apps.lespas.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity

class Upload: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)

        val files = mutableListOf<Uri>()
        if ((intent.action == Intent.ACTION_SEND) && (intent.type?.startsWith("image/")!!)) {
            files.add(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as Uri)
        }
        if ((intent.action == Intent.ACTION_SEND_MULTIPLE) && (intent.type?.startsWith("image/")!!)) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
                files.add(it as Uri)
            }
        }
    }
}