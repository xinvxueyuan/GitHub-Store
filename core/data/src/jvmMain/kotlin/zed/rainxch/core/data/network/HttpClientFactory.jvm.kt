package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Credentials
import zed.rainxch.core.domain.model.ProxyConfig
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector

actual fun createPlatformHttpClient(proxyConfig: ProxyConfig): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                // Reset any inherited global SOCKS authenticator before
                // deciding what this client needs — prevents a stale
                // Authenticator from a previous [ProxyConfig.Socks] client
                // leaking into a subsequently-built plain client.
                Authenticator.setDefault(null)

                when (proxyConfig) {
                    is ProxyConfig.None -> {
                        proxy(Proxy.NO_PROXY)
                    }

                    is ProxyConfig.System -> {
                        proxySelector(ProxySelector.getDefault())
                    }

                    is ProxyConfig.Http -> {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyConfig.host, proxyConfig.port)))
                        val username = proxyConfig.username
                        val password = proxyConfig.password
                        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                            proxyAuthenticator { _, response ->
                                response.request
                                    .newBuilder()
                                    .header(
                                        "Proxy-Authorization",
                                        Credentials.basic(username, password),
                                    ).build()
                            }
                        }
                    }

                    is ProxyConfig.Socks -> {
                        proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyConfig.host, proxyConfig.port)))
                        val username = proxyConfig.username
                        val password = proxyConfig.password
                        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                            // SOCKS5 username/password auth goes through
                            // java.net.Authenticator (OkHttp has no
                            // dedicated SOCKS auth hook), so install a
                            // default authenticator keyed on host:port.
                            Authenticator.setDefault(
                                object : Authenticator() {
                                    override fun getPasswordAuthentication() =
                                        PasswordAuthentication(
                                            username,
                                            password.toCharArray(),
                                        )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
