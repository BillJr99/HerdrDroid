package com.billmongan.herdrdroid

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider that lacks the algorithms SSH needs
        // (ed25519, curve25519). Replace it with the full Bouncy Castle provider.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
