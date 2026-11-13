package com.docscan.rebuild.tools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** User-configured HTTPS service. No default provider, embedded key, background upload or redirects. */
class AiService(context:Context) {
    private val prefs=context.getSharedPreferences("optional-ai",Context.MODE_PRIVATE)
    val endpoint get()=prefs.getString("endpoint","").orEmpty()
    val model get()=prefs.getString("model","").orEmpty()
    val configured get()=endpoint.isNotBlank() && model.isNotBlank()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return (store.getKey("docscan-ai",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("docscan-ai",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(endpoint:String,model:String,apiKey:String) {
        val uri=URI(endpoint.trim());require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null)
        require(model.isNotBlank() && model.length<=200 && apiKey.length<=8000 && apiKey.none { it=='\r' || it=='\n' })
        val edit=prefs.edit().putString("endpoint",uri.toString()).putString("model",model.trim())
        if(uri.toString()!=this.endpoint)edit.remove("secret").remove("iv")
        if(apiKey.isNotEmpty()) {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
            edit.putString("secret",Base64.encodeToString(cipher.doFinal(apiKey.toByteArray()),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
        }
        check(edit.commit())
    }
    fun forget(){check(prefs.edit().clear().commit());KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias("docscan-ai"))deleteEntry("docscan-ai")}}
    private fun secret():String {
        val data=prefs.getString("secret",null)?:return "";val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",null),Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    suspend fun send(instruction:String,text:String,imageBase64:String?=null):String=withContext(Dispatchers.IO) {
        require(configured && text.length<=100000 && instruction.length<=4000)
        val content:Any=if(imageBase64==null)text else JSONArray().put(JSONObject().put("type","text").put("text",text)).put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,$imageBase64")))
        val request=JSONObject().put("model",model).put("messages",JSONArray().put(JSONObject().put("role","system").put("content",instruction)).put(JSONObject().put("role","user").put("content",content))).put("stream",false)
        val connection=URI(endpoint).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=60000
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
            val token=secret();if(token.isNotEmpty())connection.setRequestProperty("Authorization","Bearer $token")
            connection.outputStream.use {it.write(request.toString().toByteArray())}
            check(connection.responseCode in 200..299){"AI request failed"}
            val bytes=connection.inputStream.use { input -> val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=2_000_000);out.write(b,0,n)};out.toByteArray() }
            JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {connection.disconnect()}
    }
}
