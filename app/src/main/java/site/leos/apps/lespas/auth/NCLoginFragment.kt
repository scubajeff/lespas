package site.leos.apps.lespas.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.SingleLiveEvent
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class NCLoginFragment: Fragment() {
    private lateinit var inputArea: TextInputLayout
    private lateinit var hostEditText: TextInputEditText
    private lateinit var loadingSpinner: Drawable

    private var doAnimation = true

    private var storagePermissionRequestLauncher: ActivityResultLauncher<String>? = null

    private var scannerAvailable = false
    private val scanIntent = Intent("com.google.zxing.client.android.SCAN")
    private var scanRequestLauncher: ActivityResultLauncher<Intent>? = null

    private val authenticateModel: AuthenticateViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scannerAvailable = scanIntent.resolveActivity(requireContext().packageManager) != null

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
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

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authenticateModel.isPinging()) authenticateModel.stopPinging() else requireActivity().finish()
            }
        })
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
                inputArea.prefixText = if (authenticateModel.getAccount().https) "https://" else "http://"
            }
        }
        hostEditText = view.findViewById<TextInputEditText>(R.id.host).apply {
            setOnEditorActionListener { _, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_GO || keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                    checkServer((if (authenticateModel.getAccount().https) "https://" else "http://") + text.toString().trim())
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
        loadingSpinner = CircularProgressDrawable(requireContext()).apply {
            strokeWidth = 6.0f
            centerRadius = 16.0f
            setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.color_primary))
            hostEditText.textSize.toInt().let { setBounds(0, 0, it, it) }
        }

        authenticateModel.getPingResult().observe(viewLifecycleOwner) { result ->
            when (result) {
                200 -> {
                    // If host verification ok, start loading the nextcloud authentication page in webview

                    // Clean up the input area
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(hostEditText.windowToken, 0) }
                    setEndIconMode(ICON_MODE_INPUT)

                    parentFragmentManager.beginTransaction().replace(R.id.container_root, NCAuthenticationFragment.newInstance(false), NCAuthenticationFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
                998 -> {
                    // Ask user to accept self-signed certificate
                    AlertDialog.Builder(hostEditText.context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setIcon(ContextCompat.getDrawable(hostEditText.context, android.R.drawable.ic_dialog_alert)?.apply { setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)) })
                        .setTitle(getString(R.string.verify_ssl_certificate_title))
                        .setCancelable(false)
                        .setMessage(getString(R.string.verify_ssl_certificate_message, authenticateModel.getAccount().serverUrl.substringAfterLast("://").substringBefore('/')))
                        .setPositiveButton(R.string.accept_certificate) { _, _ -> authenticateModel.pingServer(null, true) }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> showError(1001) }
                        .create().show()
                }
                else -> showError(result)
            }
        }

        authenticateModel.getAuthResult().observe(viewLifecycleOwner) { result ->
            if (result == AuthenticateViewModel.RESULT_SUCCESS) {
                // Ask for storage access permission so that Camera Roll can be shown at first run, fragment quits after return from permission granting dialog closed
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                storagePermissionRequestLauncher?.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        outState.putBoolean(KEY_ERROR_SHOWN, hostEditText.error != null)
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
                    inputArea.setEndIconOnClickListener { scanRequestLauncher?.launch(scanIntent) }
                } else {
                    inputArea.endIconMode = TextInputLayout.END_ICON_NONE
                    hostEditText.error = null
                }
                inputArea.requestFocus()
            }
            ICON_MODE_PINGING -> {
                inputArea.endIconMode = TextInputLayout.END_ICON_CUSTOM
                inputArea.endIconDrawable = loadingSpinner.apply { (this as CircularProgressDrawable).start() }
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
            else-> getString(R.string.host_not_valid)
        }
    }

    private fun checkServer(hostUrl: String, username: String = "", token: String = "") {
        if (Pattern.compile("^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;\\[\\]]*[-a-zA-Z0-9\\]+&@#/%=~_|]").matcher(hostUrl).matches()) {
            disableInputWhilePinging()
            authenticateModel.setToken(username, token)
            authenticateModel.pingServer(hostUrl, false)
        } else hostEditText.error = getString(R.string.host_address_validation_error)
    }

    class AuthenticateViewModel : ViewModel() {
        private val account = NCAccount()
        private var pingJob: Job? = null
        private var httpCall: Call? = null

        // Use nextcloud server capabilities OCS endpoint to validate host
        fun pingServer(serverUrl: String?, acceptSelfSign: Boolean) {
            serverUrl?.let { account.serverUrl = it }
            account.selfSigned = acceptSelfSign

            pingJob = viewModelScope.launch(Dispatchers.IO) {
                pingResult.postValue(
                    try {
                        httpCall = OkHttpClient.Builder().apply {
                            if (acceptSelfSign) hostnameVerifier { _, _ -> true }
                            readTimeout(20, TimeUnit.SECONDS)
                            writeTimeout(20, TimeUnit.SECONDS)
                        }.build().newCall(Request.Builder().url("${serverUrl}${NEXTCLOUD_CAPABILITIES_ENDPOINT}").addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").build())

                        httpCall?.execute().use { it?.code ?: 0}
                    } catch (e: SSLPeerUnverifiedException) {
                        // This certificate is issued by user installed CA, let user decided whether to trust it or not
                        998
                    } catch (e: SSLHandshakeException) {
                        // SSL related error generally means wrong SSL certificate
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

        private val pingResult = SingleLiveEvent<Int>()
        fun getPingResult() = pingResult

        fun toggleUseHttps() { account.https = !account.https }
        fun setToken(username: String, token: String, serverUrl: String? = null) {
            account.username = username
            account.token = token
            serverUrl?.let { account.serverUrl = it }
        }
        fun setSelfSigned(selfSigned: Boolean) { account.selfSigned = selfSigned }
        fun getAccount() = account

        fun isPinging() = pingJob?.isActive ?: false
        fun stopPinging() { pingJob?.let {
            if (it.isActive) {
                httpCall?.cancel()
            }
        }}

        private val authResult = SingleLiveEvent<Int>()
        fun getAuthResult() = authResult
        fun setAuthResult(result: Int) { this.authResult.postValue(result) }

        data class NCAccount(
            var serverUrl: String = "",
            var username: String = "",
            var token: String = "",
            var selfSigned: Boolean = false,
            var https: Boolean = true,
        )

        companion object {
            const val RESULT_SUCCESS = 0
            const val RESULT_FAIL = 1

            private const val NEXTCLOUD_CAPABILITIES_ENDPOINT = "/ocs/v2.php/cloud/capabilities?format=json"
        }
    }

    companion object {
        private const val ICON_MODE_INPUT = 1
        private const val ICON_MODE_PINGING = 2
        private const val ICON_MODE_ERROR = 3

        private const val KEY_ERROR_SHOWN = "KEY_ERROR_SHOWN"
    }
}