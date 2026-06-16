package com.bhuvan.callback.ml

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream

/**
 * Gemma-4-E2B-IT vision-language model via LiteRT-LM [Engine]; each [describe] call is stateless.
 */
class VLMService(
    private val engine: Engine?,
) {
    private val sampler = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4, seed = 1)

    /** Describes [crop] with the SPEC phase-4 prompt; returns a single-line phrase. */
    fun describe(crop: Bitmap): String {
        if (engine == null) return "object"
        val jpeg = bitmapToJpeg(crop)
        val userContents =
            Contents.of(
                Content.ImageBytes(jpeg),
                Content.Text(USER_PROMPT_TAIL),
            )
        val system = Contents.of(Content.Text(SYSTEM_INSTRUCTION))
        val cfg = ConversationConfig(system, emptyList(), emptyList(), sampler, false)
        val conv: Conversation = engine.createConversation(cfg)
        conv.use { c ->
            val reply = c.sendMessage(Message.user(userContents))
            return extractText(reply).trim().lowercase()
        }
    }

    private fun extractText(msg: Message): String {
        val parts = StringBuilder()
        for (piece in msg.contents.contents) {
            if (piece is Content.Text) {
                parts.append(piece.text)
            }
        }
        return parts.toString().ifBlank { msg.toString() }
    }

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return bos.toByteArray()
    }

    companion object {
        /** System line instructing one short retrieval phrase (SPEC §4). */
        val SYSTEM_INSTRUCTION: String =
            """
            You caption a single object for a spatial memory app. Reply with one short phrase only.
            """.trimIndent()

        /** User turn text paired with the crop image (examples + instruction from SPEC §4). */
        val USER_PROMPT_TAIL: String =
            """
            Describe this object in one short phrase suitable for later retrieval. Include color and one or two distinguishing features.
            Do not add commentary. Examples:
              "blue ceramic coffee mug, chipped rim"
              "black spiral notebook with red sticker"
              "silver MacBook, lid open"
            Now describe the object in the image.
            """.trimIndent()
    }
}
