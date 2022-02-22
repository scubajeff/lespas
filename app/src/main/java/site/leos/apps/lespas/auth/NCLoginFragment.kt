package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
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

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>

    private val scanIntent = Intent("com.google.zxing.client.android.SCAN")
    private lateinit var scanButton: MaterialButton
    private lateinit var scanRequestLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reLogin = arguments?.getBoolean(KEY_RELOGIN, false) ?: false

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Handler(Looper.getMainLooper()).post {
                requireActivity().apply {
                    val myIntent = intent.apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION) }
                    overridePendingTransition(0, 0)
                    finish()

                    overridePendingTransition(0, 0)
                    startActivity(myIntent)
                }
            }
        }
        scanRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra("SCAN_RESULT")?.let { scanResult ->
                    ("nc://login/user:(.*)&password:(.*)&server:(.*)").toRegex().matchEntire(scanResult)?.destructured?.let { (username, token, server) ->
                        hostInputText.setText(server.substringAfter("://"))
                        checkServer(server, username, token)
                    }
                }
            }

            // TODO Show scan error
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_nc_login, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val root = view.findViewById<ConstraintLayout>(R.id.layout_background)
        welcomePage = view.findViewById(R.id.welcome_page)
        authWebpage = view.findViewById(R.id.nc_auth_page)
        inputArea = view.findViewById(R.id.input_area)
        hostInputText = view.findViewById(R.id.host)
        scanButton = view.findViewById(R.id.scan)
        loadingSpinner = CircularProgressDrawable(requireContext()).apply {
            strokeWidth = 6.0f
            centerRadius = 16.0f
            setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.color_primary))
            hostInputText.textSize.toInt().let { setBounds(0, 0, it, it) }
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
                showInputArea()
                AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc)).apply {
                    if (this.isNotEmpty()) hostInputText.setText(AccountManager.get(requireContext()).getUserData(this[0], getString(R.string.nc_userdata_server)).substringAfter("://"))
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
                        showInputArea()
                    }
                })
            }
        } else {
            // If app restarts during authentication
            useHttps = savedInstanceState.getBoolean(KEY_USE_HTTPS)
            inputArea.prefixText = if (useHttps) "https://" else "http://"
            if (!savedInstanceState.getBoolean(KEY_WEBVIEW_VISIBLE)) showInputArea()
        }

        hostInputText.run {
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                    checkServer((if (useHttps) "https://" else "http://") + text.toString().trim())
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
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme)))
                            ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(this.path.toString())?.destructured?.let { (server, username, token) ->
                                // As stated in <a href="https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html#obtaining-the-login-credentials">Nextcloud document</a>:
                                // The server may specify a protocol (http or https). If no protocol is specified the client will assume https.
                                val host = if (server.startsWith("http")) server else "https://${server}"
                                saveTokenAndFinish(host, username, token)
                            } ?: run {
                                backToWelcomePage()
                                showError(999)
                            }
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
        authWebpage.saveState(outState)
    }

    private val backPressedListener = View.OnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == MotionEvent.ACTION_UP) {
            // TODO: better first page detection
            if (authWebpage.url!!.contains(getString(R.string.login_flow_endpoint))) backToWelcomePage()
            else authWebpage.goBack()

            true
        } else false
    }

    private fun showInputArea() {
        welcomePage.findViewById<TextView>(R.id.welcome_message).visibility = View.VISIBLE
        scanIntent.resolveActivity(requireActivity().packageManager)?.let {
            scanButton.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    scanRequestLauncher.launch(scanIntent)
                }
            }
        }
        enableInput()
    }

    private fun disableInput() {
        inputArea.isEnabled = false
        scanButton.isEnabled = false
        hostInputText.error = null
    }

    private fun enableInput() {
        inputArea.isEnabled = true
        inputArea.visibility = View.VISIBLE
        inputArea.requestFocus()
        scanButton.isEnabled = true
    }

    private fun backToWelcomePage() {
        authWebpage.visibility = View.GONE
        welcomePage.visibility = View.VISIBLE
        welcomePage.alpha = 1f
        enableInput()

        // Load a blank page in webview, to prevent cross-fade effect when configuration changed
        authWebpage.loadUrl("about:blank")
        // Remove key listener when view become invisible
        authWebpage.setOnKeyListener(null)
    }

    private fun saveTokenAndFinish(server: String, username: String, token: String) {
        authWebpage.stopLoading()

        val url = URL(server)
        val accountName = "$username@${url.host}"
        val account = Account(accountName, getString(R.string.account_type_nc))
        val am = AccountManager.get(requireContext())
        am.run {
            if (!reLogin) addAccountExplicitly(account, "", null)
            setAuthToken(account, server, token)    // authTokenType set to server address
            setUserData(account, getString(R.string.nc_userdata_server), server)
            setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
            setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
            setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
            setUserData(account, getString(R.string.nc_userdata_username), username)
            setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("$username:$token".encodeToByteArray(), Base64.NO_WRAP))
            setUserData(account, getString(R.string.nc_userdata_selfsigned), selfSigned.toString())
            notifyAccountAuthenticated(account)
        }

        if (reLogin) parentFragmentManager.popBackStack()
        else requestStoragePermission()
    }

    private fun requestStoragePermission() {
        // Ask for storage access permission so that Camera Roll can be shown at first run
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        welcomePage.visibility = View.GONE
        authWebpage.visibility = View.GONE
        storagePermissionRequestLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun checkServer(hostUrl: String, username: String = "", token: String = "") {
        var result: Int

        if (!Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;\\[\\]]*[-a-zA-Z0-9\\]+&@#/%=~_|]").matcher(hostUrl).matches()) {
            hostInputText.error = getString(R.string.host_address_validation_error)
        } else {
            disableInput()
            // Set a loading spinner for text input view
            inputArea.run {
                endIconMode = TextInputLayout.END_ICON_CUSTOM
                endIconDrawable = loadingSpinner.apply { (this as CircularProgressDrawable).start() }
            }

            CoroutineScope(Dispatchers.Default).launch(Dispatchers.Main) {
                result = pingServer(hostUrl, false)

                when (result) {
                    HttpURLConnection.HTTP_OK -> {
                        // If host verification ok, start loading the nextcloud authentication page in webview
                        if (username.isEmpty()) {
                            // Clean up the input area
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostInputText.windowToken, 0) }
                            disableInput()

                            // the webview will reveal after page loaded
                            authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
                        }
                        else saveTokenAndFinish(hostUrl, username, token)
                    }
                    998 -> {
                        // Ask user to accept self-signed certificate
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
                                        if (username.isEmpty()) authWebpage.loadUrl("$hostUrl${getString(R.string.login_flow_endpoint)}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
                                        else saveTokenAndFinish(hostUrl, username, token)
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
        enableInput()
        inputArea.endIconMode = TextInputLayout.END_ICON_NONE
        hostInputText.error = when(errorCode) {
            999-> getString(R.string.network_error)
            404, 1000-> getString(R.string.unknown_host)
            1001-> getString(R.string.certificate_error)
            else-> getString(R.string.host_not_valid)
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

/*
    private fun getCredential(url: String): HashMap<String, String>? {
        val credential = HashMap<String, String>()
        // Login flow v1 result: nc://login/server:<server>&user:<loginname>&password:<password>
        // QR code scanning result: nc://login/user:<loginname>&password:<password>&server:<server>
        // In case Nextcloud will ever change the return url
        return if (url.startsWith(resources.getString(R.string.nextcloud_credential_scheme))) {
            ("(.*):(.*)&(.*):(.*)&(.*):(.*)").toRegex().matchEntire(url.substringAfter("nc://login/"))?.destructured?.let { (k1, v1, k2, v2, k3, v3) ->
                try {
                    credential[k1] = v1
                    credential[k2] = v2
                    credential[k3] = v3

                    when {
                        credential["server"] == null -> null
                        credential["user"] == null -> null
                        credential["password"] == null -> null
                        else -> credential
                    }
                } catch (e: Exception) { null }
            }
        } else null
    }
*/

    companion object {
        private const val KEY_WEBVIEW_VISIBLE = "KEY_WEBVIEW_VISIBLE"
        private const val KEY_USE_HTTPS = "KEY_USE_HTTPS"
        private const val KEY_AUTH_RESULT = "KEY_AUTH_RESULT"
        private const val KEY_RELOGIN = "KEY_RELOGIN"

        @JvmStatic
        fun newInstance(reLogin: Boolean) = NCLoginFragment().apply { arguments = Bundle().apply { putBoolean(KEY_RELOGIN, reLogin) }}
    }
}