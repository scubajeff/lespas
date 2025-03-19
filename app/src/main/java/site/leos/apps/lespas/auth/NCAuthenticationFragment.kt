/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
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

package site.leos.apps.lespas.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encode
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.publication.NCShareViewModel
import java.net.URL
import java.nio.charset.StandardCharsets

class NCAuthenticationFragment: Fragment() {
    private lateinit var authWebpage: WebView
    private lateinit var authWebpageBG: ViewGroup
    private var sloganView: TextView? = null

    private var reLogin: Boolean = false
    private lateinit var serverTheme: NCLoginFragment.AuthenticateViewModel.NCTheming

    private val authenticateModel: NCLoginFragment.AuthenticateViewModel by activityViewModels { NCLoginFragment.AuthenticateViewModelFactory(requireActivity()) }

    private val scanIntent = Intent("com.google.zxing.client.android.SCAN")
    private var scanRequestLauncher: ActivityResultLauncher<Intent>? = null

    private var currentLoginName = ""

    private lateinit var webviewBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reLogin = requireArguments().getBoolean(KEY_RELOGIN, false)
        serverTheme = requireArguments().parcelable(KEY_THEMING) ?: NCLoginFragment.AuthenticateViewModel.NCTheming()

        webviewBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                authWebpage.goBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, webviewBackPressedCallback)

        if (reLogin) {
            scanRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringExtra("SCAN_RESULT")?.let { scanResult ->
                        ("nc://login/user:(.*)&password:(.*)&server:(.*)").toRegex().matchEntire(scanResult)?.destructured?.let { (username, token, server) ->
                            authenticateModel.fetchUserId(server, username, token, false)
                        }
                    }
                }

                // TODO Show scan error
            }

            // Get current account loginName
            currentLoginName = authenticateModel.getCredential().loginName
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_nc_authentication, container, false)

    @SuppressLint("SetJavaScriptEnabled", "ApplySharedPref")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set content below action toolbar if launch from Setting
        if (!reLogin) (requireActivity() as AppCompatActivity).supportActionBar?.hide()

        authWebpageBG = view.findViewById(R.id.webview_background)
        authWebpage = view.findViewById<WebView>(R.id.webview).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.apply {
                        if (this.scheme.equals(resources.getString(R.string.nextcloud_credential_scheme))) {
                            // Detected Nextcloud server authentication return special uri scheme: "nc://login/server:<server>&user:<loginname>&password:<password>"
                            ("/server:(.*)&user:(.*)&password:(.*)").toRegex().matchEntire(this.path.toString())?.destructured?.let { (server, username, token) ->
                                authenticateModel.fetchUserId(server, username, token, !reLogin)
                            } ?: run {
                                // Can't parse Nextcloud server's return
                                parentFragmentManager.setFragmentResult(KEY_AUTHENTICATION_REQUEST, bundleOf(KEY_AUTHENTICATION_RESULT to false))
                                parentFragmentManager.popBackStack()
                            }

                            // Don't load this page in webview
                            return true
                        }
                    }

                    // Continue loading in webview
                    return false
                }

                override fun onPageFinished(webView: WebView?, url: String?) {
                    super.onPageFinished(webView, url)

                    webView?.let {
                        // Reveal webview after Nextcloud login process landing page loaded
                        if (webView.alpha == 0f) {
                            //authWebpageBG.background.let { if (it is AnimatedVectorDrawable) it.stop() }
                            authWebpageBG.setBackgroundColor(ContextCompat.getColor(webView.context, R.color.color_background))
                            sloganView?.clearAnimation()

                            authWebpage.apply {
                                alpha = 0f
                                animate().alpha(1f).setDuration(resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()).setListener(object: AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        authWebpage.alpha = 1f
                                        requestFocus()
                                        super.onAnimationEnd(animation)
                                    }
                                })
                            }
                        }
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    request?.let { if (it.url.toString().contains(LOGIN_FLOW_ENDPOINT)) view?.reload() }   // TODO: better error handling
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                // Have to allow self-signed certificate
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    error?.let {
                        if (authenticateModel.getCredential().selfSigned) {
                            when(error.primaryError) {
                                SslError.SSL_UNTRUSTED -> {
                                    try {
                                        authenticateModel.getCredential().certificate?.let { cert ->
                                            // TODO is this comparison enough?
                                            if (error.certificate.toString() == SslCertificate(cert).toString()) handler?.proceed() else handler?.cancel()
                                        } ?: handler?.cancel()
                                    } catch (_: Exception) { handler?.cancel() }
                                }
                                SslError.SSL_IDMISMATCH -> handler?.proceed()
                                else -> handler?.cancel()
                            }
                        } else handler?.cancel()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    webviewBackPressedCallback.isEnabled = authWebpage.canGoBack()
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

            view.findViewById<FrameLayout>(R.id.theme_background).setBackgroundColor(serverTheme.color)
            if (serverTheme.slogan.isNotEmpty()) {
                sloganView = view.findViewById<TextView>(R.id.slogan).apply {
                    text = serverTheme.slogan
                    setTextColor(serverTheme.textColor)

                    alpha = 0.2f
                    ObjectAnimator.ofFloat(this, "alpha", 1f).run {
                        duration = 800
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            } else {
                authWebpageBG.background = (ContextCompat.getDrawable(authWebpageBG.context, R.drawable.animated_loading_indicator_lv) as AnimatedVectorDrawable).apply {
                    setTintList(ColorStateList.valueOf(serverTheme.color))
                    setTintMode(PorterDuff.Mode.ADD)
                    start()
                }
            }

            authenticateModel.getCredential().run {
                if (loginName.isEmpty() || reLogin) authWebpage.loadUrl("${serverUrl}${LOGIN_FLOW_ENDPOINT}", HashMap<String, String>().apply { put(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true") })
                else authenticateModel.fetchUserId(serverUrl, loginName, token, true)
            }
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(NC_AUTHENTICATION_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                CONFIRM_NEW_ACCOUNT_DIALOG -> {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) {
                        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                        requireActivity().finish()
                        // TODO allow user re-login to a different account
                    } else parentFragmentManager.popBackStack()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    authenticateModel.fetchUserIdResult.collect { success ->
                        if (success) prepareCredentialAndQuit()
                        else {
                            // Can't get userId
                            parentFragmentManager.setFragmentResult(KEY_AUTHENTICATION_REQUEST, bundleOf(KEY_AUTHENTICATION_RESULT to false))
                            parentFragmentManager.popBackStack()
                        }
                    }
                }
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                if (reLogin) inflater.inflate(R.menu.authentication_menu, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                if (reLogin) {
                    (scanIntent.resolveActivity(requireContext().packageManager) != null).let { scannerAvailable ->
                        menu.findItem(R.id.option_menu_qr_scanner)?.run {
                            isEnabled = scannerAvailable
                            isVisible = scannerAvailable
                        }
                    }
                }
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                return when(item.itemId) {
                    R.id.option_menu_qr_scanner -> {
                        try { scanRequestLauncher?.launch(scanIntent) }
                        catch (e: SecurityException) {
                            if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.should_allow_launching_other_app), requestKey = NC_AUTHENTICATION_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        if (!reLogin) requireActivity().window.statusBarColor = serverTheme.color
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // UninitializedPropertyAccessException will throw when screen rotates in NCSelectHomeFragment
        try { authWebpage.saveState(outState) } catch(_: UninitializedPropertyAccessException) {}
    }

    override fun onDestroyView() {
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.color_primary)
        authWebpage.stopLoading()
        super.onDestroyView()
    }

    private fun prepareCredentialAndQuit() {
        if (reLogin) {
            if (authenticateModel.getCredential().loginName != currentLoginName) {
                // Re-login to a new account
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.login_to_new_account), positiveButtonText = getString(R.string.yes_logout), individualKey = CONFIRM_NEW_ACCOUNT_DIALOG, requestKey = NC_AUTHENTICATION_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
            } else {
                saveAccount()
                parentFragmentManager.popBackStack()
            }
        } else {
            saveAccount()

            // Time to set home folder on server
            parentFragmentManager.beginTransaction().replace(R.id.container_root, NCSelectHomeFragment.newInstance(serverTheme), NCSelectHomeFragment::class.java.canonicalName).commit()
        }
    }

    private fun saveAccount() {
        val credential = authenticateModel.getCredential()
        val url = URL(credential.serverUrl)
        val account: Account

        AccountManager.get(requireContext()).run {
            if (reLogin) {
                getAccountsByType(getString(R.string.account_type_nc)).run {
                    if (this.isNotEmpty()) {
                        account = this[0]
                        setAuthToken(account, credential.serverUrl, credential.token)    // authTokenType set to server address
                        setUserData(account, getString(R.string.nc_userdata_secret), "${credential.loginName}:${credential.token}".encode(StandardCharsets.ISO_8859_1).base64())
                        ViewModelProvider(requireActivity())[NCShareViewModel::class.java].updateWebDavAccessToken(requireContext())
                    } else return
                }
            } else {
                account = Account("${credential.loginName}@${url.host}", getString(R.string.account_type_nc))
                addAccountExplicitly(account, "", null)
                setAuthToken(account, credential.serverUrl, credential.token)    // authTokenType set to server address
                setUserData(account, getString(R.string.nc_userdata_server), credential.serverUrl)
                setUserData(account, getString(R.string.nc_userdata_server_protocol), url.protocol)
                setUserData(account, getString(R.string.nc_userdata_server_host), url.host)
                setUserData(account, getString(R.string.nc_userdata_server_port), url.port.toString())
                setUserData(account, getString(R.string.nc_userdata_loginname), credential.loginName)
                setUserData(account, getString(R.string.nc_userdata_username), credential.userName)
                setUserData(account, getString(R.string.nc_userdata_secret), "${credential.loginName}:${credential.token}".encode(StandardCharsets.ISO_8859_1).base64())
                setUserData(account, getString(R.string.nc_userdata_selfsigned), credential.selfSigned.toString())
                setUserData(account, getString(R.string.nc_userdata_certificate), credential.certificateString)     // User's self-signed certificate byteString encoded in Base64
            }

            lifecycleScope.launch { notifyAccountAuthenticated(account) }
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
        private const val NC_AUTHENTICATION_REQUEST_KEY = "NC_AUTHENTICATION_REQUEST_KEY"
        private const val CONFIRM_NEW_ACCOUNT_DIALOG = "CONFIRM_NEW_ACCOUNT_DIALOG"

        private const val KEY_RELOGIN = "KEY_RELOGIN"
        private const val KEY_THEMING = "KEY_THEMING"

        const val KEY_AUTHENTICATION_REQUEST = "KEY_AUTHENTICATION_REQUEST"
        const val KEY_AUTHENTICATION_RESULT = "KEY_AUTHENTICATION_RESULT"

        @JvmStatic
        fun newInstance(reLogin: Boolean, theming: NCLoginFragment.AuthenticateViewModel.NCTheming = NCLoginFragment.AuthenticateViewModel.NCTheming()) = NCAuthenticationFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_RELOGIN, reLogin)
                putParcelable(KEY_THEMING, theming)
            }
        }
    }
}