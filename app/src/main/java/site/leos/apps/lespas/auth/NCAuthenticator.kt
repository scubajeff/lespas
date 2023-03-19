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

import android.accounts.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import site.leos.apps.lespas.R
import java.net.URL

class NCAuthenticator(private val mContext: Context): AbstractAccountAuthenticator(mContext) {
    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun getAuthTokenLabel(authTokenType: String?): String {
        return try {
            // authTokenType is set to server's url during account creation
            "Full access to ${URL(authTokenType).host}"
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Throws(NetworkErrorException::class)
    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle {
        val am = AccountManager.get(mContext)
        val authToken = am.peekAuthToken(account, authTokenType)

        // If there is a authtoken, return it
        return if (authToken.isNotEmpty()) {
            Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, account?.name)
                putString(AccountManager.KEY_ACCOUNT_TYPE, account?.type)
                putString(AccountManager.KEY_AUTHTOKEN, authToken)
                //putString(AccountManager.KEY_AUTHENTICATOR_TYPES, server)

                val userName = mContext.getString(R.string.nc_userdata_username)
                val loginName = mContext.getString(R.string.nc_userdata_loginname)
                val secretKey = mContext.getString(R.string.nc_userdata_secret)
                putString(userName, am.getUserData(account, userName))
                putString(loginName, am.getUserData(account, loginName))
                putString(secretKey, am.getUserData(account, secretKey))
            }
        } else {
            // If we get here, then we couldn't access the user's password - so we need to re-prompt them for their credentials. We do that by creating
            // an intent to display our AuthenticatorActivity.
            getBundle(response)
        }
    }

    @Throws(NetworkErrorException::class)
    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?): Bundle {
        val am = AccountManager.get(mContext)
        val accounts: Array<Account> = am.getAccountsByType(accountType)

        return if (accounts.isEmpty()) {
            getBundle(response)
        } else {
            Handler(mContext.mainLooper).post { Toast.makeText(mContext, R.string.error_only_one_account, Toast.LENGTH_LONG).show() }

            Bundle().apply {
                putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_BAD_REQUEST)
                putString(AccountManager.KEY_ERROR_MESSAGE, mContext.getString(R.string.error_only_one_account))
            }
        }
    }

    private fun getBundle(response: AccountAuthenticatorResponse?): Bundle {
        val intent = Intent(mContext, NCLoginActivity::class.java)
        //intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, accountType)
        //intent.putExtra(AuthenticatorActivity.ARG_AUTH_TYPE, authTokenType)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val bundle = Bundle()
        bundle.putParcelable(AccountManager.KEY_INTENT, intent)
        return bundle
    }
}