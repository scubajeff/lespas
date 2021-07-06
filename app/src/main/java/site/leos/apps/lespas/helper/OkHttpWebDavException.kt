package site.leos.apps.lespas.helper

import okhttp3.Response

class OkHttpWebDavException(response: Response): Exception() {
    val statusCode = response.code
    val stackTraceString = "****Exception ${this.statusCode}****: ${this.stackTraceToString()}"
}