package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.HashMap

class NCLoginActivity : AppCompatActivity() {
    private lateinit var welcomePage: ScrollView
    private lateinit var authWebpage: WebView
    private lateinit var hostInputText: TextInputEditText
    private lateinit var logoImage: ImageView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nc_login)

        welcomePage = findViewById(R.id.welcome_page)
        authWebpage = findViewById(R.id.nc_auth_page)
        hostInputText = findViewById(R.id.host)
        logoImage = findViewById(R.id.logo)

        hostInputText.run {
            if (savedInstanceState == null) append("https://")
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                    attemptLogin()
                    true
                } else false
            }
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }

                override fun afterTextChanged(s: Editable?) {}
            })
        }

        authWebpage.run {
            // On first run, load a blank page in webview
            if (savedInstanceState == null) loadUrl("about:blank")

            webViewClient = object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.apply {
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) getToken(this.path.toString())
                    }
                    return false
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (errorResponse != null) view?.reload()
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    if (view!!.visibility == View.GONE && url!!.contains(getString(R.string.login_flow_endpoint))) {
                        val duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        authWebpage.apply {
                            alpha = 0f
                            visibility = View.VISIBLE
                            animate().alpha(1f).duration = duration
                        }
                        welcomePage.animate().alpha(0f).setDuration(duration)
                            .setListener(object: AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator?) {
                                    welcomePage.visibility = View.GONE
                                }
                            })
                        logoImage.clearAnimation()
                    }
                }
            }
            settings.apply {
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "${resources.getString(R.string.app_name)} on ${getDeviceName()}"
                javaScriptEnabled = true    // TODO: do we really need this enabled
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(WEBVIEW_VISIBLE, authWebpage.visibility == View.VISIBLE)
        authWebpage.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        authWebpage.restoreState(savedInstanceState)
        authWebpage.visibility = if (savedInstanceState.getBoolean(WEBVIEW_VISIBLE)) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (authWebpage.visibility == View.GONE) moveTaskToBack(true)
            else {
                if (authWebpage.url!!.contains(getString(R.string.login_flow_endpoint))) {  // TODO: better first page detection
                    authWebpage.visibility = View.GONE
                    welcomePage.visibility = View.VISIBLE
                    welcomePage.alpha = 1f
                    hostInputText.isEnabled = true
                    // Load a blank page in webview, to prevent cross-fade effect when configuration changed
                    authWebpage.loadUrl("about:blank")
                }
                else authWebpage.goBack()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) model else "$manufacturer $model"
    }

    private fun getToken(path: String) {
        val destructedRegex = ("/server:(.*)&user:(.*)&password:(.*)").toRegex()

        authWebpage.stopLoading()
        destructedRegex.matchEntire(path)?.destructured?.let { (server, username, token) ->
            val account = Account(username, getString(R.string.account_type_nc))
            val am = AccountManager.get(baseContext)
            if (am.addAccountExplicitly(account, "", null)) {
                am.run {
                    val url = URL(server)
                    //setAuthToken(account, server, token)
                    setAuthToken(account, server, token)    // authTokenType set to server address
                    setUserData(account, getString(R.string.nc_userdata_server), server)
                    setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
                    setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
                    setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
                    setUserData(account, getString(R.string.nc_userdata_username), username)
                    setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.DEFAULT))
                    Log.e("=========", "$token   ${Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.DEFAULT)}")
                }

                val result = Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, username)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.account_type_nc))
                    // AccountManager.KEY_AUTHTOKEN removed by setAccountAuthenticatorResult!!!
                    //putString(AccountManager.KEY_AUTHTOKEN, token)
                    putString("TOKEN", token)
                    putString(AccountManager.KEY_AUTHENTICATOR_TYPES, server)
                }

                val mAccountAuthenticatorResponse = intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
                if (mAccountAuthenticatorResponse != null) {
                    if (result != null) {
                        mAccountAuthenticatorResponse.onResult(result)

                        // TODO: Create our base folder on server if needed
                    }
                    else mAccountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled")
                }

                finish()
            }
        }
    }

    private fun attemptLogin() {
        val hostUrl = hostInputText.text.toString().trim()
        var result: Int

        val mPattern = Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")
        if (!mPattern.matcher(hostUrl).matches()) {
            hostInputText.error = getString(R.string.host_address_validation_error)
        } else {
            // Clean up the view
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostInputText.windowToken, 0) }
            hostInputText.error = null
            hostInputText.isEnabled = false
            logoImage.animation = AlphaAnimation(1f, 0f).apply {
                duration = 1600
                repeatCount = Animation.INFINITE
                repeatMode = Animation.REVERSE
            }

            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                result = pingServer("$hostUrl${getString(R.string.server_capabilities_endpoint)}")

                withContext(Dispatchers.Main) {
                    if (result in 200..399) {
                        HashMap<String, String>().run {
                            put("OCS-APIREQUEST", "true")
                            authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", this)
                        }
                    } else {
                        logoImage.clearAnimation()
                        hostInputText.apply {
                            isEnabled = true
                            error = getString(R.string.host_not_found)
                        }
                    }
                }
            }

        }
    }

    private suspend fun pingServer(serverUrl: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val response: Int
                (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 5000
                    setRequestProperty("OCS-APIRequest", "true")
                    response = responseCode
                    disconnect()
                }
                response
            } catch (e: Exception) {
                e.printStackTrace()
                999
            }
        }
    }

    companion object {
        private const val WEBVIEW_VISIBLE = "WEBVIEW_VISIBLE"
    }
}