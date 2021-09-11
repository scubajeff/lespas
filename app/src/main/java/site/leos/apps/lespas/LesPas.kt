package site.leos.apps.lespas

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class LesPas : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start authentication process if needed
        try {
            val accountType = getString(R.string.account_type_nc)
            val am = AccountManager.get(this)
            val accounts = am.getAccountsByType(accountType)
            if (accounts.isEmpty()) {
                am.addAccount(accountType, "", null, null, this, { passToMainActivity() }, null)
            } else passToMainActivity()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        finish()
    }

    private fun passToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
    }
}