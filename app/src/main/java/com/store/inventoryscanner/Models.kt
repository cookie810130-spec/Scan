package com.store.inventoryscanner

data class ItemInfo(
    val customCode: String,
    val intlCode: String,
    val name: String
)

data class ScanRecord(
    val customCode: String,
    val intlCode: String,
    val name: String,
    var qty: Int,
    var lastTime: String
)
