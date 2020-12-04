package np.com.sarbagyastha.youtube_player_flutter_example

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.twitter.sdk.android.core.*
import com.twitter.sdk.android.core.identity.TwitterAuthClient
import io.flutter.app.FlutterActivity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugins.GeneratedPluginRegistrant
import java.util.*

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeneratedPluginRegistrant.registerWith(this)

        MethodChannel(flutterView, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "openFullVideo" -> openMxplayer(call.argument("url"), call.argument("audioOnly"))
                METHOD_GET_CURRENT_SESSION -> getCurrentSession(result, call)
                METHOD_AUTHORIZE -> authorize(result, call)
                else -> result.notImplemented()
            }
        }
    }

    private fun canResolveIntent(intent: Intent): Boolean {
        val resolveInfo: List<ResolveInfo>? = packageManager.queryIntentActivities(intent, 0)
        return resolveInfo != null && resolveInfo.isNotEmpty()
    }

    private fun openMxplayer(url: String?, audioOnly: Boolean?) {
        if (url==null) return

        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage("com.mxtech.videoplayer.pro")

        intent.setDataAndTypeAndNormalize(uri, "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("decode_mode", 2.toByte())
        intent.putExtra("orientation", 0.toInt())
        if (audioOnly ?: false) intent.putExtra("video", false)
        intent.putExtra("sticky", true)
        intent.putExtra("secure_uri", true)
        intent.putExtra("video_zoom", 2.toInt())
        if (Build.VERSION.SDK_INT >= 19) intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            this.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                val packageName = "com.mxtech.videoplayer.ad"
                intent.setPackage(packageName)
                this.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pub:MX+Media+%28formerly+J2+Interactive%29&hl=en_US&gl=US")))
            }
        }
    }

    companion object {
        private const val CHANNEL = "samples.flutter.dev/gabor"
        private const val METHOD_GET_CURRENT_SESSION = "getCurrentSession"
        private const val METHOD_AUTHORIZE = "authorize"
    }

    private var authClientInstance: TwitterAuthClient? = null

    private fun getCurrentSession(result: MethodChannel.Result, call: MethodCall) {
        initializeAuthClient(call)
        val session = TwitterCore.getInstance().sessionManager.activeSession
        val sessionMap = sessionToMap(session)
        result.success(sessionMap)
    }

    private fun authorize(res: MethodChannel.Result, call: MethodCall) {

        initializeAuthClient(call)!!.authorize(this, object : Callback<TwitterSession>() {

            override fun success(result: Result<TwitterSession>?) {
                val sessionMap = sessionToMap(result?.data)
                val resultMap: HashMap<String, Any> = object : HashMap<String, Any>() {
                    init {
                        put("status", "loggedIn")
                        put("session", sessionMap ?: "")
                    }
                }
                res.success(resultMap)
            }

            override fun failure(exception: TwitterException?) {
                val resultMap: HashMap<String, Any> = object : HashMap<String, Any>() {
                    init {
                        put("status", "error")
                        put("errorMessage", exception?.message ?: "unknown error")
                    }
                }
                res.success(resultMap)
            }
        })
    }

    private fun initializeAuthClient(call: MethodCall): TwitterAuthClient? {
        if (authClientInstance == null) {
            val consumerKey = call.argument<String>("consumerKey")
            val consumerSecret = call.argument<String>("consumerSecret")
            authClientInstance = configureClient(consumerKey, consumerSecret)
            authClientInstance!!.cancelAuthorize()
        }
        return authClientInstance
    }

    private fun configureClient(consumerKey: String?, consumerSecret: String?): TwitterAuthClient? {
        val authConfig = TwitterAuthConfig(consumerKey, consumerSecret)
        val config = TwitterConfig.Builder(this)
                .twitterAuthConfig(authConfig)
                .build()
        Twitter.initialize(config)
        return TwitterAuthClient()
    }

    private fun sessionToMap(session: TwitterSession?): HashMap<String, Any>? {
        return if (session == null) {
            null
        } else object : HashMap<String, Any>() {
            init {
                put("secret", session.authToken.secret)
                put("token", session.authToken.token)
                put("userId", session.userId.toString())
                put("username", session.userName)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (authClientInstance != null) {
            authClientInstance!!.onActivityResult(requestCode, resultCode, data)
        }
    }
}