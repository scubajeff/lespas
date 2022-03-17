package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import java.net.URL

class NCAuthenticationFragment: Fragment() {
    private lateinit var authWebpage: WebView

    private var reLogin: Boolean = false

    private val authenticateModel: NCLoginFragment.AuthenticateViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reLogin = requireArguments().getBoolean(KEY_RELOGIN, false)

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authWebpage.canGoBack()) authWebpage.goBack() else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_nc_authentication, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (reLogin) (requireActivity() as AppCompatActivity).supportActionBar?.let { view.setPadding(view.paddingLeft, it.height, view.paddingRight, 0) }

        authWebpage = view.findViewById<WebView>(R.id.webview).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.apply {
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) {
                            // Detected Nextcloud server authentication return special uri scheme: "nc://login/server:<server>&user:<loginname>&password:<password>"
                            ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(this.path.toString())?.destructured?.let { (server, username, token) ->
                                // As stated in <a href="https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html#obtaining-the-login-credentials">Nextcloud document</a>:
                                // The server may specify a protocol (http or https). If no protocol is specified the client will assume https.
                                val host = if (server.startsWith("http")) server else "https://${server}"
                                val currentUsername = authenticateModel.getAccount().username

                                authenticateModel.setToken(username, token, host)

                                if (reLogin) {
                                    if (username != currentUsername) {
                                        // Re-login to a new account
                                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                                            ConfirmDialogFragment.newInstance(getString(R.string.login_to_new_account), getString(R.string.yes_logout), true, CONFIRM_NEW_ACCOUNT_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                                    } else {
                                        saveToken()
                                        parentFragmentManager.popBackStack()
                                    }
                                } else {
                                    saveToken()
                                    authenticateModel.setAuthResult(NCLoginFragment.AuthenticateViewModel.RESULT_SUCCESS)
                                    parentFragmentManager.popBackStack()
                                }
                            } ?: run {
                                // Can't parse Nextcloud server's return
                                if (reLogin) {
                                    // TODO prompt user of failure
                                } else authenticateModel.setAuthResult(NCLoginFragment.AuthenticateViewModel.RESULT_FAIL)

                                parentFragmentManager.popBackStack()
                            }

                            // Don't load this uri with webview
                            return true
                        }
                    }

                    // Continue loading in webview
                    return false
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (errorResponse != null) view?.reload()   // TODO: better error handling
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onPageFinished(webView: WebView?, url: String?) {
                    super.onPageFinished(webView, url)

                    webView?.let {
                        if (webView.alpha == 0f) {
                            authWebpage.apply {
                                alpha = 0f
                                animate().alpha(1f).duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                                requestFocus()
                            }
                        }
                    }
                }

                // Have to allow self-signed certificate
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    if (error?.primaryError == SslError.SSL_IDMISMATCH && authenticateModel.getAccount().selfSigned) handler?.proceed() else handler?.cancel()
                }
            }

            settings.apply {
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "${resources.getString(R.string.app_name)} on ${Tools.getDeviceModel()}"
                javaScriptEnabled = true
            }

            savedInstanceState ?: run {
                CookieManager.getInstance().removeAllCookies(null)
                clearCache(true)
            }
        }

        savedInstanceState?.let {
            authWebpage.restoreState(it)
        } ?: run {
            // Show a loading sign first
            authWebpage.alpha = 0f
            view.findViewById<ConstraintLayout>(R.id.webview_background).background = (ContextCompat.getDrawable(requireContext(), R.drawable.animated_placeholder) as AnimatedVectorDrawable).apply { start() }

            authWebpage.loadUrl("${authenticateModel.getAccount().serverUrl}${LOGIN_FLOW_ENDPOINT}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                CONFIRM_NEW_ACCOUNT_DIALOG -> {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                        requireActivity().packageManager.setComponentEnabledSetting(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                        requireActivity().finish()
                        // TODO allow user re-login to a different account
                    } else parentFragmentManager.popBackStack()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        authWebpage.saveState(outState)
    }

    private fun saveToken() {
        val ncAccount = authenticateModel.getAccount()
        val url = URL(ncAccount.serverUrl)
        val account: Account

        AccountManager.get(requireContext()).run {
            if (!reLogin) {
                account = Account("${ncAccount.username}@${url.host}", getString(R.string.account_type_nc))
                addAccountExplicitly(account, "", null)
            } else {
                account = getAccountsByType(getString(R.string.account_type_nc))[0]
            }

            setAuthToken(account, ncAccount.serverUrl, ncAccount.token)    // authTokenType set to server address
            setUserData(account, getString(R.string.nc_userdata_server), ncAccount.serverUrl)
            setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
            setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
            setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
            setUserData(account, getString(R.string.nc_userdata_username), ncAccount.username)
            setUserData(account, getString(R.string.nc_userdata_secret), Base64.encodeToString("${ncAccount.username}:${ncAccount.token}".encodeToByteArray(), Base64.NO_WRAP))
            setUserData(account, getString(R.string.nc_userdata_selfsigned), ncAccount.selfSigned.toString())
            notifyAccountAuthenticated(account)
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
        private const val LOGIN_FLOW_ENDPOINT = "/index.php/login/flow"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val CONFIRM_NEW_ACCOUNT_DIALOG = "CONFIRM_NEW_ACCOUNT_DIALOG"

        private const val KEY_RELOGIN = "KEY_RELOGIN"
        @JvmStatic
        fun newInstance(reLogin: Boolean) = NCAuthenticationFragment().apply { arguments = Bundle().apply { putBoolean(KEY_RELOGIN, reLogin) }}
    }
}