package com.example.cloud

import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Ensures TLS 1.2+ is enabled on Android 6.0.1 (API 23) devices while preserving
 * strict platform X.509 certificate chain and hostname verification.
 */
class Tls12SocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        enableTls12(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        enableTls12(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        enableTls12(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        enableTls12(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        enableTls12(delegate.createSocket(address, port, localAddress, localPort))

    private fun enableTls12(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols.orEmpty()
            val desired = buildList {
                if ("TLSv1.3" in supported) add("TLSv1.3")
                if ("TLSv1.2" in supported) add("TLSv1.2")
            }
            if (desired.isNotEmpty()) {
                socket.enabledProtocols = desired.toTypedArray()
            }
        }
        return socket
    }
}

object Tls12OkHttpFactory {

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: error("System default X509TrustManager not found")
    }

    private fun createTls12SslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(trustManager), null)
        return Tls12SocketFactory(sslContext.socketFactory)
    }

    fun requireHttpsBaseUrl(baseUrl: String) {
        require(baseUrl.startsWith("https://", ignoreCase = false)) {
            "RetroTok Cloud API requires HTTPS. Plaintext HTTP is forbidden."
        }
    }

    fun createBackendApiClient(
        tokenProvider: () -> String?
    ): OkHttpClient {
        val trustManager = systemTrustManager()
        val sslSocketFactory = createTls12SslSocketFactory(trustManager)

        val authAndHeaderInterceptor = Interceptor { chain ->
            val original = chain.request()
            val isPairEndpoint = original.url.encodedPath.endsWith("/devices/pair")
            val builder = original.newBuilder()
                .header("Accept", "application/json")
            if (!isPairEndpoint) {
                val token = tokenProvider()
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
            }
            chain.proceed(builder.build())
        }

        val httpsEnforcementInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (!request.isHttps) {
                throw java.io.IOException("Blocked non-HTTPS request to ${request.url.host}")
            }
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(httpsEnforcementInterceptor)
            .addInterceptor(authAndHeaderInterceptor)
            .build()
    }

    /**
     * Dedicated raw OkHttp client for direct signed Cloudflare R2 PUT uploads.
     * Contains NO Authorization Bearer interceptor and long streaming timeouts.
     */
    fun createR2UploadClient(): OkHttpClient {
        val trustManager = systemTrustManager()
        val sslSocketFactory = createTls12SslSocketFactory(trustManager)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (!req.isHttps) {
                    throw java.io.IOException("Blocked non-HTTPS R2 upload request")
                }
                chain.proceed(req)
            })
            .build()
    }
}
