package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.getDrawableOrThrow
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.HashMap

class NCLoginActivity : AppCompatActivity() {
    private lateinit var welcomePage: ConstraintLayout
    private lateinit var authWebpage: WebView
    private lateinit var inputArea: TextInputLayout
    private lateinit var hostInputText: TextInputEditText
    private lateinit var loadingSpinner: Drawable

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nc_login)

        welcomePage = findViewById(R.id.welcome_page)
        authWebpage = findViewById(R.id.nc_auth_page)
        inputArea = findViewById(R.id.input_area)
        hostInputText = findViewById(R.id.host)

        // Animate the welcome message on first run
        if (savedInstanceState == null) {
            welcomePage.run {
                alpha = 0f
                translationY = 100f
                animate().alpha(1f).translationY(0f).setDuration(2000).setInterpolator(DecelerateInterpolator())
                    .setListener(object: AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            super.onAnimationEnd(animation)
                            // Clear the focus of input area, make the screen cleaner
                            inputArea.clearFocus()
                        }
                    })
            }
        }
        // Animate the background
        (findViewById<ConstraintLayout>(R.id.layout_background).background as AnimationDrawable).run {
            setEnterFadeDuration(2000)
            setExitFadeDuration(4000)
            start()
        }

        // Set a loading spinner for text input view
        loadingSpinner = getProgressBarDrawable()
        inputArea.run {
            endIconDrawable = loadingSpinner
            (loadingSpinner as? Animatable)?.start()
        }

        hostInputText.run {
            //if (savedInstanceState == null) append("https://")
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                    attemptLogin()
                    true
                } else false
            }
            setOnFocusChangeListener { v, hasFocus ->  inputArea.helperText = if (hasFocus) getString(R.string.url_helper_text) else null}
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) {
                    // TODO: url validation, helper text hints
                }
            })
        }

        authWebpage.run {
            // On first run, load a blank page in webview
            if (savedInstanceState == null) loadUrl("about:blank")

            webViewClient = object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.apply {
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) saveTokenAndFinish(this.path.toString())
                    }
                    return false
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (errorResponse != null) view?.reload()
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Reveal the server login page only after it finished loading in the webview
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
                        inputArea.endIconMode = TextInputLayout.END_ICON_NONE
                    }
                }
            }
            settings.apply {
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "${resources.getString(R.string.app_name)} on ${getDeviceName()}"
                javaScriptEnabled = true
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

    // Get system's private drawable resource of progressBar
    private fun Context.getProgressBarDrawable(): Drawable {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.progressBarStyleSmall, value, false)
        val progressBarStyle = value.data
        val attributes = intArrayOf(android.R.attr.indeterminateDrawable)
        val array = obtainStyledAttributes(progressBarStyle, attributes)
        val drawable = array.getDrawableOrThrow(0)
        array.recycle()
        return drawable
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) model else "$manufacturer $model"
    }

    private fun saveTokenAndFinish(path: String) {
        val destructedRegex = ("/server:(.*)&user:(.*)&password:(.*)").toRegex()

        authWebpage.stopLoading()
        destructedRegex.matchEntire(path)?.destructured?.let { (server, username, token) ->
            val url = URL(server)
            val accountName = "$username@${url.host}"
            val account = Account(accountName, getString(R.string.account_type_nc))
            val am = AccountManager.get(baseContext)
            if (am.addAccountExplicitly(account, "", null)) {
                am.run {
                    //setAuthToken(account, server, token)
                    setAuthToken(account, server, token)    // authTokenType set to server address
                    setUserData(account, getString(R.string.nc_userdata_server), server)
                    setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
                    setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
                    setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
                    setUserData(account, getString(R.string.nc_userdata_username), username)
                    setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.DEFAULT))
                }

                val result = Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.account_type_nc))
                    putString(AccountManager.KEY_AUTHTOKEN, token)
                    //putString("TOKEN", token)
                    putString(AccountManager.KEY_AUTHENTICATOR_TYPES, server)
                }

                intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)?.onResult(result)
                // TODO: Create our base folder on server if needed
                /*
                val mAccountAuthenticatorResponse
                        = intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
                if (mAccountAuthenticatorResponse != null) {
                    if (result != null) {
                        mAccountAuthenticatorResponse.onResult(result)

                    }
                    else mAccountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled")
                }
                */

                finish()
            }
        }
    }

    private fun attemptLogin() {
        val hostUrl = "https://" + hostInputText.text.toString().trim()
        var result: Int

        val mPattern = Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")
        if (!mPattern.matcher(hostUrl).matches()) {
            hostInputText.error = getString(R.string.host_address_validation_error)
        } else {
            // Clean up the input area
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostInputText.windowToken, 0) }
            hostInputText.error = null
            hostInputText.isEnabled = false
            inputArea.endIconMode = TextInputLayout.END_ICON_CUSTOM

            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                result = pingServer("$hostUrl${getString(R.string.server_capabilities_endpoint)}")

                withContext(Dispatchers.Main) {
                    if (result == 200) {
                        HashMap<String, String>().run {
                            put("OCS-APIREQUEST", "true")
                            authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", this)
                        }
                    } else {
                        inputArea.endIconMode = TextInputLayout.END_ICON_NONE
                        hostInputText.apply {
                            isEnabled = true
                            error = when(result) {
                                999-> getString(R.string.network_error)
                                1000-> getString(R.string.unknown_host)
                                else-> getString(R.string.host_not_valid)
                            }
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
                    // TODO: validate response 
                }
                response
            } catch (e: UnknownHostException) {
                e.printStackTrace()
                1000
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