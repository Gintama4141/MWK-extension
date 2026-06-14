package com.mwk.idlix

import com.lagradost.cloudstream3.extractors.helper.AesHelper
import android.util.Base64

object IdlixCrypto {

    fun createIdlixKey(r: String, m: String): String {
        val rList = r.split("\\\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            Base64.decode(reversedM, Base64.DEFAULT)
        } catch (_: Exception) { return "" }
        val decodedM = String(decodedBytes)
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) n += "\\\\x" + rList[index]
            } catch (_: Exception) {}
        }
        return n
    }

    fun decryptEmbedUrl(embedUrl: String, key: String, metrix: String): String? {
        val password = createIdlixKey(key, metrix)
        return try {
            AesHelper.cryptoAESHandler(embedUrl, password.toByteArray(), false)?.fixUrlBloat()
        } catch (_: Exception) { null }
    }

    private fun String.fixUrlBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }
}
