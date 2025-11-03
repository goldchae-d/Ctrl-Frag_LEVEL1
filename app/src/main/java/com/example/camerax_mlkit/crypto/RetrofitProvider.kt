// crypto/RetrofitProvider.kt (선택)
package com.example.camerax_mlkit.crypto

import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object RetrofitProvider {
    // HTTPS 엔드포인트로 변경!
    private const val BASE_URL = "https://<your-key-server>/"

    val keyApi: KeyApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(KeyApi::class.java)
    }
}
