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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.Html
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encode
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import java.io.ByteArrayOutputStream
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.*

class NCLoginFragment: Fragment() {
    private lateinit var inputArea: TextInputLayout
    private lateinit var hostEditText: TextInputEditText

    private var doAnimation = true

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>

    private var scannerAvailable = false
    private val scanIntent = Intent("com.google.zxing.client.android.SCAN")
    private lateinit var scanRequestLauncher: ActivityResultLauncher<Intent>

    private val authenticateModel: AuthenticateViewModel by activityViewModels { AuthenticateViewModelFactory(requireActivity()) }

    private lateinit var pingJobBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scannerAvailable = scanIntent.resolveActivity(requireContext().packageManager) != null

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Handler(Looper.getMainLooper()).post {
                // Restart activity
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
                        hostEditText.setText(server.substringAfter("://"))
                        checkServer(server, username, token)
                    }
                }
            }

            // TODO Show scan error
        }

        pingJobBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (authenticateModel.isPinging()) authenticateModel.stopPinging()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, pingJobBackPressedCallback)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(if (savedInstanceState == null && doAnimation) R.layout.fragment_nc_login_motion else R.layout.fragment_nc_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Start background animation
        (view.findViewById<ConstraintLayout>(R.id.layout_background).background as AnimationDrawable).run {
            setEnterFadeDuration(3000)
            setExitFadeDuration(3000)
            start()
        }

        inputArea = view.findViewById<TextInputLayout>(R.id.input_area).apply {
            findViewById<TextView>(com.google.android.material.R.id.textinput_prefix_text).setOnClickListener {
                authenticateModel.toggleUseHttps()
                inputArea.prefixText = if (authenticateModel.getCredential().https) "https://" else "http://"
            }
        }
        hostEditText = view.findViewById<TextInputEditText>(R.id.host).apply {
            setOnEditorActionListener { _, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_GO || keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                    checkServer((if (authenticateModel.getCredential().https) "https://" else "http://") + text.toString().trim())
                    true
                } else false
            }
            doOnTextChanged { _, _, _, _ ->
                error?.let {
                    setEndIconMode(ICON_MODE_INPUT)
                    error = null
                }
            }
        }

        authenticateModel.getPingResult().observe(viewLifecycleOwner) { result ->
            pingJobBackPressedCallback.isEnabled = false
            when (result) {
                200 -> {
                    // If host verification ok, start loading the nextcloud authentication page in webview

                    // Clean up the input area
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostEditText.windowToken, 0) }
                    setEndIconMode(ICON_MODE_INPUT)

                    parentFragmentManager.beginTransaction().replace(R.id.container_root, NCAuthenticationFragment.newInstance(false, authenticateModel.getTheming()), NCAuthenticationFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
                998 -> {
                    // Prompt user to accept self-signed certificate
                    val cert = authenticateModel.getCredential().certificate
                    AlertDialog.Builder(hostEditText.context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setIcon(ContextCompat.getDrawable(hostEditText.context, android.R.drawable.ic_dialog_alert)?.apply { setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)) })
                        .setTitle(getString(R.string.verify_ssl_certificate_title))
                        .setCancelable(false)
                        .setMessage(
                            if (cert == null)
                                // SSLPeerUnverifiedException
                                getString(R.string.verify_ssl_certificate_message, authenticateModel.getCredential().serverUrl.substringAfterLast("://").substringBefore('/'))
                            else {
                                // CertValidatorException
                                val dFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                                getString(
                                    R.string.untrusted_ssl_certificate_message,
                                    cert.subjectDN.name,
                                    cert.issuerDN.name,
                                    try { MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(separator = "") { eachByte -> "%02x:".format(eachByte).uppercase(Locale.ROOT) }.dropLast(1) } catch(_: Exception) { "" },
                                    try { dFormat.format(cert.notBefore) } catch (_: Exception) { "" },
                                    try { dFormat.format(cert.notAfter) } catch (_: Exception) { "" }
                                )
                            }
                        )
                        .setPositiveButton(R.string.accept_certificate) { _, _ ->
                            authenticateModel.pingServer(null, true)
                            pingJobBackPressedCallback.isEnabled = true
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> showError(1001) }
                        .create().show()
                }
                else -> showError(result)
            }
        }

        authenticateModel.getAuthResult().observe(viewLifecycleOwner) { success ->
            if (success) {
                // Ask for storage access permission so that Camera Roll can be shown at first run, fragment quits after return from permission granting dialog closed
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                storagePermissionRequestLauncher.launch(Tools.getStoragePermissionsArray())
            } else showError(999)
        }

        // Take care edittext UX
        when {
            savedInstanceState == null -> setEndIconMode(ICON_MODE_INPUT)
            authenticateModel.isPinging() -> disableInputWhilePinging()
            else -> setEndIconMode(if (savedInstanceState.getBoolean(KEY_ERROR_SHOWN, false)) ICON_MODE_ERROR else ICON_MODE_INPUT)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            outState.putBoolean(KEY_ERROR_SHOWN, hostEditText.error != null)
        } catch (e: UninitializedPropertyAccessException) {
            outState.putBoolean(KEY_ERROR_SHOWN, false)
        }
    }

    override fun onDestroyView() {
        doAnimation = false
        super.onDestroyView()
    }

    private fun disableInputWhilePinging() {
        hostEditText.isEnabled = false
        hostEditText.error = null
        setEndIconMode(ICON_MODE_PINGING)
    }

    private fun setEndIconMode(mode: Int) {
        when(mode) {
            ICON_MODE_ERROR -> {
                inputArea.endIconMode = TextInputLayout.END_ICON_NONE
                inputArea.endIconDrawable = null
                inputArea.setEndIconOnClickListener {  }
            }
            ICON_MODE_INPUT -> {
                if (scannerAvailable) {
                    inputArea.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    inputArea.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_qr_code_scanner_24)
                    inputArea.setEndIconOnClickListener {
                        try { scanRequestLauncher.launch(scanIntent) }
                        catch (e: SecurityException) {
                            if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.should_allow_launching_other_app)).show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                    }
                } else {
                    inputArea.endIconMode = TextInputLayout.END_ICON_NONE
                    hostEditText.error = null
                }
                inputArea.requestFocus()
            }
            ICON_MODE_PINGING -> {
                inputArea.endIconMode = TextInputLayout.END_ICON_CUSTOM
                inputArea.endIconDrawable = authenticateModel.getLoadingIndicatorDrawable()
                inputArea.setEndIconOnClickListener {  }
            }
        }
    }

    private fun showError(errorCode: Int) {
        setEndIconMode(ICON_MODE_ERROR)
        inputArea.requestFocus()

        hostEditText.isEnabled = true
        hostEditText.error = when(errorCode) {
            0 -> null
            999-> getString(R.string.network_error)
            404, 1000-> getString(R.string.unknown_host)
            1001-> getString(R.string.certificate_error)
            else-> getString(R.string.host_not_valid, errorCode)
        }
    }

    private fun checkServer(hostUrl: String, username: String = "", token: String = "") {
        if (Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;\\[\\]]*[-a-zA-Z0-9\\]+&@#/%=~_|]").matcher(hostUrl).matches()) {
            disableInputWhilePinging()
            authenticateModel.setToken(username, token)
            authenticateModel.pingServer(hostUrl, false)
            pingJobBackPressedCallback.isEnabled = true
            // Reset self-signed certificate
            authenticateModel.setSelfSignedCertificate(null)
            authenticateModel.setSelfSignedCertificateString("")
        } else hostEditText.error = getString(R.string.host_address_validation_error)
    }

    class AuthenticateViewModelFactory(private val context: FragmentActivity): ViewModelProvider.NewInstanceFactory() {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthenticateViewModel(context) as T
    }
    class AuthenticateViewModel(context: FragmentActivity) : AndroidViewModel(context.application) {
        private val credential = NCCredential()
        private var serverTheme = NCTheming().apply {
            color = ContextCompat.getColor(context, R.color.color_background)
            textColor = ContextCompat.getColor(context, R.color.lespas_black)
        }
        private var pingJob: Job? = null
        private var httpCall: Call? = null

        private val pingResult = SingleLiveEvent<Int>()

        private val colorWhite = ContextCompat.getColor(context, R.color.lespas_white)
        private val colorBlack = ContextCompat.getColor(context, R.color.lespas_black)
        @SuppressLint("PrivateResource")
        private var loadingIndicator = IndeterminateDrawable.createCircularDrawable(context, CircularProgressIndicatorSpec(context, null, 0, com.google.android.material.R.style.Widget_MaterialComponents_CircularProgressIndicator_ExtraSmall))
        fun getLoadingIndicatorDrawable(): IndeterminateDrawable<CircularProgressIndicatorSpec> = loadingIndicator

        fun isPinging() = pingJob?.isActive ?: false
        fun stopPinging() { pingJob?.let {
            if (it.isActive) {
                httpCall?.cancel()
            }
        }}
        fun getPingResult() = pingResult
        fun pingServer(serverUrl: String?, acceptSelfSign: Boolean) {
            // Use nextcloud server capabilities OCS endpoint to validate host
            serverUrl?.let { credential.serverUrl = it }
            credential.selfSigned = acceptSelfSign

            pingJob = viewModelScope.launch(Dispatchers.IO) {
                pingResult.postValue(
                    try {
                        httpCall = OkHttpClient.Builder().apply {
                            if (acceptSelfSign) {
                                credential.certificate?.let { cert ->
                                    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                                        init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                                            load(null, null)
                                            setCertificateEntry(credential.serverUrl.substringAfterLast("//").substringBefore('/'), cert)

                                            // Save certificate in Base64 string for storing in Account's user data later
                                            ByteArrayOutputStream().let { output ->
                                                store(output, null)
                                                credential.certificateString = output.toByteArray().toByteString().base64()
                                            }
                                        })
                                        trustManagers
                                    }
                                    sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory, trustManagers[0] as X509TrustManager)
                                }
                                hostnameVerifier { _, _ -> true }
                            }
                            readTimeout(20, TimeUnit.SECONDS)
                            writeTimeout(20, TimeUnit.SECONDS)
                        }.build().newCall(Request.Builder().url("${credential.serverUrl}${NEXTCLOUD_CAPABILITIES_ENDPOINT}").addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").build())

                        httpCall?.execute()?.use { response ->
                            if (response.isSuccessful) {
                                try {
                                    response.body?.string()?.let { json ->
                                        JSONObject(json).getJSONObject("ocs").getJSONObject("data").getJSONObject("capabilities").getJSONObject("theming").run {
                                            try { serverTheme.color = Color.parseColor(getString("color")) } catch (_: Exception) {}
                                            //try { serverTheme.textColor = Color.parseColor(getString("color-text")) } catch (_: Exception) {}
                                            serverTheme.textColor = if (ColorUtils.calculateContrast(colorWhite, serverTheme.color) > 1.5f) colorWhite else colorBlack
                                            try { serverTheme.slogan = Html.fromHtml(getString("slogan"), Html.FROM_HTML_MODE_LEGACY).toString() } catch (_: Exception) {}
                                            200
                                        }
                                    } ?: -1
                                } catch (_: JSONException) { -1 }
                            } else response.code
                        } ?: 999
                    } catch (e: SSLPeerUnverifiedException) {
                        // This certificate is issued by user installed CA, let user decide whether to trust it or not
                        998
                    } catch (e: SSLHandshakeException) {
                        // SSL related error generally means wrong SSL certificate
                        var result = 1001

                        // If it's caused by CertPathValidatorException, then we should extract the untrusted certificate and let user decide
                        var previousCause: Throwable? = null
                        var cause = e.cause
                        while (cause != null && cause != previousCause && cause !is CertPathValidatorException) {
                            previousCause = cause
                            cause = cause.cause
                        }
                        if (cause != null && cause is CertPathValidatorException && cause.certPath.certificates.size > 0) {
                            try {
                                credential.certificate = cause.certPath.certificates[if (cause.index == -1) 0 else cause.index] as X509Certificate
                                result = 998
                            } catch (_: Exception) {}
                        }

                        result
                    } catch (e: KeyStoreException) {
                        1001
                    } catch (e: NoSuchAlgorithmException) {
                        1001
                    } catch (e: KeyManagementException) {
                        1001
                    } catch (e: UnknownHostException) {
                        1000
                    } catch (e: SocketException) {
                        httpCall?.let { if (it.isCanceled()) 0 else 999 } ?: 999
                    } catch (e: Exception) {
                        999
                    }
                )

                pingJob = null
            }
        }

        private val fetchUserIdResult = SingleLiveEvent<Boolean>()
        fun fetchUserIdResult() = fetchUserIdResult
        fun fetchUserId(server: String, username: String, token: String, willFetch: Boolean) {
            // As stated in <a href="https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html#obtaining-the-login-credentials">Nextcloud document</a>:
            // The server may specify a protocol (http or https). If no protocol is specified the client will assume https.
            val host = if (server.startsWith("http")) server else "https://${server}"

            setToken(username, token, host)

            if (willFetch) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        httpCall = OkHttpClient.Builder().apply {
                            if (credential.selfSigned) {
                                hostnameVerifier { _, _ -> true }
                                try {
                                    credential.certificate?.let { cert ->
                                        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                                            init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                                                load(null, null)
                                                setCertificateEntry(credential.serverUrl.substringAfterLast("//").substringBefore('/'), cert)
                                            })
                                            trustManagers
                                        }
                                        sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory, trustManagers[0] as X509TrustManager)
                                    }
                                } catch (_: Exception) {}
                            }
                            readTimeout(20, TimeUnit.SECONDS)
                            writeTimeout(20, TimeUnit.SECONDS)
                            addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Basic ${"${credential.loginName}:${credential.token}".encode(StandardCharsets.ISO_8859_1).base64()}").build()) }
                        }.build().newCall(Request.Builder().url("${credential.serverUrl}${NEXTCLOUD_USER_ENDPOINT}").addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").build())

                        httpCall?.execute()?.use { response ->
                            if (response.isSuccessful) {
                                response.body?.string()?.let { json ->
                                    credential.userName = JSONObject(json).getJSONObject("ocs").getJSONObject("data").getString("id")
                                    fetchUserIdResult.postValue(true)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        fetchUserIdResult.postValue(false)
                    }
                }
            } else fetchUserIdResult.postValue(true)
        }

        fun setToken(username: String, token: String, serverUrl: String = "", userId: String = "") {
            credential.loginName = username
            credential.token = token
            credential.serverUrl = serverUrl
            credential.userName = userId
        }
        fun toggleUseHttps() { credential.https = !credential.https }
        fun setSelfSigned(selfSigned: Boolean) { credential.selfSigned = selfSigned }
        fun setSelfSignedCertificate(certificate: X509Certificate?) { credential.certificate = certificate }
        fun setSelfSignedCertificateString(certificateString: String) { credential.certificateString = certificateString }
        fun getCredential() = credential
        fun getTheming() = serverTheme

        private val authResult = SingleLiveEvent<Boolean>()
        fun getAuthResult() = authResult
        fun setAuthResult(result: Boolean) { this.authResult.postValue(result) }

        data class NCCredential(
            var serverUrl: String = "",
            var loginName: String = "",
            var token: String = "",
            var selfSigned: Boolean = false,
            var https: Boolean = true,
            var userName: String = "",
            var certificate: X509Certificate? = null,
            var certificateString: String = ""
        )

        @Parcelize
        data class NCTheming(
            var slogan: String = "",
            var color: Int = Color.TRANSPARENT,
            var textColor: Int = Color.WHITE,
        ): Parcelable

        companion object {
            private const val NEXTCLOUD_CAPABILITIES_ENDPOINT = "/ocs/v2.php/cloud/capabilities?format=json"
            private const val NEXTCLOUD_USER_ENDPOINT = "/ocs/v1.php/cloud/user?format=json"
        }
    }

    companion object {
        private const val ICON_MODE_INPUT = 1
        private const val ICON_MODE_PINGING = 2
        private const val ICON_MODE_ERROR = 3

        private const val KEY_ERROR_SHOWN = "KEY_ERROR_SHOWN"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
    }
}