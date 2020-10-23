package site.leos.apps.lespas.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder

class NCAuthenticatorService: Service() {
    private lateinit var mAuthenticator: NCAuthenticator

    override fun onCreate() {
        mAuthenticator = NCAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = mAuthenticator.iBinder
}