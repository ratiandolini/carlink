package com.example.carlink

import android.app.Application

/** ერთი საერთო CarViewModel მთელ პროცესზე, რომ ფონური სერვისიც და ეკრანიც ერთ კავშირს იყენებდეს. */
class CarLinkApplication : Application() {
    val vm: CarViewModel by lazy { CarViewModel(this) }
}
