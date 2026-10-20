package com.example.agent

/** Conservative pre-dispatch label guard, independent of the model's claimed approval. */
object ScreenSafety {
    private val sensitive=Regex("(?i)(\\b(send|pay|purchase|buy|checkout|delete|remove|transfer|submit|confirm|book|reserve|share|publish|post)\\b|ส่ง|ชำระ|ซื้อ|จ่าย|ลบ|โอน|ยืนยัน|จอง|แชร์|เผยแพร่)")
    fun requiresConfirmation(label:String):Boolean = sensitive.containsMatchIn(label)
}
