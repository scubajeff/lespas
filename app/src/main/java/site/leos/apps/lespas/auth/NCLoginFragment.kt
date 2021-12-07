package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class NCLoginFragment: Fragment() {
    private lateinit var welcomePage: LinearLayout
    private lateinit var authWebpage: WebView
    private lateinit var inputArea: TextInputLayout
    private lateinit var hostInputText: TextInputEditText
    private lateinit var loadingSpinner: Drawable
    private var selfSigned = false
    private var useHttps = true

    private var reLogin = false

    private var authResult: Bundle? = null
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reLogin = arguments?.getBoolean(KEY_RELOGIN, false) ?: false

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            requireActivity().intent.getParcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)?.onResult(authResult)
            requireActivity().finish()
        }

        (requireActivity() as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_nc_login, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val root = view.findViewById<ConstraintLayout>(R.id.layout_background)
        welcomePage = view.findViewById(R.id.welcome_page)
        authWebpage = view.findViewById(R.id.nc_auth_page)
        inputArea = view.findViewById(R.id.input_area)
        hostInputText = view.findViewById(R.id.host)
        loadingSpinner = CircularProgressDrawable(requireContext()).apply {
            strokeWidth = 6.0f
            centerRadius = 16.0f
            setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.color_primary))
            start()
        }

        // Animate the background
        (view.findViewById<ConstraintLayout>(R.id.layout_background).background as AnimationDrawable).run {
            setEnterFadeDuration(3000)
            setExitFadeDuration(3000)
            start()
        }

        if (savedInstanceState == null) {
            if (reLogin) {
                // Show welcome page without animation when it's called to do re-login
                welcomePage.findViewById<TextView>(R.id.welcome_message).visibility = View.VISIBLE
                inputArea.apply {
                    requestFocus()
                    visibility = View.VISIBLE
                }
                AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc)).apply {
                    if (this.isNotEmpty()) hostInputText.let {
                        it.setText(AccountManager.get(requireContext()).getUserData(this[0], getString(R.string.nc_userdata_server)).substringAfter("://"))
                        it.requestFocus()
                        it.isEnabled = true
                    }
                }
            }
            else with(welcomePage) {
                // Animate the welcome page on first run
                alpha = 0.3f
                animate().alpha(1f).setDuration(1500).setInterpolator(AccelerateDecelerateInterpolator()).setListener(object: AnimatorListenerAdapter() {
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
                            requestFocus()
                            visibility = View.VISIBLE
                        }
                    }
                })
            }
        } else {
            authResult = savedInstanceState.getBundle(KEY_AUTH_RESULT)?.apply {
                // If app restarts when permission request dialog show, show it again here
                requestStoragePermission()
            } ?: run {
                // If app restarts during authentication
                welcomePage.findViewById<TextView>(R.id.welcome_message).visibility = View.VISIBLE
                useHttps = savedInstanceState.getBoolean(KEY_USE_HTTPS)
                inputArea.prefixText = if (useHttps) "https://" else "http://"
                inputArea.visibility = View.VISIBLE
                null
            }
        }

        hostInputText.run {
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                    error = null
                    prepareLogin()
                    true
                } else false
            }
            //setOnFocusChangeListener { _, hasFocus ->  inputArea.helperText = if (hasFocus) getString(R.string.url_helper_text) else null}
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) {}  // TODO: url validation, helper text hints
            })
        }

        inputArea.findViewById<TextView>(com.google.android.material.R.id.textinput_prefix_text).setOnClickListener {
            useHttps = !useHttps
            inputArea.prefixText = if (useHttps) "https://" else "http://"
        }

        authWebpage.run {
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

                            requestFocus()
                            // Handle back key
                            setOnKeyListener(backPressedListener)
                        }
                        welcomePage.animate().alpha(0f).setDuration(duration).setListener(object: AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator?) { welcomePage.visibility = View.GONE }
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
                userAgentString = "${resources.getString(R.string.app_name)} on ${Tools.getDeviceModel()}"
                javaScriptEnabled = true
            }

            // On first run, load a blank page in webview
            if (savedInstanceState == null) loadUrl("about:blank")
            else {
                restoreState(savedInstanceState)
                if (savedInstanceState.getBoolean(KEY_WEBVIEW_VISIBLE)) {
                    visibility = View.VISIBLE
                    setOnKeyListener(backPressedListener)
                } else {
                    visibility = View.GONE
                    setOnKeyListener(null)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USE_HTTPS, useHttps)
        outState.putBoolean(KEY_WEBVIEW_VISIBLE, authWebpage.visibility == View.VISIBLE)
        authResult?.let { outState.putBundle(KEY_AUTH_RESULT, it) }
        authWebpage.saveState(outState)
    }

    private val backPressedListener = View.OnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == MotionEvent.ACTION_UP) {
            if (authWebpage.url!!.contains(getString(R.string.login_flow_endpoint))) {  // TODO: better first page detection
                authWebpage.visibility = View.GONE
                welcomePage.visibility = View.VISIBLE
                welcomePage.alpha = 1f
                hostInputText.isEnabled = true
                welcomePage.requestFocus()

                // Load a blank page in webview, to prevent cross-fade effect when configuration changed
                authWebpage.loadUrl("about:blank")
                // Remove key listener when view become invisible
                authWebpage.setOnKeyListener(null)
            } else authWebpage.goBack()
            true
        } else false
    }

    private fun saveTokenAndFinish(path: String) {
        authWebpage.stopLoading()

        ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(path)?.destructured?.let { (server, username, token) ->
            val url = URL(server)
            val accountName = "$username@${url.host}"
            val account = Account(accountName, getString(R.string.account_type_nc))
            val am = AccountManager.get(requireContext())
            am.run {
                addAccountExplicitly(account, "", null)
                setAuthToken(account, server, token)    // authTokenType set to server address
                setUserData(account, getString(R.string.nc_userdata_server), server)
                setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
                setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
                setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
                setUserData(account, getString(R.string.nc_userdata_username), username)
                setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.NO_WRAP))
                setUserData(account, getString(R.string.nc_userdata_selfsigned), selfSigned.toString())
            }

            if (reLogin) parentFragmentManager.popBackStack()
            else {
                authResult = Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.account_type_nc))
                    putString(AccountManager.KEY_AUTHTOKEN, token)
                    //putString("TOKEN", token)
                    putString(AccountManager.KEY_AUTHENTICATOR_TYPES, server)
                    putString(getString(R.string.nc_userdata_username), am.getUserData(account, getString(R.string.nc_userdata_username)))
                    putString(getString(R.string.nc_userdata_secret), am.getUserData(account, getString(R.string.nc_userdata_secret)))
                    putString(getString(R.string.nc_userdata_selfsigned), am.getUserData(account, getString(R.string.nc_userdata_selfsigned)))
                }

                requestStoragePermission()
            }
        }
    }

    private fun requestStoragePermission() {
        // Ask for storage access permission so that Camera Roll can be shown at first run
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        welcomePage.visibility = View.GONE
        authWebpage.visibility = View.GONE
        storagePermissionRequestLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun prepareLogin() {
        val hostUrl = (if (useHttps) "https://" else "http://") + hostInputText.text.toString().trim()
        var result: Int

        if (!Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;\\[\\]]*[-a-zA-Z0-9\\]+&@#/%=~_|]").matcher(hostUrl).matches()) {
            hostInputText.error = getString(R.string.host_address_validation_error)
        } else {
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

                        // Clean up the input area
                        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostInputText.windowToken, 0) }
                        hostInputText.run {
                            error = null
                            isEnabled = false
                        }

                        // the webview will reveal after page loaded
                        authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
                    }
                    998 -> {
                        AlertDialog.Builder(hostInputText.context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setIcon(ContextCompat.getDrawable(hostInputText.context, android.R.drawable.ic_dialog_alert)?.apply { setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))})
                            .setTitle(getString(R.string.verify_ssl_certificate_title))
                            .setCancelable(false)
                            .setMessage(getString(R.string.verify_ssl_certificate_message, hostUrl.substringAfterLast("://").substringBefore('/')))
                            .setPositiveButton(R.string.accept_certificate) { _, _ ->
                                CoroutineScope(Dispatchers.Default).launch(Dispatchers.Main) {
                                    result = pingServer(hostUrl, true)

                                    if (result == HttpURLConnection.HTTP_OK) {
                                        selfSigned = true
                                        authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
                                    } else showError(result)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> showError(1001) }
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
                var response: Int
                OkHttpClient.Builder().apply {
                    if (acceptSelfSign) hostnameVerifier { _, _ -> true }
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(20, TimeUnit.SECONDS)
                }.build().newCall(Request.Builder().url("$serverUrl${getString(R.string.server_capabilities_endpoint)}").addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute().use { response = it.code }
                response
            } catch (e: SSLPeerUnverifiedException) {
                // This certificate is issued by user installed CA, let user decided whether to trust it or not
                998
            } catch (e: SSLHandshakeException) {
                // SSL related error generally means wrong SSL certificate
                1001
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
        private const val KEY_WEBVIEW_VISIBLE = "KEY_WEBVIEW_VISIBLE"
        private const val KEY_USE_HTTPS = "KEY_USE_HTTPS"
        private const val KEY_AUTH_RESULT = "KEY_AUTH_RESULT"
        private const val KEY_RELOGIN = "KEY_RELOGIN"

        @JvmStatic
        fun newInstance(reLogin: Boolean) = NCLoginFragment().apply { arguments = Bundle().apply { putBoolean(KEY_RELOGIN, reLogin) }}
    }
}