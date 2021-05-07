package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.getDrawableOrThrow
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
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
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException

class NCLoginActivity : AppCompatActivity() {
    private lateinit var welcomePage: LinearLayout
    private lateinit var authWebpage: WebView
    private lateinit var inputArea: TextInputLayout
    private lateinit var hostInputText: TextInputEditText
    private lateinit var loadingSpinner: Drawable
    private var selfSigned = false

    @SuppressLint("SetJavaScriptEnabled")   // Nextcloud authentication page requires JavaScript enabled
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nc_login)

        val root = findViewById<ConstraintLayout>(R.id.layout_background)
        welcomePage = findViewById(R.id.welcome_page)
        authWebpage = findViewById(R.id.nc_auth_page)
        inputArea = findViewById(R.id.input_area)
        hostInputText = findViewById(R.id.host)
        loadingSpinner = getSpinnerDrawable()
        (loadingSpinner as? Animatable)?.start()

        // Animate the background
        (findViewById<ConstraintLayout>(R.id.layout_background).background as AnimationDrawable).run {
            setEnterFadeDuration(3000)
            setExitFadeDuration(3000)
            start()
        }

        // Animate the welcome page on first run
        if (savedInstanceState == null) {
            with(welcomePage) {
                alpha = 0.3f
                animate().alpha(1f).setDuration(1500).setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(object: AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            super.onAnimationEnd(animation)

                            ConstraintSet().run {
                                val t = AutoTransition().apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                                clone(root)
                                constrainPercentHeight(R.id.welcome_page, 0.8f)
                                TransitionManager.beginDelayedTransition(root, t)
                                applyTo(root)
                            }
                            welcomePage.findViewById<TextView>(R.id.welcome_message).visibility = View.VISIBLE
                            inputArea.apply {
                                // Clear the focus of input area, make the screen cleaner
                                clearFocus()
                                visibility = View.VISIBLE
                            }

                        }
                    }
                )
            }
        } else {
            welcomePage.findViewById<TextView>(R.id.welcome_message).visibility = View.VISIBLE
            inputArea.visibility = View.VISIBLE
        }

        hostInputText.run {
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                    prepareLogin()
                    true
                } else false
            }
            //setOnFocusChangeListener { _, hasFocus ->  inputArea.helperText = if (hasFocus) getString(R.string.url_helper_text) else null}
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
                        // Detect nextcloud's special credential url scheme to retrieve token
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) saveTokenAndFinish(this.path.toString())
                    }
                    return false
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (errorResponse != null) view?.reload()   // TODO: better error handling
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Reveal the server authentication page only after it finished loading in this webview
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

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    if (error?.primaryError == SslError.SSL_IDMISMATCH && selfSigned) handler?.proceed() else handler?.cancel()
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

    /* Get system's private drawable resource of spinner */
    private fun Context.getSpinnerDrawable(): Drawable {
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
        return if (model.lowercase().startsWith(manufacturer.lowercase())) model else "$manufacturer $model"
    }

    private fun saveTokenAndFinish(path: String) {
        authWebpage.stopLoading()

        ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(path)?.destructured?.let { (server, username, token) ->
            val url = URL(server)
            val accountName = "$username@${url.host}"
            val account = Account(accountName, getString(R.string.account_type_nc))
            val am = AccountManager.get(baseContext)
            if (am.addAccountExplicitly(account, "", null)) {
                am.run {
                    setAuthToken(account, server, token)    // authTokenType set to server address
                    setUserData(account, getString(R.string.nc_userdata_server), server)
                    setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
                    setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
                    setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
                    setUserData(account, getString(R.string.nc_userdata_username), username)
                    setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.NO_WRAP))
                    setUserData(account, getString(R.string.nc_userdata_selfsigned), selfSigned.toString())
                }

                val result = Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.account_type_nc))
                    putString(AccountManager.KEY_AUTHTOKEN, token)
                    //putString("TOKEN", token)
                    putString(AccountManager.KEY_AUTHENTICATOR_TYPES, server)
                    putString(getString(R.string.nc_userdata_username), am.getUserData(account, getString(R.string.nc_userdata_username)))
                    putString(getString(R.string.nc_userdata_secret), am.getUserData(account, getString(R.string.nc_userdata_secret)))
                }

                intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)?.onResult(result)
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

    private fun prepareLogin() {
        val hostUrl = "https://" + hostInputText.text.toString().trim()
        var result: Int

        if (!Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;\\[\\]]*[-a-zA-Z0-9\\]+&@#/%=~_|]").matcher(hostUrl).matches()) {
            hostInputText.error = getString(R.string.host_address_validation_error)
        } else {
            // Clean up the input area
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostInputText.windowToken, 0) }
            hostInputText.run {
                error = null
                isEnabled = false
            }
            // Set a loading spinner for text input view
            inputArea.run {
                endIconDrawable = loadingSpinner
                endIconMode = TextInputLayout.END_ICON_CUSTOM
            }

            CoroutineScope(Dispatchers.Default).launch(Dispatchers.Main) {
                result = pingServer(hostUrl, false)

                when (result) {
                    HttpURLConnection.HTTP_OK -> {
                        // If everything ok, start loading the nextcloud authentication page in webview
                        // the webview will reveal after page loaded
                        authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(NEXTCLOUD_OCSAPI_HEADER, "true") })
                    }
                    998 -> {
                        // Use
                        AlertDialog.Builder(hostInputText.context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setIcon(ContextCompat.getDrawable(hostInputText.context, android.R.drawable.ic_dialog_alert)?.apply { setTint(getColor(android.R.color.holo_red_dark))})
                            .setTitle(getString(R.string.verify_ssl_certificate_title))
                            .setCancelable(false)
                            .setMessage(getString(R.string.verify_ssl_certificate_message, hostUrl.substringAfterLast("://").substringBefore('/')))
                            .setPositiveButton(R.string.accept_certificate) { _, _ ->
                                CoroutineScope(Dispatchers.Default).launch(Dispatchers.Main) {
                                    result = pingServer(hostUrl, true)

                                    if (result == HttpURLConnection.HTTP_OK) {
                                        selfSigned = true
                                        authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(NEXTCLOUD_OCSAPI_HEADER, "true") })
                                    } else showError(result)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->  showError(1001)}
                            .create().show()
                    }
                    else -> showError(result)
                }
            }
        }
    }

    private fun showError(errorCode: Int) {
        inputArea.endIconMode = TextInputLayout.END_ICON_NONE
        hostInputText.apply {
            isEnabled = true
            error = when(errorCode) {
                999-> getString(R.string.network_error)
                404, 1000-> getString(R.string.unknown_host)
                1001-> getString(R.string.certificate_error)
                else-> getString(R.string.host_not_valid)
            }
        }
    }

    /* Use nextcloud server capabilities OCS endpoint to validate host */
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun pingServer(serverUrl: String, acceptSelfSign: Boolean): Int {
        return withContext(Dispatchers.IO) {
            try {
                var response = 0
                (URL("$serverUrl${getString(R.string.server_capabilities_endpoint)}").openConnection() as HttpsURLConnection).apply {
                    if (acceptSelfSign) setHostnameVerifier { _, _ -> true }
                    setRequestProperty(NEXTCLOUD_OCSAPI_HEADER, "true")
                    connectTimeout = 2000
                    readTimeout = 5000
                    instanceFollowRedirects = false
                    response = responseCode
                    /*
                    if (response == HttpURLConnection.HTTP_OK) {
                        BufferedReader(InputStreamReader(this.inputStream)).apply {
                            val result = StringBuffer()
                            var line = readLine()
                            while (line != null) {
                                result.append(line)
                                line = readLine()
                            }
                            // server should return this JSON object
                            JSONObject(result.toString()).get("ocs")
                        }
                    }
                    */
                    disconnect()
                }
                response
            } catch (e: SSLPeerUnverifiedException) {
                // This certificate is issued by user installed CA, let user decided whether to trust it or not
                998
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
        private const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
    }
}